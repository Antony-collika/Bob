package com.bob.ai.di

import android.content.Context
import com.bob.ai.data.ConversationRepositoryImpl
import com.bob.ai.data.SettingsRepositoryImpl
import com.bob.ai.data.local.AppDatabase
import com.bob.ai.data.providers.ProviderFactoryImpl
import com.bob.ai.data.tools.BobToolExecutor
import com.bob.ai.data.tools.DailyToolsExecutor
import com.bob.ai.data.tools.FileToolsExecutor
import com.bob.ai.data.tools.TextToolsExecutor
import com.bob.ai.data.tools.ToolRegistryImpl
import com.bob.ai.data.workspace.SafWorkspaceManager
import com.bob.ai.domain.ChatEngine
import com.bob.ai.domain.ConversationRepository
import com.bob.ai.domain.ProviderFactory
import com.bob.ai.domain.SettingsRepository
import com.bob.ai.domain.ToolExecutor
import com.bob.ai.domain.ToolRegistry
import com.bob.ai.domain.WorkspaceManager

/**
 * Manual dependency container. Implementations are created by the data layer;
 * the class names below are the contract — do not rename them without updating
 * this file.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(appContext)
    }

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepositoryImpl(appContext, AppDatabase.get(appContext))
    }

    val workspaceManager: WorkspaceManager by lazy {
        SafWorkspaceManager(appContext)
    }

    private val fileToolsExecutor by lazy { FileToolsExecutor(workspaceManager) }
    private val textToolsExecutor by lazy { TextToolsExecutor(workspaceManager) }
    private val dailyToolsExecutor by lazy { DailyToolsExecutor(appContext, settingsRepository) }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistryImpl(appContext)
    }

    val toolExecutor: ToolExecutor by lazy {
        BobToolExecutor(fileToolsExecutor, textToolsExecutor, dailyToolsExecutor)
    }

    val providerFactory: ProviderFactory by lazy { ProviderFactoryImpl(appContext) }

    val chatEngine: ChatEngine by lazy {
        ChatEngine(providerFactory, toolRegistry, toolExecutor)
    }
}
