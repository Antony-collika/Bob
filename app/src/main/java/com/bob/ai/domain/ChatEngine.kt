package com.bob.ai.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Động cơ chat: trim ngữ cảnh, stream câu trả lời từ provider và chạy vòng
 * gọi công cụ (SPEC §10, §14, §15).
 *
 * - Công cụ được gửi cho model là [ToolRegistry.effectiveDefinitions]: chỉ
 *   công cụ đang bật, control != OFF, và công cụ file bị loại khi chưa có
 *   workspace.
 * - Model gọi công cụ theo tên (có thể đã đổi tên) -> quy ngược về UID cố
 *   định bằng [ToolRegistry.resolveUidByName]; lịch sử lưu theo UID nên đổi
 *   tên không làm hỏng cuộc trò chuyện cũ.
 * - Mức kiểm soát: OFF thì không chạy, ASK thì hỏi [requestApproval] trước
 *   (từ chối thì bỏ qua), AUTO chạy thẳng.
 * - Không bao giờ crash vì lỗi provider: lỗi được trả về trong
 *   [GenerateResult.error].
 */
class ChatEngine(
    private val providerFactory: ProviderFactory,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor
) {

    data class GenerateResult(
        val text: String,
        val toolCalls: List<ToolCallRecord>,
        val error: String?
    )

    /** Ném ra để hủy việc collect stream ngay khi người dùng bấm dừng. */
    private class StoppedException : CancellationException("Stopped by user")

    suspend fun generate(
        provider: ProviderConfig,
        modelId: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        workspaceAvailable: Boolean,
        onDelta: (String) -> Unit,
        onToolCall: (ToolCallRecord) -> Unit,
        requestApproval: suspend (ToolCallRequest) -> Boolean,
        isStopped: () -> Boolean,
        maxToolRounds: Int = 6
    ): GenerateResult {
        val records = mutableListOf<ToolCallRecord>()
        var finalText = ""
        var error: String? = null

        try {
            val model = modelsFor(provider.type).find { it.id == modelId }
                ?: modelsFor(provider.type).firstOrNull()
            val contextWindow = model?.contextWindow ?: 128_000
            val maxOutputTokens = model?.maxOutputTokens ?: 8_192

            val tools = toolRegistry.effectiveDefinitions(workspaceAvailable)
            val trimmed = TokenEstimator.trimMessages(
                messages = history,
                systemPrompt = systemPrompt,
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens
            )

            val aiProvider = try {
                providerFactory.create(provider)
            } catch (e: Exception) {
                return GenerateResult(
                    text = "",
                    toolCalls = emptyList(),
                    error = e.message ?: "Không tạo được provider."
                )
            }

            val conversationId = history.firstOrNull()?.conversationId ?: ""
            val currentHistory = trimmed.toMutableList()
            var toolRounds = 0

            while (true) {
                if (isStopped()) break

                val roundText = StringBuilder()
                val pendingCalls = mutableListOf<ToolCallRequest>()
                var roundError: String? = null
                var stopped = false

                try {
                    aiProvider.streamChat(
                        ChatRequest(
                            provider = provider,
                            modelId = modelId,
                            systemPrompt = systemPrompt,
                            messages = currentHistory.toList(),
                            tools = tools
                        )
                    ).collect { chunk ->
                        if (isStopped()) throw StoppedException()
                        when (chunk) {
                            is StreamChunk.TextDelta -> {
                                roundText.append(chunk.text)
                                try {
                                    onDelta(chunk.text)
                                } catch (_: Exception) {
                                    // Callback của UI lỗi thì không làm hỏng vòng chat.
                                }
                            }
                            is StreamChunk.ToolCall -> pendingCalls.add(chunk.request)
                            is StreamChunk.Done -> Unit
                            is StreamChunk.Error -> roundError = chunk.message
                        }
                    }
                } catch (e: StoppedException) {
                    stopped = true
                }
                if (stopped || isStopped()) break

                if (roundError != null) {
                    error = roundError
                    finalText = roundText.toString()
                    break
                }
                finalText = roundText.toString()

                // Model không gọi công cụ nữa: đây là câu trả lời cuối cùng.
                if (pendingCalls.isEmpty()) break

                if (toolRounds >= maxToolRounds) {
                    error = "Đã đạt giới hạn $maxToolRounds vòng gọi công cụ liên tiếp."
                    break
                }
                toolRounds++

                val roundRecords = mutableListOf<ToolCallRecord>()
                var stoppedDuringTools = false
                for (call in pendingCalls) {
                    val record = handleToolCall(call, requestApproval, isStopped)
                    if (record == null) {
                        // Người dùng bấm dừng ngay trước/khi chạy công cụ.
                        stoppedDuringTools = true
                        break
                    }
                    records.add(record)
                    roundRecords.add(record)
                    try {
                        onToolCall(record)
                    } catch (_: Exception) {
                        // Callback của UI lỗi thì không làm hỏng vòng chat.
                    }
                }
                if (stoppedDuringTools) break

                // Đưa lượt gọi công cụ vào lịch sử để vòng sau model thấy kết quả:
                // tin assistant (kèm danh sách tool call) rồi các tin TOOL.
                currentHistory.add(
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = Role.ASSISTANT,
                        content = roundText.toString(),
                        toolCalls = roundRecords.toList()
                    )
                )
                for (record in roundRecords) {
                    currentHistory.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = Role.TOOL,
                            content = record.result
                        )
                    )
                }
            }
        } catch (e: StoppedException) {
            // Người dùng bấm dừng: trả về phần đã có, không tính là lỗi.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (error == null) error = e.message ?: "Đã xảy ra lỗi không xác định."
        }

        return GenerateResult(text = finalText, toolCalls = records, error = error)
    }

    /**
     * Xử lý một lần model gọi công cụ: quy tên về UID, kiểm tra mức kiểm
     * soát, hỏi xác nhận nếu cần rồi thực thi. Trả về null chỉ khi người
     * dùng bấm dừng trước khi kịp chạy (để vòng ngoài dừng ngay).
     */
    private suspend fun handleToolCall(
        call: ToolCallRequest,
        requestApproval: suspend (ToolCallRequest) -> Boolean,
        isStopped: () -> Boolean
    ): ToolCallRecord? {
        if (isStopped()) return null

        val uid = toolRegistry.resolveUidByName(call.nameUsed)
            ?: return ToolCallRecord(
                id = UUID.randomUUID().toString(),
                uid = "",
                name = call.nameUsed,
                argumentsJson = call.argumentsJson,
                result = "Không tìm thấy công cụ tên \"${call.nameUsed}\".",
                success = false
            )

        val definition = toolRegistry.definition(uid)
        val control = try {
            toolRegistry.getSetting(uid).control
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            definition?.defaultControl ?: ToolControl.AUTO
        }

        when (control) {
            ToolControl.OFF -> return ToolCallRecord(
                id = UUID.randomUUID().toString(),
                uid = uid,
                name = call.nameUsed,
                argumentsJson = call.argumentsJson,
                result = "Công cụ \"${call.nameUsed}\" đang tắt nên không chạy.",
                success = false
            )
            ToolControl.ASK -> {
                val approved = try {
                    requestApproval(
                        ToolCallRequest(
                            uid = uid,
                            nameUsed = call.nameUsed,
                            argumentsJson = call.argumentsJson
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!approved) {
                    return ToolCallRecord(
                        id = UUID.randomUUID().toString(),
                        uid = uid,
                        name = call.nameUsed,
                        argumentsJson = call.argumentsJson,
                        result = "Bạn đã từ chối cho công cụ \"${call.nameUsed}\" chạy.",
                        success = false
                    )
                }
                if (isStopped()) return null
            }
            ToolControl.AUTO -> Unit
        }

        val result = try {
            toolExecutor.execute(uid, call.argumentsJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(success = false, output = "", error = e.message)
        }
        val resultText = if (result.success) {
            result.output
        } else {
            result.error ?: result.output.ifBlank { "Công cụ thất bại mà không có thông báo lỗi." }
        }
        return ToolCallRecord(
            id = UUID.randomUUID().toString(),
            uid = uid,
            name = call.nameUsed,
            argumentsJson = call.argumentsJson,
            result = resultText,
            success = result.success
        )
    }
}
