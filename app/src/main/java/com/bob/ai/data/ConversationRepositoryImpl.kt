package com.bob.ai.data

import android.content.Context
import com.bob.ai.data.local.AppDatabase
import com.bob.ai.data.local.AttachmentDto
import com.bob.ai.data.local.ConversationEntity
import com.bob.ai.data.local.MessageEntity
import com.bob.ai.data.local.ToolCallDto
import com.bob.ai.data.local.bobJson
import com.bob.ai.domain.Attachment
import com.bob.ai.domain.AttachmentType
import com.bob.ai.domain.ChatMessage
import com.bob.ai.domain.Conversation
import com.bob.ai.domain.ConversationRepository
import com.bob.ai.domain.Role
import com.bob.ai.domain.SearchHit
import com.bob.ai.domain.ToolCallRecord
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

class ConversationRepositoryImpl(
    private val context: Context,
    private val db: AppDatabase
) : ConversationRepository {

    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private fun ConversationEntity.toDomain(): Conversation = Conversation(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        providerId = providerId,
        modelId = modelId,
        systemPrompt = systemPrompt
    )

    private fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        providerId = providerId,
        modelId = modelId,
        systemPrompt = systemPrompt
    )

    private fun MessageEntity.toDomain(): ChatMessage {
        val attachments = try {
            bobJson.decodeFromString(
                ListSerializer(AttachmentDto.serializer()), attachmentsJson
            ).map { dto ->
                Attachment(
                    id = dto.id,
                    type = runCatching { AttachmentType.valueOf(dto.type) }
                        .getOrDefault(AttachmentType.IMAGE),
                    uri = dto.uri,
                    mimeType = dto.mimeType,
                    fileName = dto.fileName
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        val toolCalls = try {
            bobJson.decodeFromString(
                ListSerializer(ToolCallDto.serializer()), toolCallsJson
            ).map { dto ->
                ToolCallRecord(
                    id = dto.id,
                    uid = dto.uid,
                    name = dto.name,
                    argumentsJson = dto.argumentsJson,
                    result = dto.result,
                    success = dto.success,
                    createdAt = dto.createdAt
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
            content = content,
            edited = edited,
            attachments = attachments,
            createdAt = createdAt,
            error = error,
            toolCalls = toolCalls
        )
    }

    private fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        edited = edited,
        attachmentsJson = bobJson.encodeToString(
            ListSerializer(AttachmentDto.serializer()),
            attachments.map { a ->
                AttachmentDto(
                    id = a.id,
                    type = a.type.name,
                    uri = a.uri,
                    mimeType = a.mimeType,
                    fileName = a.fileName
                )
            }
        ),
        createdAt = createdAt,
        error = error,
        toolCallsJson = bobJson.encodeToString(
            ListSerializer(ToolCallDto.serializer()),
            toolCalls.map { t ->
                ToolCallDto(
                    id = t.id,
                    uid = t.uid,
                    name = t.name,
                    argumentsJson = t.argumentsJson,
                    result = t.result,
                    success = t.success,
                    createdAt = t.createdAt
                )
            }
        )
    )

    // ------------------------------------------------------------------
    // Observe / read
    // ------------------------------------------------------------------

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeByConversation(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun getConversation(id: String): Conversation? =
        conversationDao.getById(id)?.toDomain()

    override suspend fun getMessages(id: String): List<ChatMessage> =
        messageDao.getByConversation(id).map { it.toDomain() }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    override suspend fun createConversation(
        title: String,
        providerId: String,
        modelId: String,
        systemPrompt: String
    ): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            modelId = modelId,
            systemPrompt = systemPrompt
        )
        conversationDao.insert(conversation.toEntity())
        return conversation
    }

    override suspend fun renameConversation(id: String, title: String) {
        val existing = conversationDao.getById(id) ?: return
        conversationDao.update(
            existing.copy(title = title, updatedAt = System.currentTimeMillis())
        )
    }

    override suspend fun deleteConversation(id: String) {
        // Messages are removed by the CASCADE foreign key; delete explicitly
        // as well so the rows are gone even if foreign keys are ever off.
        messageDao.deleteByConversation(id)
        conversationDao.deleteById(id)
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    override suspend fun addMessage(message: ChatMessage) {
        messageDao.insert(message.toEntity())
        // Keep the conversation list ordered by recent activity.
        val conversation = conversationDao.getById(message.conversationId)
        if (conversation != null) {
            conversationDao.update(
                conversation.copy(updatedAt = maxOf(conversation.updatedAt, message.createdAt))
            )
        }
    }

    override suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message.toEntity())
    }

    override suspend fun deleteMessage(id: String) {
        messageDao.deleteById(id)
    }

    override suspend fun deleteTurn(conversationId: String, userMessageId: String) {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == userMessageId }
        if (index < 0) return
        messageDao.deleteById(messages[index].id)
        // The assistant reply immediately after the user message, if any.
        if (index + 1 < messages.size && messages[index + 1].role == Role.ASSISTANT.name) {
            messageDao.deleteById(messages[index + 1].id)
        }
    }

    override suspend fun deleteMessagesAfter(conversationId: String, messageId: String) {
        val messages = messageDao.getByConversation(conversationId)
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        for (i in index + 1 until messages.size) {
            messageDao.deleteById(messages[i].id)
        }
    }

    override suspend fun branchConversation(
        sourceId: String,
        upToMessageId: String,
        newTitle: String
    ): Conversation {
        val source = conversationDao.getById(sourceId)
            ?: throw IllegalArgumentException("Không tìm thấy cuộc trò chuyện gốc")
        val messages = messageDao.getByConversation(sourceId)
        val upToIndex = messages.indexOfFirst { it.id == upToMessageId }
        if (upToIndex < 0) {
            throw IllegalArgumentException("Không tìm thấy tin nhắn để branch")
        }
        val now = System.currentTimeMillis()
        val branched = Conversation(
            id = UUID.randomUUID().toString(),
            title = newTitle,
            createdAt = now,
            updatedAt = now,
            providerId = source.providerId,
            modelId = source.modelId,
            systemPrompt = source.systemPrompt
        )
        conversationDao.insert(branched.toEntity())
        for (i in 0..upToIndex) {
            val original = messages[i]
            messageDao.insert(
                original.copy(
                    id = UUID.randomUUID().toString(),
                    conversationId = branched.id
                    // content, edited flag, attachmentsJson, toolCallsJson,
                    // createdAt and error are copied unchanged.
                )
            )
        }
        return branched
    }

    // ------------------------------------------------------------------
    // Search (over current content, including edited content)
    // ------------------------------------------------------------------

    override suspend fun searchAll(query: String): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (conversation in conversationDao.getAll()) {
            for (entity in messageDao.getByConversation(conversation.id)) {
                if (entity.content.contains(needle, ignoreCase = true)) {
                    val message = entity.toDomain()
                    hits.add(
                        SearchHit(
                            conversationId = conversation.id,
                            conversationTitle = conversation.title,
                            messageId = entity.id,
                            snippet = makeSnippet(entity.content, needle),
                            role = message.role
                        )
                    )
                }
            }
        }
        return hits
    }

    override suspend fun searchInConversation(
        conversationId: String,
        query: String
    ): List<ChatMessage> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return messageDao.getByConversation(conversationId)
            .filter { it.content.contains(needle, ignoreCase = true) }
            .map { it.toDomain() }
    }

    /** ~80 characters around the first match, with "…" where text was cut. */
    private fun makeSnippet(content: String, query: String): String {
        val flat = content.replace('\n', ' ').replace('\r', ' ')
        val index = flat.indexOf(query, ignoreCase = true)
        if (index < 0) return flat.take(80)
        val start = maxOf(0, index - 30)
        var end = minOf(flat.length, start + 80)
        if (end < index + query.length) {
            end = minOf(flat.length, index + query.length)
        }
        var snippet = flat.substring(start, end)
        if (start > 0) snippet = "…$snippet"
        if (end < flat.length) snippet = "$snippet…"
        return snippet
    }
}
