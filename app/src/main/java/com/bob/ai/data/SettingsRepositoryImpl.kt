package com.bob.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bob.ai.domain.AppSettings
import com.bob.ai.domain.ProviderConfig
import com.bob.ai.domain.ProviderType
import com.bob.ai.domain.SettingsRepository
import com.bob.ai.domain.modelsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bob_settings"
)

/**
 * Settings + provider storage (§2 of the spec).
 *
 * - [AppSettings] live in DataStore Preferences ("bob_settings").
 * - Provider configs (without apiKey) live in the same DataStore as a JSON
 *   string; each provider's apiKey lives in EncryptedSharedPreferences
 *   (file "bob_keys", MasterKey AES256_GCM), so keys are never stored in
 *   plaintext.
 * - Default providers are seeded on first run when no providers exist.
 */
class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {

    private val json = Json { ignoreUnknownKeys = true }

    private val dataStore: DataStore<Preferences> get() = context.settingsDataStore

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bob_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun apiKeyPrefKey(providerId: String): String = "key_$providerId"

    // -- Serializable stand-ins (ProviderConfig itself is not @Serializable,
    //    and ProviderType is not either, so the apiKey-free form is stored
    //    with the type as its enum name) -----------------------------------

    @Serializable
    private data class StoredProvider(
        val id: String,
        val type: String,
        val name: String,
        val baseUrl: String? = null,
        val modelId: String,
        val enabled: Boolean = true
    )

    private fun ProviderConfig.toStored(): StoredProvider = StoredProvider(
        id = id,
        type = type.name,
        name = name,
        baseUrl = baseUrl,
        modelId = modelId,
        enabled = enabled
    )

    private fun StoredProvider.toConfig(): ProviderConfig {
        val type = runCatching { ProviderType.valueOf(type) }
            .getOrElse { ProviderType.CUSTOM }
        return ProviderConfig(
            id = id,
            type = type,
            name = name,
            apiKey = encryptedPrefs.getString(apiKeyPrefKey(id), "") ?: "",
            baseUrl = baseUrl,
            modelId = modelId,
            enabled = enabled
        )
    }

    private fun decodeProviders(raw: String?): List<StoredProvider> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<StoredProvider>>(raw) }
            .getOrElse { emptyList() }
    }

    private fun defaultProviders(): List<StoredProvider> {
        fun one(id: String, type: ProviderType, name: String, baseUrl: String? = null) =
            StoredProvider(
                id = id,
                type = type.name,
                name = name,
                baseUrl = baseUrl,
                modelId = modelsFor(type).first().id,
                enabled = true
            )
        return listOf(
            one("claude", ProviderType.CLAUDE, "Claude"),
            one("openai", ProviderType.OPENAI, "OpenAI"),
            one("gemini", ProviderType.GEMINI, "Gemini"),
            // Custom provider: OpenAI-compatible, empty baseUrl (§2).
            one("custom", ProviderType.CUSTOM, "Custom", baseUrl = "")
        )
    }

    /** Seed the four default providers (with empty apiKeys) if none exist. */
    private suspend fun ensureSeeded() {
        val prefs = dataStore.data.first()
        if (decodeProviders(prefs[Keys.PROVIDERS_JSON]).isEmpty()) {
            val defaults = defaultProviders()
            dataStore.edit { it[Keys.PROVIDERS_JSON] = json.encodeToString(defaults) }
        }
    }

    private suspend fun storedProviders(): List<StoredProvider> {
        ensureSeeded()
        return decodeProviders(dataStore.data.first()[Keys.PROVIDERS_JSON])
    }

    // -- AppSettings ---------------------------------------------------------

    override fun observeSettings(): Flow<AppSettings> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val mode = prefs[Keys.DARK_THEME_MODE] ?: "system"
                AppSettings(
                    darkTheme = when (mode) {
                        "dark" -> true
                        "light" -> false
                        else -> null // "system" (or anything unexpected) = follow system
                    },
                    searchApiKey = prefs[Keys.SEARCH_API_KEY] ?: "",
                    defaultProviderId = prefs[Keys.DEFAULT_PROVIDER_ID] ?: "",
                    defaultSystemPrompt = prefs[Keys.DEFAULT_SYSTEM_PROMPT] ?: ""
                )
            }

    override suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_THEME_MODE] = when (settings.darkTheme) {
                true -> "dark"
                false -> "light"
                null -> "system"
            }
            prefs[Keys.SEARCH_API_KEY] = settings.searchApiKey
            prefs[Keys.DEFAULT_PROVIDER_ID] = settings.defaultProviderId
            prefs[Keys.DEFAULT_SYSTEM_PROMPT] = settings.defaultSystemPrompt
        }
    }

    // -- Providers -----------------------------------------------------------

    override fun observeProviders(): Flow<List<ProviderConfig>> = flow {
        ensureSeeded()
        emitAll(
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { prefs ->
                    decodeProviders(prefs[Keys.PROVIDERS_JSON]).map { it.toConfig() }
                }
        )
    }

    override suspend fun saveProvider(config: ProviderConfig) {
        val current = storedProviders().toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) current[index] = config.toStored() else current += config.toStored()
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDERS_JSON] = json.encodeToString(current)
        }
        // API key goes only to the encrypted store, never to DataStore.
        encryptedPrefs.edit().apply {
            if (config.apiKey.isBlank()) remove(apiKeyPrefKey(config.id))
            else putString(apiKeyPrefKey(config.id), config.apiKey)
        }.apply()
    }

    override suspend fun deleteProvider(id: String) {
        val remaining = storedProviders().filter { it.id != id }
        dataStore.edit { prefs ->
            prefs[Keys.PROVIDERS_JSON] = json.encodeToString(remaining)
        }
        encryptedPrefs.edit().remove(apiKeyPrefKey(id)).apply()
    }

    override suspend fun getProvider(id: String): ProviderConfig? =
        storedProviders().firstOrNull { it.id == id }?.toConfig()

    private object Keys {
        val DARK_THEME_MODE = stringPreferencesKey("dark_theme_mode")
        val SEARCH_API_KEY = stringPreferencesKey("search_api_key")
        val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        val DEFAULT_SYSTEM_PROMPT = stringPreferencesKey("default_system_prompt")
        val PROVIDERS_JSON = stringPreferencesKey("providers_json")
    }
}
