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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * Provider Gemini (SPEC §2).
 * Streaming thật qua SSE: POST {baseUrl}/models/{model}:streamGenerateContent?alt=sse.
 */
class GeminiProvider(private val context: Context) : AiProvider {

    override val type: ProviderType = ProviderType.GEMINI

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
                .url("$baseUrl/models/${request.modelId}:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", apiKey)
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
                        val message = (err["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "Gemini API error."
                        emit(StreamChunk.Error(message))
                        return@flow
                    }
                    val candidates = obj["candidates"] as? JsonArray
                    if (candidates == null) {
                        // Không có candidate: có thể prompt bị chặn.
                        val blockReason = ((obj["promptFeedback"] as? JsonObject)
                            ?.get("blockReason") as? JsonPrimitive)?.contentOrNull
                        if (blockReason != null) {
                            emit(StreamChunk.Error("Yêu cầu bị chặn: $blockReason."))
                            return@flow
                        }
                        continue
                    }
                    for (candidateElement in candidates) {
                        val candidate = candidateElement as? JsonObject ?: continue
                        val content = candidate["content"] as? JsonObject ?: continue
                        val parts = content["parts"] as? JsonArray ?: continue
                        for (partElement in parts) {
                            val part = partElement as? JsonObject ?: continue
                            (part["text"] as? JsonPrimitive)?.contentOrNull?.let { text ->
                                if (text.isNotEmpty()) emit(StreamChunk.TextDelta(text))
                            }
                            val functionCall = part["functionCall"] as? JsonObject
                            if (functionCall != null) {
                                val name = (functionCall["name"] as? JsonPrimitive)?.contentOrNull
                                if (!name.isNullOrBlank()) {
                                    val args = functionCall["args"] ?: buildJsonObject {}
                                    emit(
                                        StreamChunk.ToolCall(
                                            ToolCallRequest(
                                                uid = "",
                                                nameUsed = name,
                                                argumentsJson = args.toString()
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                emit(StreamChunk.Done)
            }
        } catch (e: Exception) {
            emit(StreamChunk.Error(e.message ?: "Lỗi mạng khi gọi Gemini."))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    private fun buildRequestBody(request: ChatRequest): String {
        // Gemini dùng role user/model, kết quả tool dùng role "function".
        // Tin SYSTEM trong lịch sử được gộp vào systemInstruction. Các
        // content liên tiếp cùng role được gộp lại thành một content.
        val systemParts = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) systemParts.add(request.systemPrompt)

        val pendingToolCalls = ArrayDeque<Pair<String, String>>() // (id, name)
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun addPart(role: String, part: JsonObject) {
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                last.second.add(part)
            } else {
                merged.add(role to mutableListOf(part))
            }
        }

        for (message in request.messages) {
            when (message.role) {
                Role.SYSTEM -> {
                    if (message.content.isNotBlank()) systemParts.add(message.content)
                }
                Role.USER -> {
                    if (message.content.isNotBlank()) {
                        addPart(
                            "user",
                            buildJsonObject { put("text", message.content) }
                        )
                    }
                    for (attachment in message.attachments) {
                        if (attachment.type != AttachmentType.IMAGE) continue
                        val base64 = readImageBase64(attachment) ?: continue
                        addPart(
                            "user",
                            buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", attachment.mimeType ?: "image/jpeg")
                                    put("data", base64)
                                }
                            }
                        )
                    }
                }
                Role.ASSISTANT -> {
                    if (message.content.isNotBlank()) {
                        addPart(
                            "model",
                            buildJsonObject { put("text", message.content) }
                        )
                    }
                    for (toolCall in message.toolCalls) {
                        pendingToolCalls.addLast(toolCall.id to toolCall.name)
                        addPart(
                            "model",
                            buildJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", toolCall.name)
                                    put("args", parseParameters(toolCall.argumentsJson))
                                }
                            }
                        )
                    }
                }
                Role.TOOL -> {
                    val (_, name) = if (pendingToolCalls.isNotEmpty()) {
                        pendingToolCalls.removeFirst()
                    } else {
                        message.id to ""
                    }
                    addPart(
                        "function",
                        buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", name)
                                putJsonObject("response") {
                                    put("result", message.content)
                                }
                            }
                        }
                    )
                }
            }
        }

        val body = buildJsonObject {
            if (systemParts.isNotEmpty()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemParts.joinToString("\n\n"))
                        }
                    }
                }
            }
            putJsonArray("contents") {
                for ((role, parts) in merged) {
                    addJsonObject {
                        put("role", role)
                        putJsonArray("parts") {
                            for (part in parts) add(part)
                        }
                    }
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonObject("tools") {
                    putJsonArray("functionDeclarations") {
                        for (def in request.tools) {
                            addJsonObject {
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
}
