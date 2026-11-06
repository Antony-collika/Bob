package com.bob.ai.domain

/**
 * Ước lượng token và cắt ngữ cảnh theo giới hạn của từng mô hình (SPEC §5).
 *
 * - Ước lượng ≈ số ký tự / 4, tối thiểu 1 token cho mỗi tin nhắn.
 * - Mỗi ảnh đính kèm tính ~1000 token.
 * - Luôn tính theo nội dung HIỆN TẠI (có thể đã sửa tay) vì đó là nội dung
 *   được gửi đi ở lần chat tiếp theo.
 */
object TokenEstimator {

    private const val IMAGE_TOKENS = 1000

    /** Ước lượng số token của một đoạn chữ. Tối thiểu 1 token. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 1
        return maxOf(1, (text.length + 3) / 4)
    }

    private fun messageTokens(message: ChatMessage): Int {
        var tokens = estimateTokens(message.content)
        tokens += message.attachments.count { it.type == AttachmentType.IMAGE } * IMAGE_TOKENS
        return maxOf(1, tokens)
    }

    /**
     * Cắt [messages] sao cho vừa ngân sách ngữ cảnh còn lại.
     *
     * Ngân sách = [contextWindow] - [maxOutputTokens] - token của [systemPrompt]
     * (luôn chừa chỗ cho system prompt và cho phần trả lời tối đa của model).
     *
     * - Giữ các lượt (turn) mới nhất còn vừa ngân sách, bỏ các lượt cũ nhất
     *   trước, luôn bỏ nguyên một lượt (câu hỏi + câu trả lời đi kèm) chứ
     *   không cắt lẻ một tin nhắn trong lượt.
     * - Không bao giờ bỏ lượt chứa tin nhắn user cuối cùng, kể cả khi riêng
     *   lượt đó đã vượt ngân sách.
     * - Trả về các tin nhắn theo đúng thứ tự gốc.
     */
    fun trimMessages(
        messages: List<ChatMessage>,
        systemPrompt: String,
        contextWindow: Int,
        maxOutputTokens: Int
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val budget = contextWindow - maxOutputTokens - estimateTokens(systemPrompt)

        // Nhóm tin nhắn thành các lượt: một lượt bắt đầu ở mỗi tin nhắn USER
        // (hoặc SYSTEM) và gồm các tin ASSISTANT/TOOL đi sau nó cho tới lượt
        // tiếp theo. Tin ASSISTANT/TOOL lẻ ở đầu lịch sử thành một lượt riêng.
        val turns = mutableListOf<MutableList<ChatMessage>>()
        var current: MutableList<ChatMessage>? = null
        for (message in messages) {
            if (message.role == Role.USER || message.role == Role.SYSTEM) {
                current = mutableListOf()
                turns.add(current)
            }
            if (current == null) {
                current = mutableListOf()
                turns.add(current)
            }
            current.add(message)
        }

        val lastUserTurnIndex = turns.indexOfLast { turn ->
            turn.any { it.role == Role.USER }
        }

        // Lịch sử không có tin nhắn user: giữ hậu tố vừa ngân sách, ít nhất
        // giữ lại tin nhắn cuối cùng để request không bị rỗng.
        if (lastUserTurnIndex < 0) {
            val kept = ArrayDeque<ChatMessage>()
            var used = 0
            for (message in messages.asReversed()) {
                val tokens = messageTokens(message)
                if (kept.isNotEmpty() && used + tokens > budget) break
                kept.addFirst(message)
                used += tokens
            }
            return kept.toList()
        }

        val turnTokens = turns.map { turn -> turn.sumOf { messageTokens(it) } }
        val kept = ArrayDeque<List<ChatMessage>>()
        var used = 0
        for (i in turns.indices.reversed()) {
            if (i == lastUserTurnIndex) {
                // Lượt chứa tin nhắn user cuối cùng: luôn giữ.
                kept.addFirst(turns[i])
                used += turnTokens[i]
                continue
            }
            if (used + turnTokens[i] <= budget) {
                kept.addFirst(turns[i])
                used += turnTokens[i]
            }
            // Ngược lại: bỏ nguyên lượt cũ này.
        }
        return kept.flatten()
    }
}
