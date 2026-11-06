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
import com.bob.ai.domain.modelsFor
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
 * Provider Claude (SPEC §2).
 * Streaming thật qua SSE: POST https://api.anthropic.com/v1/messages.
 */
class ClaudeProvider(private val context: Context) : AiProvider {

    override val type: ProviderType = ProviderType.CLAUDE

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
        val maxTokens = modelsFor(ProviderType.CLAUDE)
            .find { it.id == request.modelId }?.maxOutputTokens ?: 8_192

        val httpRequest = try {
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(
                    buildRequestBody(request, maxTokens)
                        .toRequestBody("application/json".toMediaType())
                )
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

                // Claude gửi kèm dòng "event: <loại>" trước mỗi "data:".
                var currentEvent: String? = null
                val toolCalls = LinkedHashMap<Int, ToolCallAccumulator>()
                var line: String?
                while (source.readUtf8Line().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) {
                        currentEvent = null
                        continue
                    }
                    if (trimmed.startsWith("event:")) {
                        currentEvent = trimmed.removePrefix("event:").trim()
                        continue
                    }
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    val element = try {
                        json.parseToJsonElement(data)
                    } catch (_: Exception) {
                        continue
                    }
                    val obj = element as? JsonObject ?: continue
                    val eventType = currentEvent
                        ?: (obj["type"] as? JsonPrimitive)?.contentOrNull
                    when (eventType) {
                        "error" -> {
                            val errorObj = obj["error"] as? JsonObject
                            val message = (errorObj?.get("message") as? JsonPrimitive)?.contentOrNull
                                ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
                                ?: "Claude API error."
                            emit(StreamChunk.Error(message))
                            return@flow
                        }
                        "content_block_start" -> {
                            val index = (obj["index"] as? JsonPrimitive)
                                ?.contentOrNull?.toIntOrNull() ?: 0
                            val block = obj["content_block"] as? JsonObject ?: continue
                            if ((block["type"] as? JsonPrimitive)?.contentOrNull == "tool_use") {
                                toolCalls[index] = ToolCallAccumulator(
                                    id = (block["id"] as? JsonPrimitive)?.contentOrNull,
                                    name = StringBuilder(
                                        (block["name"] as? JsonPrimitive)?.contentOrNull
                                            .orEmpty()
                                    )
                                )
                            }
                        }
                        "content_block_delta" -> {
                            val index = (obj["index"] as? JsonPrimitive)
                                ?.contentOrNull?.toIntOrNull() ?: 0
                            val delta = obj["delta"] as? JsonObject ?: continue
                            when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                                "text_delta" -> {
                                    val text = (delta["text"] as? JsonPrimitive)?.contentOrNull
                                    if (!text.isNullOrEmpty()) emit(StreamChunk.TextDelta(text))
                                }
                                "input_json_delta" -> {
                                    val partial = (delta["partial_json"] as? JsonPrimitive)
                                        ?.contentOrNull
                                    if (partial != null) {
                                        toolCalls[index]?.arguments?.append(partial)
                                    }
                                }
                            }
                        }
                        // "message_stop" / "content_block_stop" / "ping": không cần xử lý
                        // riêng, tool call gom đủ sẽ được phát sau vòng lặp.
                        else -> Unit
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
            emit(StreamChunk.Error(e.message ?: "Lỗi mạng khi gọi Claude."))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Request body
    // ------------------------------------------------------------------

    private fun buildRequestBody(request: ChatRequest, maxTokens: Int): String {
        // Claude chỉ cho phép role user/assistant trong messages; tin SYSTEM
        // trong lịch sử được gộp vào system prompt. Các tin liên tiếp cùng
        // role được gộp lại thành một message (Claude yêu cầu xen kẽ).
        val systemParts = mutableListOf<String>()
        if (request.systemPrompt.isNotBlank()) systemParts.add(request.systemPrompt)

        val pendingToolCalls = ArrayDeque<Pair<String, String>>() // (id, name)
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun addBlock(role: String, block: JsonObject) {
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                last.second.add(block)
            } else {
                merged.add(role to mutableListOf(block))
            }
        }

        for (message in request.messages) {
            when (message.role) {
                Role.SYSTEM -> {
                    if (message.content.isNotBlank()) systemParts.add(message.content)
                }
                Role.USER -> {
                    if (message.content.isNotBlank()) {
                        addBlock(
                            "user",
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            }
                        )
                    }
                    for (attachment in message.attachments) {
                        if (attachment.type != AttachmentType.IMAGE) continue
                        val base64 = readImageBase64(attachment) ?: continue
                        addBlock(
                            "user",
                            buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", attachment.mimeType ?: "image/jpeg")
                                    put("data", base64)
                                }
                            }
                        )
                    }
                }
                Role.ASSISTANT -> {
                    if (message.content.isNotBlank()) {
                        addBlock(
                            "assistant",
                            buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            }
                        )
                    }
                    for (toolCall in message.toolCalls) {
                        pendingToolCalls.addLast(toolCall.id to toolCall.name)
                        addBlock(
                            "assistant",
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolCall.id)
                                put("name", toolCall.name)
                                put("input", parseParameters(toolCall.argumentsJson))
                            }
                        )
                    }
                }
                Role.TOOL -> {
                    val (callId, _) = if (pendingToolCalls.isNotEmpty()) {
                        pendingToolCalls.removeFirst()
                    } else {
                        message.id to ""
                    }
                    addBlock(
                        "user",
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", callId)
                            put("content", message.content)
                        }
                    )
                }
            }
        }

        val body = buildJsonObject {
            put("model", request.modelId)
            put("max_tokens", maxTokens)
            if (systemParts.isNotEmpty()) {
                put("system", systemParts.joinToString("\n\n"))
            }
            putJsonArray("messages") {
                for ((role, blocks) in merged) {
                    addJsonObject {
                        put("role", role)
                        putJsonArray("content") {
                            for (block in blocks) add(block)
                        }
                    }
                }
            }
            put("stream", true)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (def in request.tools) {
                        addJsonObject {
                            put("name", def.name)
                            put("description", def.description)
                            put("input_schema", parseParameters(def.parametersJson))
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

    private class ToolCallAccumulator(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )
}
