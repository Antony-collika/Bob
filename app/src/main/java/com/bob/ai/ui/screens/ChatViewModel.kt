package com.bob.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.Attachment
import com.bob.ai.domain.ChatMessage
import com.bob.ai.domain.Conversation
import com.bob.ai.domain.Role
import com.bob.ai.domain.ToolCallRecord
import com.bob.ai.domain.ToolCallRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds a regenerate request for a conversation that does not exist yet:
 * when the user chooses "Tạo nhánh mới" for a non-last turn, the branch is
 * created first and the new conversation's ViewModel picks up the pending
 * regeneration from here in its init.
 */
object PendingRegeneration {
    val map = mutableMapOf<String, String>() // newConversationId -> userMessageId
}

data class BranchPrompt(
    val userMessageId: String,
    val newContent: String?
)

data class DeletedTurn(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage?
)

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> =
        container.conversationRepository.observeMessages(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ToolCallRequest?>(null)
    val pendingApproval: StateFlow<ToolCallRequest?> = _pendingApproval.asStateFlow()

    private val _branchPrompt = MutableStateFlow<BranchPrompt?>(null)
    val branchPrompt: StateFlow<BranchPrompt?> = _branchPrompt.asStateFlow()

    private val _deletedTurn = MutableStateFlow<DeletedTurn?>(null)
    val deletedTurn: StateFlow<DeletedTurn?> = _deletedTurn.asStateFlow()

    private val _navigateTo = MutableStateFlow<String?>(null)
    val navigateTo: StateFlow<String?> = _navigateTo.asStateFlow()

    private val _workspaceAvailable = MutableStateFlow(
        container.workspaceManager.hasWorkspace() &&
            container.workspaceManager.permissionStillValid()
    )
    val workspaceAvailable: StateFlow<Boolean> = _workspaceAvailable.asStateFlow()

    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var stopRequested = false

    init {
        viewModelScope.launch {
            container.conversationRepository.observeConversations().collect { list ->
                _conversation.value = list.firstOrNull { it.id == conversationId }
            }
        }
        viewModelScope.launch {
            val pendingUserId = PendingRegeneration.map.remove(conversationId)
            if (pendingUserId != null) {
                val userMessage = container.conversationRepository.getMessages(conversationId)
                    .firstOrNull { it.id == pendingUserId && it.role == Role.USER }
                if (userMessage != null) {
                    runGeneration(userMessage)
                }
            }
        }
    }

    fun refreshWorkspace() {
        _workspaceAvailable.value =
            container.workspaceManager.hasWorkspace() &&
                container.workspaceManager.permissionStillValid()
    }

    fun consumeNavigation() {
        _navigateTo.value = null
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) {
        if (_isBusy.value) return
        if (text.isBlank() && attachments.isEmpty()) return
        viewModelScope.launch {
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.USER,
                content = text.trim(),
                attachments = attachments
            )
            container.conversationRepository.addMessage(userMessage)
            maybeAutoTitle(text)
            runGeneration(userMessage)
        }
    }

    /** Stops the current generation (spec §3, §9, §15). The engine checks [isStopped]. */
    fun stopGeneration() {
        stopRequested = true
    }

    private suspend fun maybeAutoTitle(firstText: String) {
        val conv = container.conversationRepository.getConversation(conversationId)
        if (conv != null && conv.title == "Cuộc trò chuyện mới") {
            val title = firstText.trim().replace(Regex("\\s+"), " ").take(50)
            if (title.isNotBlank()) {
                container.conversationRepository.renameConversation(conversationId, title)
            }
        }
    }

    /**
     * Runs one generation for [userMessage] and persists the assistant reply.
     *
     * Uses the engine contract:
     * `chatEngine.generate(provider, modelId, systemPrompt, history,
     * workspaceAvailable, onDelta, onToolCall, requestApproval, isStopped,
     * maxToolRounds = 6)` returning `GenerateResult(text, toolCalls, error)`.
     */
    private suspend fun runGeneration(userMessage: ChatMessage) {
        if (_isBusy.value) return
        _isBusy.value = true
        _streamingText.value = ""
        stopRequested = false
        val collectedToolCalls = mutableListOf<ToolCallRecord>()
        var accumulated = StringBuilder()
        try {
            val conv = container.conversationRepository.getConversation(conversationId)
            val settings = container.settingsRepository.observeSettings().first()
            val providers = container.settingsRepository.observeProviders().first()
            val provider = providers.firstOrNull { it.id == conv?.providerId }
                ?: providers.firstOrNull { it.id == settings.defaultProviderId }
                ?: providers.firstOrNull()

            if (provider == null || provider.apiKey.isBlank()) {
                saveAssistantMessage(
                    content = "",
                    error = "Chưa nhập API key cho nhà cung cấp. Vào Cài đặt để thêm API key rồi thử lại.",
                    toolCalls = emptyList()
                )
                return
            }
            val modelId = conv?.modelId?.ifBlank { null } ?: provider.modelId
            val systemPrompt = conv?.systemPrompt?.ifBlank { null }
                ?: settings.defaultSystemPrompt
            val history = container.conversationRepository.getMessages(conversationId)

            val result = container.chatEngine.generate(
                provider = provider,
                modelId = modelId,
                systemPrompt = systemPrompt,
                history = history,
                workspaceAvailable = _workspaceAvailable.value,
                onDelta = { delta ->
                    accumulated.append(delta)
                    _streamingText.value = accumulated.toString()
                },
                onToolCall = { record -> collectedToolCalls.add(record) },
                requestApproval = { request -> requestApproval(request) },
                isStopped = { stopRequested },
                maxToolRounds = 6
            )
            val finalText = result.text.ifBlank { accumulated.toString() }
            val finalToolCalls = result.toolCalls.ifEmpty { collectedToolCalls }
            if (finalText.isBlank() && result.error != null) {
                saveAssistantMessage("", result.error, finalToolCalls)
            } else {
                saveAssistantMessage(finalText, result.error, finalToolCalls)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val partial = accumulated.toString()
            if (partial.isNotBlank()) {
                saveAssistantMessage(partial, "Đã dừng: ${e.message}", collectedToolCalls)
            } else {
                saveAssistantMessage("", e.message ?: "Đã xảy ra lỗi.", collectedToolCalls)
            }
        } finally {
            _streamingText.value = ""
            _isBusy.value = false
            stopRequested = false
        }
    }

    private suspend fun saveAssistantMessage(
        content: String,
        error: String?,
        toolCalls: List<ToolCallRecord>
    ) {
        container.conversationRepository.addMessage(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = Role.ASSISTANT,
                content = content,
                error = error,
                toolCalls = toolCalls
            )
        )
    }

    // ------------------------------------------------------------------
    // Tool approval (spec §15): suspends the engine until the user decides.
    // ------------------------------------------------------------------

    private suspend fun requestApproval(request: ToolCallRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _pendingApproval.value = request
        return try {
            deferred.await()
        } finally {
            approvalDeferred = null
            _pendingApproval.value = null
        }
    }

    fun approvePending() {
        approvalDeferred?.complete(true)
    }

    fun declinePending() {
        approvalDeferred?.complete(false)
    }

    // ------------------------------------------------------------------
    // Message actions (spec §6-§9)
    // ------------------------------------------------------------------

    /** Edit: save only (spec §6). */
    fun saveEdit(message: ChatMessage, newContent: String) {
        if (_isBusy.value) return
        viewModelScope.launch {
            container.conversationRepository.updateMessage(
                message.copy(content = newContent, edited = true)
            )
        }
    }

    /** Edit & regenerate (spec §6, §8). */
    fun saveEditAndRegenerate(message: ChatMessage, newContent: String) {
        if (_isBusy.value) return
        if (message.role != Role.USER) {
            saveEdit(message, newContent)
            return
        }
        val isLast = isLastTurn(message.id)
        if (isLast) {
            viewModelScope.launch { regenerateInPlace(message.id, newContent) }
        } else {
            _branchPrompt.value = BranchPrompt(message.id, newContent)
        }
    }

    /** Regenerate with the same content (spec §6, §8). */
    fun regenerate(message: ChatMessage) {
        if (_isBusy.value) return
        if (message.role != Role.USER) return
        if (isLastTurn(message.id)) {
            viewModelScope.launch { regenerateInPlace(message.id, null) }
        } else {
            _branchPrompt.value = BranchPrompt(message.id, null)
        }
    }

    private fun isLastTurn(userMessageId: String): Boolean {
        val list = messages.value
        val index = list.indexOfFirst { it.id == userMessageId }
        if (index == -1) return true
        return list.subList(index + 1, list.size).none { it.role == Role.USER }
    }

    /** "Không" in the branch dialog: delete later turns, regenerate in place (§8). */
    fun regenerateInPlaceConfirmed() {
        val prompt = _branchPrompt.value ?: return
        _branchPrompt.value = null
        viewModelScope.launch { regenerateInPlace(prompt.userMessageId, prompt.newContent) }
    }

    /** "Có" in the branch dialog: branch, then regenerate in the new chat (§8). */
    fun branchAndRegenerateConfirmed() {
        val prompt = _branchPrompt.value ?: return
        _branchPrompt.value = null
        viewModelScope.launch {
            val newConv = container.conversationRepository.branchConversation(
                sourceId = conversationId,
                upToMessageId = prompt.userMessageId,
                newTitle = (conversation.value?.title ?: "Cuộc trò chuyện mới") + " (nhánh)"
            )
            val newMessages = container.conversationRepository.getMessages(newConv.id)
            val lastUser = newMessages.lastOrNull { it.role == Role.USER }
            if (lastUser != null) {
                if (prompt.newContent != null) {
                    container.conversationRepository.updateMessage(
                        lastUser.copy(content = prompt.newContent, edited = true)
                    )
                }
                PendingRegeneration.map[newConv.id] = lastUser.id
            }
            _navigateTo.value = newConv.id
        }
    }

    fun dismissBranchPrompt() {
        _branchPrompt.value = null
    }

    private suspend fun regenerateInPlace(userMessageId: String, newContent: String?) {
        if (newContent != null) {
            val userMessage = container.conversationRepository.getMessages(conversationId)
                .firstOrNull { it.id == userMessageId }
            if (userMessage != null) {
                container.conversationRepository.updateMessage(
                    userMessage.copy(content = newContent, edited = true)
                )
            }
        }
        container.conversationRepository.deleteMessagesAfter(conversationId, userMessageId)
        val userMessage = container.conversationRepository.getMessages(conversationId)
            .firstOrNull { it.id == userMessageId } ?: return
        runGeneration(userMessage)
    }

    /** Branch in new chat (spec §4, §7): copy up to the selected message, open it. */
    fun branchFromMessage(message: ChatMessage) {
        if (_isBusy.value) return
        viewModelScope.launch {
            val newConv = container.conversationRepository.branchConversation(
                sourceId = conversationId,
                upToMessageId = message.id,
                newTitle = (conversation.value?.title ?: "Cuộc trò chuyện mới") + " (nhánh)"
            )
            _navigateTo.value = newConv.id
        }
    }

    /** Delete a whole turn (user + assistant pair) with undo (spec §6, §7, §9). */
    fun deleteTurn(message: ChatMessage) {
        if (_isBusy.value) return
        viewModelScope.launch {
            val list = container.conversationRepository.getMessages(conversationId)
            val userMessage: ChatMessage?
            val assistantMessage: ChatMessage?
            if (message.role == Role.USER) {
                userMessage = message
                val index = list.indexOfFirst { it.id == message.id }
                assistantMessage = list.getOrNull(index + 1)
                    ?.takeIf { it.role == Role.ASSISTANT }
            } else {
                val index = list.indexOfFirst { it.id == message.id }
                userMessage = list.subList(0, index).lastOrNull { it.role == Role.USER }
                assistantMessage = message
            }
            if (userMessage == null) return@launch
            _deletedTurn.value = DeletedTurn(userMessage, assistantMessage)
            container.conversationRepository.deleteTurn(conversationId, userMessage.id)
            // Undo window: a few seconds (spec §9).
            delay(5000)
            if (_deletedTurn.value?.userMessage?.id == userMessage.id) {
                _deletedTurn.value = null
            }
        }
    }

    fun undoDeleteTurn() {
        val deleted = _deletedTurn.value ?: return
        _deletedTurn.value = null
        viewModelScope.launch {
            container.conversationRepository.addMessage(deleted.userMessage)
            deleted.assistantMessage?.let {
                container.conversationRepository.addMessage(it)
            }
        }
    }

    fun clearDeletedTurn() {
        _deletedTurn.value = null
    }
}
