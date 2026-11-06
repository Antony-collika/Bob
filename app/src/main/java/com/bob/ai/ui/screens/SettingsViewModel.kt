package com.bob.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.AppSettings
import com.bob.ai.domain.ProviderConfig
import com.bob.ai.domain.ProviderType
import com.bob.ai.domain.modelsFor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        container.settingsRepository.observeSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val providers: StateFlow<List<ProviderConfig>> =
        container.settingsRepository.observeProviders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun configFor(type: ProviderType): ProviderConfig? =
        providers.value.firstOrNull { it.type == type }

    private fun ensureConfig(type: ProviderType): ProviderConfig {
        return configFor(type) ?: ProviderConfig(
            id = type.name.lowercase(),
            type = type,
            name = type.displayName(),
            modelId = modelsFor(type).first().id
        )
    }

    fun setApiKey(type: ProviderType, apiKey: String) {
        viewModelScope.launch {
            val current = ensureConfig(type)
            container.settingsRepository.saveProvider(current.copy(apiKey = apiKey))
        }
    }

    fun setBaseUrl(type: ProviderType, baseUrl: String) {
        viewModelScope.launch {
            val current = ensureConfig(type)
            container.settingsRepository.saveProvider(
                current.copy(baseUrl = baseUrl.ifBlank { null })
            )
        }
    }

    fun setModelId(type: ProviderType, modelId: String) {
        viewModelScope.launch {
            val current = ensureConfig(type)
            container.settingsRepository.saveProvider(current.copy(modelId = modelId))
        }
    }

    fun setDefaultProvider(providerId: String) {
        viewModelScope.launch {
            container.settingsRepository.saveSettings(
                settings.value.copy(defaultProviderId = providerId)
            )
        }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch {
            container.settingsRepository.saveSettings(
                settings.value.copy(defaultSystemPrompt = prompt)
            )
        }
    }

    fun setTheme(darkTheme: Boolean?) {
        viewModelScope.launch {
            container.settingsRepository.saveSettings(
                settings.value.copy(darkTheme = darkTheme)
            )
        }
    }

    fun setSearchApiKey(apiKey: String) {
        viewModelScope.launch {
            container.settingsRepository.saveSettings(
                settings.value.copy(searchApiKey = apiKey)
            )
        }
    }

    fun setWorkspace(uriString: String, displayName: String?) {
        viewModelScope.launch {
            container.workspaceManager.setWorkspace(uriString, displayName)
        }
    }

    fun clearWorkspace() {
        viewModelScope.launch {
            container.workspaceManager.clearWorkspace()
        }
    }

    fun workspaceDisplayName(): String? = container.workspaceManager.workspaceDisplayName()
    fun hasWorkspace(): Boolean = container.workspaceManager.hasWorkspace()
}

fun ProviderType.displayName(): String = when (this) {
    ProviderType.OPENAI -> "OpenAI"
    ProviderType.CLAUDE -> "Claude"
    ProviderType.GEMINI -> "Gemini"
    ProviderType.CUSTOM -> "Tùy chỉnh (OpenAI-compatible)"
}

fun ProviderType.helpText(): String = when (this) {
    ProviderType.CLAUDE ->
        "Lấy API key tại console.anthropic.com → API Keys. Key bắt đầu bằng sk-ant-."
    ProviderType.OPENAI ->
        "Lấy API key tại platform.openai.com/api-keys. Key bắt đầu bằng sk-."
    ProviderType.GEMINI ->
        "Lấy API key miễn phí tại aistudio.google.com/app/apikey."
    ProviderType.CUSTOM ->
        "Nhập base URL của máy chủ tương thích OpenAI (ví dụ: https://api.example.com/v1) và API key của máy chủ đó."
}
