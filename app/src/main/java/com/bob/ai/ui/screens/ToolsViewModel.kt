package com.bob.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.ToolCategory
import com.bob.ai.domain.ToolControl
import com.bob.ai.domain.ToolDefinition
import com.bob.ai.domain.ToolSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ToolUiItem(
    val definition: ToolDefinition,
    val setting: ToolSetting
) {
    val effectiveName: String get() = setting.effectiveName(definition)
    val effectiveDescription: String get() = setting.effectiveDescription(definition)
}

fun ToolCategory.displayName(): String = when (this) {
    ToolCategory.FILE -> "File & thư mục"
    ToolCategory.TEXT -> "File văn bản"
    ToolCategory.DAILY -> "Công việc hằng ngày"
}

fun ToolControl.displayName(): String = when (this) {
    ToolControl.AUTO -> "Tự động"
    ToolControl.ASK -> "Hỏi trước khi làm"
    ToolControl.OFF -> "Tắt"
}

class ToolsViewModel(private val container: AppContainer) : ViewModel() {

    val tools: StateFlow<List<ToolUiItem>> =
        combine(
            container.toolRegistry.observeSettings(),
            kotlinx.coroutines.flow.flowOf(container.toolRegistry.definitions())
        ) { settings, definitions ->
            definitions.map { def ->
                ToolUiItem(def, settings[def.uid] ?: ToolSetting(def.uid, control = def.defaultControl))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedUid = MutableStateFlow<String?>(null)
    val selectedUid: StateFlow<String?> = _selectedUid.asStateFlow()

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    val selectedTool: StateFlow<ToolUiItem?> =
        combine(tools, _selectedUid) { list, uid ->
            list.firstOrNull { it.definition.uid == uid }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectTool(uid: String?) {
        _selectedUid.value = uid
        _nameError.value = null
    }

    fun setEnabled(uid: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = container.toolRegistry.getSetting(uid)
            container.toolRegistry.saveSetting(current.copy(enabled = enabled))
        }
    }

    fun setControl(uid: String, control: ToolControl) {
        viewModelScope.launch {
            val current = container.toolRegistry.getSetting(uid)
            container.toolRegistry.saveSetting(current.copy(control = control))
        }
    }

    /** Validates immediately via [com.bob.ai.domain.ToolRegistry.validateName] and saves only if valid. */
    fun setCustomName(uid: String, name: String) {
        val error = container.toolRegistry.validateName(uid, name)
        _nameError.value = error
        if (error != null) return
        viewModelScope.launch {
            val current = container.toolRegistry.getSetting(uid)
            container.toolRegistry.saveSetting(current.copy(customName = name.ifBlank { null }))
        }
    }

    fun setCustomDescription(uid: String, description: String) {
        viewModelScope.launch {
            val current = container.toolRegistry.getSetting(uid)
            container.toolRegistry.saveSetting(
                current.copy(customDescription = description.ifBlank { null })
            )
        }
    }

    fun resetToDefaults(uid: String) {
        viewModelScope.launch {
            container.toolRegistry.resetSetting(uid)
            _nameError.value = null
        }
    }
}
