package com.bob.ai.data.providers

import android.content.Context
import com.bob.ai.domain.AiProvider
import com.bob.ai.domain.ProviderConfig
import com.bob.ai.domain.ProviderFactory
import com.bob.ai.domain.ProviderType

/**
 * Tạo provider theo [ProviderConfig.type]. CUSTOM dùng chung giao thức
 * OpenAI (OpenAiProvider tự lấy baseUrl hiệu dụng từ config, xem
 * [ProviderConfig.effectiveBaseUrl]).
 */
class ProviderFactoryImpl(private val context: Context) : ProviderFactory {

    private val appContext = context.applicationContext

    override fun create(config: ProviderConfig): AiProvider = when (config.type) {
        ProviderType.OPENAI, ProviderType.CUSTOM -> OpenAiProvider(appContext)
        ProviderType.CLAUDE -> ClaudeProvider(appContext)
        ProviderType.GEMINI -> GeminiProvider(appContext)
    }
}
