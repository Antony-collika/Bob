package com.bob.ai.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

// ---------------------------------------------------------------------------
// Chat domain
// ---------------------------------------------------------------------------

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

enum class AttachmentType { IMAGE }

data class Attachment(
    val id: String,
    val type: AttachmentType = AttachmentType.IMAGE,
    val uri: String,
    val mimeType: String? = null,
    val fileName: String? = null
)

data class ToolCallRecord(
    val id: String,
    val uid: String,
    val name: String,
    val argumentsJson: String,
    val result: String,
    val success: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val edited: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val toolCalls: List<ToolCallRecord> = emptyList()
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val providerId: String = "",
    val modelId: String = "",
    val systemPrompt: String = ""
)

data class SearchHit(
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    val snippet: String,
    val role: Role
)

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

enum class ProviderType { OPENAI, CLAUDE, GEMINI, CUSTOM }

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val maxOutputTokens: Int
)

fun modelsFor(type: ProviderType): List<ModelInfo> = when (type) {
    ProviderType.OPENAI -> listOf(
        ModelInfo("gpt-4o", "GPT-4o", 128_000, 16_384),
        ModelInfo("gpt-4o-mini", "GPT-4o mini", 128_000, 16_384),
        ModelInfo("gpt-4-turbo", "GPT-4 Turbo", 128_000, 4_096),
        ModelInfo("o1-mini", "o1-mini", 128_000, 65_536)
    )
    ProviderType.CLAUDE -> listOf(
        ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", 200_000, 8_192),
        ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200_000, 8_192),
        ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200_000, 8_192),
        ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", 200_000, 4_096)
    )
    ProviderType.GEMINI -> listOf(
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", 1_000_000, 8_192),
        ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", 2_000_000, 8_192),
        ModelInfo("gemini-1.5-flash", "Gemini 1.5 Flash", 1_000_000, 8_192)
    )
    ProviderType.CUSTOM -> listOf(
        ModelInfo("custom", "Mô hình tùy chỉnh", 128_000, 8_192)
    )
}

data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val apiKey: String = "",
    val baseUrl: String? = null,
    val modelId: String,
    val enabled: Boolean = true
) {
    fun defaultBaseUrl(): String = when (type) {
        ProviderType.OPENAI -> "https://api.openai.com/v1"
        ProviderType.CLAUDE -> "https://api.anthropic.com"
        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderType.CUSTOM -> baseUrl?.trimEnd('/') ?: ""
    }
    fun effectiveBaseUrl(): String = if (type == ProviderType.CUSTOM) {
        baseUrl?.trimEnd('/')?.ifBlank { null } ?: ""
    } else defaultBaseUrl()
}

data class AppSettings(
    val darkTheme: Boolean? = null, // null = theo hệ thống
    val searchApiKey: String = "",
    val defaultProviderId: String = "",
    val defaultSystemPrompt: String = ""
)

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

enum class ToolCategory { FILE, TEXT, DAILY }

enum class ToolControl { AUTO, ASK, OFF }

data class ToolDefinition(
    val uid: String,
    val name: String,
    val description: String,
    val category: ToolCategory,
    val isReadOnly: Boolean,
    val defaultControl: ToolControl,
    val parametersJson: String,
    val requiresWorkspace: Boolean
)

data class ToolSetting(
    val uid: String,
    val enabled: Boolean = true,
    val customName: String? = null,
    val customDescription: String? = null,
    val control: ToolControl
) {
    fun effectiveName(def: ToolDefinition): String = customName?.ifBlank { null } ?: def.name
    fun effectiveDescription(def: ToolDefinition): String =
        customDescription?.ifBlank { null } ?: def.description
}

data class ToolCallRequest(
    val uid: String,
    val nameUsed: String,
    val argumentsJson: String
)

data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)

/** Fixed UIDs for every tool. The UID never changes; only the name/description
 *  shown to the model can be renamed. History is stored by UID. */
object ToolUids {
    // File / directory tools (§11)
    const val FILE_LIST_DIR = "file.list_dir"
    const val FILE_TREE = "file.tree"
    const val FILE_INFO = "file.info"
    const val FILE_MKDIR = "file.mkdir"
    const val FILE_COPY = "file.copy"
    const val FILE_MOVE = "file.move"
    const val FILE_RENAME = "file.rename"
    const val FILE_DELETE = "file.delete"
    const val FILE_SEARCH_NAME = "file.search_name"
    const val FILE_SEARCH_CONTENT = "file.search_content"
    const val FILE_ZIP = "file.zip"
    const val FILE_UNZIP = "file.unzip"
    // Text file tools (§12)
    const val TEXT_READ = "text.read"
    const val TEXT_READ_RANGE = "text.read_range"
    const val TEXT_READ_MULTI = "text.read_multi"
    const val TEXT_WRITE_NEW = "text.write_new"
    const val TEXT_WRITE_OVERWRITE = "text.write_overwrite"
    const val TEXT_APPEND = "text.append"
    const val TEXT_INSERT_LINE = "text.insert_line"
    const val TEXT_DELETE_LINES = "text.delete_lines"
    const val TEXT_REPLACE_EXACT = "text.replace_exact"
    const val TEXT_FIND_REPLACE = "text.find_replace"
    const val TEXT_DIFF = "text.diff"
    const val TEXT_COUNT = "text.count"
    const val CSV_READ = "csv.read"
    const val CSV_FILTER = "csv.filter"
    const val CSV_SORT = "csv.sort"
    const val CSV_STATS = "csv.stats"
    const val CSV_ADD_ROW = "csv.add_row"
    const val CSV_EDIT_CELL = "csv.edit_cell"
    const val JSON_VALIDATE = "json.validate"
    const val JSON_PRETTY = "json.pretty"
    const val JSON_GET = "json.get"
    const val JSON_SET = "json.set"
    const val CONVERT_CSV_TO_JSON = "convert.csv_to_json"
    const val CONVERT_JSON_TO_CSV = "convert.json_to_csv"
    const val CONVERT_MD_TO_HTML = "convert.md_to_html"
    // Daily tools (§13)
    const val DAILY_CALC = "daily.calc"
    const val DAILY_NOW = "daily.now"
    const val DAILY_DAYS_BETWEEN = "daily.days_between"
    const val DAILY_FETCH_URL = "daily.fetch_url"
    const val DAILY_WEB_SEARCH = "daily.web_search"
    const val DAILY_NOTE_ADD = "daily.note_add"
    const val DAILY_NOTE_LIST = "daily.note_list"
    const val DAILY_TODO_ADD = "daily.todo_add"
    const val DAILY_TODO_LIST = "daily.todo_list"
    const val DAILY_TODO_DONE = "daily.todo_done"
    const val DAILY_ALARM = "daily.alarm"
    const val DAILY_CALENDAR_EVENT = "daily.calendar_event"
    const val DAILY_CLIPBOARD_WRITE = "daily.clipboard_write"
}

