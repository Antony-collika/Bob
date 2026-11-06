package com.bob.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.AppSettings
import com.bob.ai.domain.Conversation
import com.bob.ai.domain.ProviderConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel(private val container: AppContainer) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        container.conversationRepository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> =
        container.settingsRepository.observeSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val providers: StateFlow<List<ProviderConfig>> =
        container.settingsRepository.observeProviders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Creates a conversation with the default provider (if any) and reports its id. */
    fun createNewConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val settingsValue = settings.value
            val provider = providers.value.firstOrNull { it.id == settingsValue.defaultProviderId }
                ?: providers.value.firstOrNull()
            val conversation = container.conversationRepository.createConversation(
                title = "Cuộc trò chuyện mới",
                providerId = provider?.id ?: "",
                modelId = provider?.modelId ?: "",
                systemPrompt = settingsValue.defaultSystemPrompt
            )
            onCreated(conversation.id)
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            if (title.isNotBlank()) {
                container.conversationRepository.renameConversation(id, title.trim())
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            container.conversationRepository.deleteConversation(id)
        }
    }
}
