package com.bob.ai.data.providers

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.bob.ai.domain.AiProvider
import com.bob.ai.domain.Attachment
import com.bob.ai.domain.AttachmentType
import com.bob.ai.domain.ChatMessage
import com.bob.ai.domain.ChatRequest
import com.bob.ai.domain.ProviderType
import com.bob.ai.domain.Role
import com.bob.ai.domain.StreamChunk
import com.bob.ai.domain.ToolCallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provider OpenAI và Custom tương thích OpenAI (SPEC §2).
 * Streaming thật qua SSE: POST {baseUrl}/chat/completions với stream=true.
 */
class OpenAiProvider(private val context: Context) : AiProvider {

    override val type: ProviderType = ProviderType.OPENAI

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // stream dài: không giới hạn đọc
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    override suspend fun streamChat(request: ChatRequest): Flow<StreamChunk> = flow {
        val apiKey = request.provider.apiKey
        if (apiKey.isBlank()) {
            emit(StreamChunk.Error("Chưa nhập API key cho ${request.provider.name}."))
            return@flow
        }
        val baseUrl = request.provider.effectiveBaseUrl()
        if (baseUrl.isBlank()) {
            emit(StreamChunk.Error("Chưa nhập base URL cho ${request.provider.name}."))
            return@flow
        }

        val httpRequest = try {
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(buildRequestBody(request).toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            emit(StreamChunk.Error(e.message ?: "Không tạo được request."))
            return@flow
        }

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = try {
                        response.body?.string().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    val excerpt = body.take(500).ifBlank { response.message }
                    emit(StreamChunk.Error("HTTP ${response.code}: $excerpt"))
                    return@flow
                }
                val source = response.body?.source()
                if (source == null) {
                    emit(StreamChunk.Error("Máy chủ không trả về nội dung."))
                    return@flow
                }

                // Tool call có thể về theo từng mảnh: gom theo index.
                val toolCalls = LinkedHashMap<Int, ToolCallAccumulator>()
                var line: String?
                while (source.readUtf8Line().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val element = try {
                        json.parseToJsonElement(data)
                    } catch (_: Exception) {
                        continue
                    }
                    val obj = element as? JsonObject ?: continue
                    (obj["error"] as? JsonObject)?.let { err ->
                        emit(
                            StreamChunk.Error(
                                (err["message"] as? JsonPrimitive)?.contentOrNull
                                    ?: "OpenAI API error."
                            )
                        )
                        return@flow
                    }
                    val choice = (obj["choices"] as? JsonArray)
                        ?.firstOrNull() as? JsonObject ?: continue
                    val delta = choice["delta"] as? JsonObject ?: continue
                    (delta["content"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                        if (text.isNotEmpty()) emit(StreamChunk.TextDelta(text))
                    }
                    (delta["tool_calls"] as? JsonArray)?.forEach { toolCallElement ->
                        val toolCall = toolCallElement as? JsonObject ?: return@forEach
                        val index = (toolCall["index"] as? JsonPrimitive)
                            ?.contentOrNull?.toIntOrNull() ?: 0
                        val acc = toolCalls.getOrPut(index) { ToolCallAccumulator() }
                        (toolCall["id"] as? JsonPrimitive)?.contentOrNull?.let { id ->
                            if (acc.id == null) acc.id = id
                        }
                        val function = toolCall["function"] as? JsonObject ?: return@forEach
                        (function["name"] as? JsonPrimitive)?.contentOrNull?.let { name ->
                            acc.name.append(name)
                        }
                        (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { args ->
                            acc.arguments.append(args)
                        }
                    }
                }

                for (acc in toolCalls.values) {
                    val name = acc.name.toString()
                    if (name.isBlank()) continue
                    emit(
                        StreamChunk.ToolCall(
                            ToolCallRequest(
                                uid = "",
                                nameUsed = name,
                                argumentsJson = acc.arguments.toString().ifBlank { "{}" }
                            )
                        )
                    )
                }
                emit(StreamChunk.Done)
            }
        } catch (e: Exception) {
            emit(StreamChunk.Error(e.message ?: "Lỗi mạng khi gọi OpenAI."))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    private fun buildRequestBody(request: ChatRequest): String {
        // Ghép tin TOOL với tool_call_id của tin assistant ngay trước nó.
        val pendingToolCallIds = ArrayDeque<String>()
        val body = buildJsonObject {
            put("model", request.modelId)
            put("stream", true)
            putJsonArray("messages") {
                if (request.systemPrompt.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", request.systemPrompt)
                    }
                }
                for (message in request.messages) {
                    add(buildMessageJson(message, pendingToolCallIds))
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (def in request.tools) {
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", def.name)
                                put("description", def.description)
                                put("parameters", parseParameters(def.parametersJson))
                            }
                        }
                    }
                }
            }
        }
        return body.toString()
    }

    private fun buildMessageJson(
        message: ChatMessage,
        pendingToolCallIds: ArrayDeque<String>
    ): JsonObject = buildJsonObject {
        when (message.role) {
            Role.SYSTEM -> {
                put("role", "system")
                put("content", message.content)
            }
            Role.USER -> {
                put("role", "user")
                val parts = userContentParts(message)
                if (parts.isEmpty()) {
                    put("content", message.content)
                } else {
                    putJsonArray("content") {
                        for (part in parts) add(part)
                    }
                }
            }
            Role.ASSISTANT -> {
                put("role", "assistant")
                put("content", message.content)
                if (message.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        for (toolCall in message.toolCalls) {
                            pendingToolCallIds.addLast(toolCall.id)
                            addJsonObject {
                                put("id", toolCall.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", toolCall.name)
                                    put(
                                        "arguments",
                                        toolCall.argumentsJson.ifBlank { "{}" }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Role.TOOL -> {
                put("role", "tool")
                put(
                    "tool_call_id",
                    if (pendingToolCallIds.isNotEmpty()) pendingToolCallIds.removeFirst()
                    else message.id
                )
                put("content", message.content)
            }
        }
    }

    /** Phần nội dung của tin user: chữ + ảnh (base64 data URL). */
    private fun userContentParts(message: ChatMessage): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        if (message.content.isNotBlank()) {
            parts.add(
                buildJsonObject {
                    put("type", "text")
                    put("text", message.content)
                }
            )
        }
        for (attachment in message.attachments) {
            if (attachment.type != AttachmentType.IMAGE) continue
            val base64 = readImageBase64(attachment) ?: continue
            val mimeType = attachment.mimeType ?: "image/jpeg"
            parts.add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:$mimeType;base64,$base64")
                    }
                }
            )
        }
        return parts
    }

    private fun parseParameters(parametersJson: String): JsonObject = try {
        json.parseToJsonElement(parametersJson).jsonObject
    } catch (_: Exception) {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
    }

    private fun readImageBase64(attachment: Attachment): String? = try {
        context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
            Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }

    private class ToolCallAccumulator {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