// ---------------------------------------------------------------------------
// Repository / service contracts (implemented by data layer)
// ---------------------------------------------------------------------------

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getConversation(id: String): Conversation?
    suspend fun getMessages(id: String): List<ChatMessage>
    suspend fun createConversation(title: String, providerId: String, modelId: String, systemPrompt: String): Conversation
    suspend fun renameConversation(id: String, title: String)
    suspend fun deleteConversation(id: String)
    suspend fun addMessage(message: ChatMessage)
    suspend fun updateMessage(message: ChatMessage)
    suspend fun deleteMessage(id: String)
    /** Delete a whole turn: the user message and the assistant reply that follows it. */
    suspend fun deleteTurn(conversationId: String, userMessageId: String)
    /** Delete every message after (and not including) the given message id. */
    suspend fun deleteMessagesAfter(conversationId: String, messageId: String)
    /** Copy from the start up to and including [upToMessageId] into a new conversation. */
    suspend fun branchConversation(sourceId: String, upToMessageId: String, newTitle: String): Conversation
    suspend fun searchAll(query: String): List<SearchHit>
    suspend fun searchInConversation(conversationId: String, query: String): List<ChatMessage>
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun saveSettings(settings: AppSettings)
    fun observeProviders(): Flow<List<ProviderConfig>>
    suspend fun saveProvider(config: ProviderConfig)
    suspend fun deleteProvider(id: String)
    suspend fun getProvider(id: String): ProviderConfig?
}

interface ToolRegistry {
    fun definitions(): List<ToolDefinition>
    fun definition(uid: String): ToolDefinition?
    fun observeSettings(): Flow<Map<String, ToolSetting>>
    suspend fun getSetting(uid: String): ToolSetting
    suspend fun saveSetting(setting: ToolSetting)
    suspend fun resetSetting(uid: String)
    /** Tools that may be sent to the model in the next request (enabled, control != OFF,
     *  file tools excluded when [workspaceAvailable] is false). */
    fun effectiveDefinitions(workspaceAvailable: Boolean): List<ToolDefinition>
    /** Map a (possibly renamed) tool name used by the model back to its fixed UID. */
    fun resolveUidByName(name: String): String?
    /** Validate a renamed tool name. Returns an error message in Vietnamese, or null if OK. */
    fun validateName(uid: String, newName: String): String?
}

interface ToolExecutor {
    suspend fun execute(uid: String, argumentsJson: String): ToolExecutionResult
}

interface WorkspaceManager {
    fun hasWorkspace(): Boolean
    fun workspaceUriString(): String?
    fun workspaceDisplayName(): String?
    suspend fun setWorkspace(uriString: String, displayName: String?)
    suspend fun clearWorkspace()
    /** True when the persisted SAF permission can still be used. */
    fun permissionStillValid(): Boolean
}

// ---------------------------------------------------------------------------
// Chat engine contracts
// ---------------------------------------------------------------------------

data class ChatRequest(
    val provider: ProviderConfig,
    val modelId: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>
)

sealed class StreamChunk {
    data class TextDelta(val text: String) : StreamChunk()
    data class ToolCall(val request: ToolCallRequest) : StreamChunk()
    object Done : StreamChunk()
    data class Error(val message: String) : StreamChunk()
}

interface AiProvider {
    val type: ProviderType
    suspend fun streamChat(request: ChatRequest): Flow<StreamChunk>
}

interface ProviderFactory {
    fun create(config: ProviderConfig): AiProvider
}

fun requireArgs(args: JsonObject, vararg keys: String): String? {
    for (key in keys) {
        val v = args[key] ?: return "Thiếu tham số bắt buộc: $key"
        if (v.toString().trim().trim('"').isEmpty()) return "Tham số $key đang trống"
    }
    return null
}

fun JsonObject.string(key: String, default: String = ""): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: default

fun JsonObject.int(key: String, default: Int = 0): Int =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: default

fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
