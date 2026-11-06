package com.bob.ai.data.tools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bob.ai.domain.ToolCategory
import com.bob.ai.domain.ToolControl
import com.bob.ai.domain.ToolDefinition
import com.bob.ai.domain.ToolRegistry
import com.bob.ai.domain.ToolSetting
import com.bob.ai.domain.ToolUids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.toolsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bob_tools"
)

/**
 * Registry of all 50 tools (§11, §12, §13) with per-UID settings (§14, §15).
 *
 * The UID is fixed; only the name/description shown to the model can be
 * renamed. Settings are persisted in DataStore Preferences ("bob_tools") as
 * JSON. The synchronous methods ([effectiveDefinitions], [resolveUidByName],
 * [validateName]) read from an in-memory cache that starts at the defaults
 * and is kept up to date by a background collector over the DataStore flow,
 * so they never block.
 */
class ToolRegistryImpl(private val context: Context) : ToolRegistry {

    private val json = Json { ignoreUnknownKeys = true }
    private val dataStore: DataStore<Preferences> get() = context.toolsDataStore

    @Serializable
    private data class StoredSetting(
        val uid: String,
        val enabled: Boolean = true,
        val customName: String? = null,
        val customDescription: String? = null,
        val control: String = ToolControl.AUTO.name
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** uid -> stored override. Starts empty (= all defaults) until loaded. */
    @Volatile
    private var storedCache: Map<String, StoredSetting> = emptyMap()

    init {
        scope.launch {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .collect { prefs -> storedCache = decodeSettings(prefs[Keys.SETTINGS_JSON]) }
        }
    }

    private fun decodeSettings(raw: String?): Map<String, StoredSetting> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<StoredSetting>>(raw).associateBy { it.uid }
        }.getOrElse { emptyMap() }
    }

    private fun defaultSetting(def: ToolDefinition): ToolSetting =
        ToolSetting(uid = def.uid, enabled = true, control = def.defaultControl)

    private fun settingFor(def: ToolDefinition): ToolSetting {
        val stored = storedCache[def.uid] ?: return defaultSetting(def)
        val control = runCatching { ToolControl.valueOf(stored.control) }
            .getOrElse { def.defaultControl }
        return ToolSetting(
            uid = def.uid,
            enabled = stored.enabled,
            customName = stored.customName,
            customDescription = stored.customDescription,
            control = control
        )
    }

    private fun ToolSetting.toStored(): StoredSetting = StoredSetting(
        uid = uid,
        enabled = enabled,
        customName = customName,
        customDescription = customDescription,
        control = control.name
    )

    // -- Definitions ---------------------------------------------------------

    override fun definitions(): List<ToolDefinition> = ALL_DEFINITIONS

    override fun definition(uid: String): ToolDefinition? =
        ALL_DEFINITIONS.firstOrNull { it.uid == uid }

    // -- Settings ------------------------------------------------------------

    override fun observeSettings(): Flow<Map<String, ToolSetting>> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val stored = decodeSettings(prefs[Keys.SETTINGS_JSON])
                ALL_DEFINITIONS.associate { def ->
                    val s = stored[def.uid]
                    val control = s?.let {
                        runCatching { ToolControl.valueOf(it.control) }
                            .getOrElse { def.defaultControl }
                    } ?: def.defaultControl
                    def.uid to ToolSetting(
                        uid = def.uid,
                        enabled = s?.enabled ?: true,
                        customName = s?.customName,
                        customDescription = s?.customDescription,
                        control = control
                    )
                }
            }

    override suspend fun getSetting(uid: String): ToolSetting {
        val def = definition(uid) ?: return ToolSetting(uid = uid, control = ToolControl.AUTO)
        return settingFor(def)
    }

    override suspend fun saveSetting(setting: ToolSetting) {
        dataStore.edit { prefs ->
            val current = decodeSettings(prefs[Keys.SETTINGS_JSON]).toMutableMap()
            current[setting.uid] = setting.toStored()
            prefs[Keys.SETTINGS_JSON] = json.encodeToString(current.values.toList())
        }
        storedCache = storedCache + (setting.uid to setting.toStored())
    }

    override suspend fun resetSetting(uid: String) {
        dataStore.edit { prefs ->
            val current = decodeSettings(prefs[Keys.SETTINGS_JSON]).toMutableMap()
            current.remove(uid)
            prefs[Keys.SETTINGS_JSON] = json.encodeToString(current.values.toList())
        }
        storedCache = storedCache - uid
    }

    // -- Effective definitions / name resolution / validation ----------------

    override fun effectiveDefinitions(workspaceAvailable: Boolean): List<ToolDefinition> =
        ALL_DEFINITIONS.mapNotNull { def ->
            val setting = settingFor(def)
            if (!setting.enabled) return@mapNotNull null
            if (setting.control == ToolControl.OFF) return@mapNotNull null
            if (def.requiresWorkspace && !workspaceAvailable) return@mapNotNull null
            def.copy(
                name = setting.effectiveName(def),
                description = setting.effectiveDescription(def)
            )
        }

    override fun resolveUidByName(name: String): String? {
        // Effective (renamed) names first, then the default names.
        for (def in ALL_DEFINITIONS) {
            if (settingFor(def).effectiveName(def) == name) return def.uid
        }
        for (def in ALL_DEFINITIONS) {
            if (def.name == name) return def.uid
        }
        return null
    }

    override fun validateName(uid: String, newName: String): String? {
        if (newName.length !in 1..64) {
            return "Tên công cụ phải dài từ 1 đến 64 ký tự."
        }
        val first = newName.first()
        if (!(first.isLetter() || first == '_')) {
            return "Tên công cụ phải bắt đầu bằng chữ cái hoặc dấu gạch dưới (_)."
        }
        if (!newName.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return "Tên công cụ chỉ được chứa chữ cái, chữ số, dấu gạch dưới (_) và dấu gạch ngang (-)."
        }
        for (def in ALL_DEFINITIONS) {
            if (def.uid == uid) continue
            if (settingFor(def).effectiveName(def) == newName) {
                return "Tên \"$newName\" đã được dùng cho một công cụ khác."
            }
        }
        return null
    }

    private object Keys {
        val SETTINGS_JSON = stringPreferencesKey("tool_settings_json")
    }

    companion object {
        // -- JSON Schema helper ----------------------------------------------
        // properties: name -> (json type, description); "array" and "integer"
        // / "boolean" types are supported as well.
        private fun schema(
            properties: Map<String, Pair<String, String>>,
            required: List<String>
        ): String {
            val sb = StringBuilder()
            sb.append("{\"type\":\"object\",\"properties\":{")
            sb.append(
                properties.entries.joinToString(",") { (name, typeAndDesc) ->
                    val (type, desc) = typeAndDesc
                    val items = if (type == "array") ",\"items\":{\"type\":\"string\"}" else ""
                    "\"$name\":{\"type\":\"$type\",\"description\":\"${desc.replace("\"", "")}\"$items}"
                }
            )
            sb.append("},\"required\":[")
            sb.append(required.joinToString(",") { "\"$it\"" })
            sb.append("]}")
            return sb.toString()
        }

        private fun s(type: String, desc: String): Pair<String, String> = type to desc

        private fun def(
            uid: String,
            name: String,
            description: String,
            category: ToolCategory,
            isReadOnly: Boolean,
            parametersJson: String,
            requiresWorkspace: Boolean
        ): ToolDefinition = ToolDefinition(
            uid = uid,
            name = name,
            description = description,
            category = category,
            isReadOnly = isReadOnly,
            // Read-only tools run automatically; writes/moves/edits ask first;
            // delete always asks (§15).
            defaultControl = if (isReadOnly) ToolControl.AUTO else ToolControl.ASK,
            parametersJson = parametersJson,
            requiresWorkspace = requiresWorkspace
        )

        /** All 50 tools. Parameter names match exactly what the executors expect. */
        val ALL_DEFINITIONS: List<ToolDefinition> = listOf(
            // -- File / directory tools (§11), all workspace-relative ---------
            def(
                ToolUids.FILE_LIST_DIR, "list_dir",
                "List files and subfolders in a workspace directory. Parameters: path (workspace-relative directory, empty or \".\" for the workspace root), maxDepth (optional, how deep to list).",
                ToolCategory.FILE, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative directory path, empty or . for the workspace root"),
                        "maxDepth" to s("integer", "Maximum depth to list, default 1")
                    ), listOf("path")
                ), true
            ),
            def(
                ToolUids.FILE_TREE, "tree",
                "Show the directory tree of a workspace folder. Parameters: path (workspace-relative directory), maxDepth (optional, maximum depth).",
                ToolCategory.FILE, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative directory path"),
                        "maxDepth" to s("integer", "Maximum depth of the tree, default 3")
                    ), listOf("path")
                ), true
            ),
            def(
                ToolUids.FILE_INFO, "file_info",
                "Show information about a file or folder: size, type, last modified time. Parameters: path (workspace-relative path).",
                ToolCategory.FILE, true,
                schema(mapOf("path" to s("string", "Workspace-relative file or folder path")), listOf("path")),
                true
            ),
            def(
                ToolUids.FILE_MKDIR, "mkdir",
                "Create a new folder (including parent folders) inside the workspace. Parameters: path (workspace-relative folder path to create).",
                ToolCategory.FILE, false,
                schema(mapOf("path" to s("string", "Workspace-relative folder path to create")), listOf("path")),
                true
            ),
            def(
                ToolUids.FILE_COPY, "copy",
                "Copy a file or folder to another location inside the workspace. Parameters: from (workspace-relative source path), to (workspace-relative destination path).",
                ToolCategory.FILE, false,
                schema(
                    mapOf(
                        "from" to s("string", "Workspace-relative source path"),
                        "to" to s("string", "Workspace-relative destination path")
                    ), listOf("from", "to")
                ), true
            ),
            def(
                ToolUids.FILE_MOVE, "move",
                "Move a file or folder to another location inside the workspace. Parameters: from (workspace-relative source path), to (workspace-relative destination path).",
                ToolCategory.FILE, false,
                schema(
                    mapOf(
                        "from" to s("string", "Workspace-relative source path"),
                        "to" to s("string", "Workspace-relative destination path")
                    ), listOf("from", "to")
                ), true
            ),
            def(
                ToolUids.FILE_RENAME, "rename",
                "Rename a file or folder inside the workspace. Parameters: path (workspace-relative path of the item), newName (new name only, without any folder part).",
                ToolCategory.FILE, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative path of the item to rename"),
                        "newName" to s("string", "New name for the item")
                    ), listOf("path", "newName")
                ), true
            ),
            def(
                ToolUids.FILE_DELETE, "delete",
                "Delete a file or folder inside the workspace. This always asks the user for confirmation first. Parameters: path (workspace-relative path to delete).",
                ToolCategory.FILE, false,
                schema(mapOf("path" to s("string", "Workspace-relative path to delete")), listOf("path")),
                true
            ),
            def(
                ToolUids.FILE_SEARCH_NAME, "search_name",
                "Find files and folders by name inside the workspace. Parameters: query (name or part of a name to search for), path (optional workspace-relative folder to search in).",
                ToolCategory.FILE, true,
                schema(
                    mapOf(
                        "query" to s("string", "File or folder name, or part of a name, to search for"),
                        "path" to s("string", "Optional workspace-relative folder to search in")
                    ), listOf("query")
                ), true
            ),
            def(
                ToolUids.FILE_SEARCH_CONTENT, "search_content",
                "Search for text inside files in the workspace. Parameters: query (text to search for), path (optional workspace-relative folder to search in).",
                ToolCategory.FILE, true,
                schema(
                    mapOf(
                        "query" to s("string", "Text to search for inside files"),
                        "path" to s("string", "Optional workspace-relative folder to search in")
                    ), listOf("query")
                ), true
            ),
            def(
                ToolUids.FILE_ZIP, "zip",
                "Compress files or folders into a zip archive inside the workspace. Parameters: paths (list of workspace-relative paths to compress), output (workspace-relative path of the zip file to create).",
                ToolCategory.FILE, false,
                schema(
                    mapOf(
                        "paths" to s("array", "List of workspace-relative paths to compress"),
                        "output" to s("string", "Workspace-relative path of the zip file to create")
                    ), listOf("paths", "output")
                ), true
            ),
            def(
                ToolUids.FILE_UNZIP, "unzip",
                "Extract a zip archive inside the workspace. Parameters: path (workspace-relative path of the zip file), output (workspace-relative folder to extract into).",
                ToolCategory.FILE, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative path of the zip file"),
                        "output" to s("string", "Workspace-relative folder to extract into")
                    ), listOf("path", "output")
                ), true
            ),
            // -- Text file tools (§12) ----------------------------------------
            def(
                ToolUids.TEXT_READ, "read_text",
                "Read the full content of a text file in the workspace. Parameters: path (workspace-relative file path).",
                ToolCategory.TEXT, true,
                schema(mapOf("path" to s("string", "Workspace-relative text file path")), listOf("path")),
                true
            ),
            def(
                ToolUids.TEXT_READ_RANGE, "read_range",
                "Read a range of lines from a text file, with line numbers. Parameters: path (workspace-relative file path), startLine (first line, 1-based), endLine (last line, inclusive).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative text file path"),
                        "startLine" to s("integer", "First line number, 1-based"),
                        "endLine" to s("integer", "Last line number, inclusive")
                    ), listOf("path", "startLine", "endLine")
                ), true
            ),
            def(
                ToolUids.TEXT_READ_MULTI, "read_multi",
                "Read several text files at once. Parameters: paths (list of workspace-relative file paths).",
                ToolCategory.TEXT, true,
                schema(mapOf("paths" to s("array", "List of workspace-relative text file paths")), listOf("paths")),
                true
            ),
            def(
                ToolUids.TEXT_WRITE_NEW, "write_new",
                "Create a new text file with the given content. Fails if the file already exists. Parameters: path (workspace-relative file path), content (text to write).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path to create"),
                        "content" to s("string", "Text content to write")
                    ), listOf("path", "content")
                ), true
            ),
            def(
                ToolUids.TEXT_WRITE_OVERWRITE, "write_overwrite",
                "Overwrite a text file with new content, replacing everything in it. Parameters: path (workspace-relative file path), content (new text content).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path"),
                        "content" to s("string", "New text content replacing the whole file")
                    ), listOf("path", "content")
                ), true
            ),
            def(
                ToolUids.TEXT_APPEND, "append_text",
                "Append text to the end of a text file. Parameters: path (workspace-relative file path), content (text to append).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path"),
                        "content" to s("string", "Text to append at the end of the file")
                    ), listOf("path", "content")
                ), true
            ),
            def(
                ToolUids.TEXT_INSERT_LINE, "insert_line",
                "Insert text at a specific line number in a text file. Parameters: path (workspace-relative file path), line (line number, 1-based, before which the text is inserted), content (text to insert).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path"),
                        "line" to s("integer", "Line number, 1-based, before which to insert"),
                        "content" to s("string", "Text to insert")
                    ), listOf("path", "line", "content")
                ), true
            ),
            def(
                ToolUids.TEXT_DELETE_LINES, "delete_lines",
                "Delete a range of lines from a text file. Parameters: path (workspace-relative file path), startLine (first line to delete, 1-based), endLine (last line to delete, inclusive).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path"),
                        "startLine" to s("integer", "First line to delete, 1-based"),
                        "endLine" to s("integer", "Last line to delete, inclusive")
                    ), listOf("path", "startLine", "endLine")
                ), true
            ),
            def(
                ToolUids.TEXT_REPLACE_EXACT, "replace_exact",
                "Replace one exact piece of text in a file with new text. The old text must match exactly once. Parameters: path (workspace-relative file path), oldText (exact text to replace), newText (replacement text).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative file path"),
                        "oldText" to s("string", "Exact text to replace, must occur exactly once"),
                        "newText" to s("string", "Replacement text")
                    ), listOf("path", "oldText", "newText")
                ), true
            ),
            def(
                ToolUids.TEXT_FIND_REPLACE, "find_replace",
                "Find and replace text in one or more files. Parameters: paths (list of workspace-relative file paths), find (text to find), replace (replacement text), replaceAll (true to replace every occurrence, false for the first only).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "paths" to s("array", "List of workspace-relative file paths"),
                        "find" to s("string", "Text to find"),
                        "replace" to s("string", "Replacement text"),
                        "replaceAll" to s("boolean", "Replace every occurrence when true, only the first when false")
                    ), listOf("paths", "find", "replace")
                ), true
            ),
            def(
                ToolUids.TEXT_DIFF, "diff",
                "Compare two text files and show the differences line by line. Parameters: pathA (workspace-relative path of the first file), pathB (workspace-relative path of the second file).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "pathA" to s("string", "Workspace-relative path of the first file"),
                        "pathB" to s("string", "Workspace-relative path of the second file")
                    ), listOf("pathA", "pathB")
                ), true
            ),
            def(
                ToolUids.TEXT_COUNT, "count_text",
                "Count words, lines and characters in a text file. Parameters: path (workspace-relative file path).",
                ToolCategory.TEXT, true,
                schema(mapOf("path" to s("string", "Workspace-relative text file path")), listOf("path")),
                true
            ),
            def(
                ToolUids.CSV_READ, "csv_read",
                "Read a CSV file as a table. Parameters: path (workspace-relative CSV file path).",
                ToolCategory.TEXT, true,
                schema(mapOf("path" to s("string", "Workspace-relative CSV file path")), listOf("path")),
                true
            ),
            def(
                ToolUids.CSV_FILTER, "csv_filter",
                "Filter rows of a CSV file where a column equals a value. Parameters: path (workspace-relative CSV file path), column (column name), value (value rows must equal).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "column" to s("string", "Column name to filter on"),
                        "value" to s("string", "Value rows must equal")
                    ), listOf("path", "column", "value")
                ), true
            ),
            def(
                ToolUids.CSV_SORT, "csv_sort",
                "Sort the rows of a CSV file by a column. Parameters: path (workspace-relative CSV file path), column (column name to sort by), ascending (true for ascending, false for descending).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "column" to s("string", "Column name to sort by"),
                        "ascending" to s("boolean", "Sort ascending when true, descending when false")
                    ), listOf("path", "column")
                ), true
            ),
            def(
                ToolUids.CSV_STATS, "csv_stats",
                "Show statistics for a CSV file: row count and, for a numeric column, min, max and average. Parameters: path (workspace-relative CSV file path), column (optional numeric column name).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "column" to s("string", "Optional numeric column name for min, max and average")
                    ), listOf("path")
                ), true
            ),
            def(
                ToolUids.CSV_ADD_ROW, "csv_add_row",
                "Add a row to a CSV file. Parameters: path (workspace-relative CSV file path), row (the new row as comma-separated values).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "row" to s("string", "The new row as comma-separated values")
                    ), listOf("path", "row")
                ), true
            ),
            def(
                ToolUids.CSV_EDIT_CELL, "csv_edit_cell",
                "Change one cell in a CSV file. Parameters: path (workspace-relative CSV file path), column (column name), row (row number, 1-based, not counting the header), value (new cell value).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "column" to s("string", "Column name"),
                        "row" to s("integer", "Row number, 1-based, not counting the header"),
                        "value" to s("string", "New cell value")
                    ), listOf("path", "column", "row", "value")
                ), true
            ),
            def(
                ToolUids.JSON_VALIDATE, "json_validate",
                "Check whether a JSON file is valid. Parameters: path (workspace-relative JSON file path).",
                ToolCategory.TEXT, true,
                schema(mapOf("path" to s("string", "Workspace-relative JSON file path")), listOf("path")),
                true
            ),
            def(
                ToolUids.JSON_PRETTY, "json_pretty",
                "Reformat a JSON file with indentation so it is easy to read. Parameters: path (workspace-relative JSON file path).",
                ToolCategory.TEXT, false,
                schema(mapOf("path" to s("string", "Workspace-relative JSON file path")), listOf("path")),
                true
            ),
            def(
                ToolUids.JSON_GET, "json_get",
                "Get a value from a JSON file by path, for example user.name or items.0.title. Parameters: path (workspace-relative JSON file path), jsonPath (dot-separated path to the value).",
                ToolCategory.TEXT, true,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative JSON file path"),
                        "jsonPath" to s("string", "Dot-separated path to the value, for example user.name")
                    ), listOf("path", "jsonPath")
                ), true
            ),
            def(
                ToolUids.JSON_SET, "json_set",
                "Set a value in a JSON file by path, for example user.name. Parameters: path (workspace-relative JSON file path), jsonPath (dot-separated path), valueJson (new value as JSON, for example \"text\" or 42).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative JSON file path"),
                        "jsonPath" to s("string", "Dot-separated path to the value"),
                        "valueJson" to s("string", "New value encoded as JSON")
                    ), listOf("path", "jsonPath", "valueJson")
                ), true
            ),
            def(
                ToolUids.CONVERT_CSV_TO_JSON, "csv_to_json",
                "Convert a CSV file to JSON. Parameters: path (workspace-relative CSV file path), output (workspace-relative path of the JSON file to create).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative CSV file path"),
                        "output" to s("string", "Workspace-relative path of the JSON file to create")
                    ), listOf("path", "output")
                ), true
            ),
            def(
                ToolUids.CONVERT_JSON_TO_CSV, "json_to_csv",
                "Convert a JSON array of objects to a CSV file. Parameters: path (workspace-relative JSON file path), output (workspace-relative path of the CSV file to create).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative JSON file path"),
                        "output" to s("string", "Workspace-relative path of the CSV file to create")
                    ), listOf("path", "output")
                ), true
            ),
            def(
                ToolUids.CONVERT_MD_TO_HTML, "md_to_html",
                "Convert a Markdown file to HTML. Parameters: path (workspace-relative Markdown file path), output (workspace-relative path of the HTML file to create).",
                ToolCategory.TEXT, false,
                schema(
                    mapOf(
                        "path" to s("string", "Workspace-relative Markdown file path"),
                        "output" to s("string", "Workspace-relative path of the HTML file to create")
                    ), listOf("path", "output")
                ), true
            ),
            // -- Daily tools (§13), no workspace needed -----------------------
            def(
                ToolUids.DAILY_CALC, "calc",
                "Calculate a math expression. Supports + - * / % ^, parentheses, decimals and the functions sqrt, min, max. Parameters: expression (the math expression to evaluate, for example (2 + 3) * 4).",
                ToolCategory.DAILY, true,
                schema(mapOf("expression" to s("string", "Math expression to evaluate, for example (2 + 3) * 4")), listOf("expression")),
                false
            ),
            def(
                ToolUids.DAILY_NOW, "now",
                "Get the current date and time in the device timezone. No parameters.",
                ToolCategory.DAILY, true,
                schema(emptyMap(), emptyList()),
                false
            ),
            def(
                ToolUids.DAILY_DAYS_BETWEEN, "days_between",
                "Count the days between two dates. Parameters: from (start date, yyyy-MM-dd), to (end date, yyyy-MM-dd). The result is signed: positive when to is after from.",
                ToolCategory.DAILY, true,
                schema(
                    mapOf(
                        "from" to s("string", "Start date in yyyy-MM-dd format"),
                        "to" to s("string", "End date in yyyy-MM-dd format")
                    ), listOf("from", "to")
                ), false
            ),
            def(
                ToolUids.DAILY_FETCH_URL, "fetch_url",
                "Read the readable text of a web page from its link. Parameters: url (the web page URL to read).",
                ToolCategory.DAILY, true,
                schema(mapOf("url" to s("string", "URL of the web page to read")), listOf("url")),
                false
            ),
            def(
                ToolUids.DAILY_WEB_SEARCH, "web_search",
                "Search the web. Needs a search API key saved in Settings. Parameters: query (search query).",
                ToolCategory.DAILY, true,
                schema(mapOf("query" to s("string", "Search query")), listOf("query")),
                false
            ),
            def(
                ToolUids.DAILY_NOTE_ADD, "note_add",
                "Save a note so it is remembered. Parameters: text (note content).",
                ToolCategory.DAILY, false,
                schema(mapOf("text" to s("string", "Note content to save")), listOf("text")),
                false
            ),
            def(
                ToolUids.DAILY_NOTE_LIST, "note_list",
                "List all saved notes. No parameters.",
                ToolCategory.DAILY, true,
                schema(emptyMap(), emptyList()),
                false
            ),
            def(
                ToolUids.DAILY_TODO_ADD, "todo_add",
                "Add an item to the to-do list. Parameters: title (task title), due (optional due date or note, for example 2026-11-10).",
                ToolCategory.DAILY, false,
                schema(
                    mapOf(
                        "title" to s("string", "Task title"),
                        "due" to s("string", "Optional due date or note")
                    ), listOf("title")
                ), false
            ),
            def(
                ToolUids.DAILY_TODO_LIST, "todo_list",
                "List all to-do items with their done state and id. No parameters.",
                ToolCategory.DAILY, true,
                schema(emptyMap(), emptyList()),
                false
            ),
            def(
                ToolUids.DAILY_TODO_DONE, "todo_done",
                "Mark a to-do item as done. Parameters: id (the id of the item, as shown by todo_list) or index (its 1-based position in the list).",
                ToolCategory.DAILY, false,
                schema(
                    mapOf(
                        "id" to s("string", "Id of the to-do item, as shown by todo_list"),
                        "index" to s("integer", "1-based position of the item in the list")
                    ), emptyList()
                ), false
            ),
            def(
                ToolUids.DAILY_ALARM, "alarm",
                "Set an alarm at a time of day. Parameters: hour (0-23), minute (0-59), label (optional alarm label).",
                ToolCategory.DAILY, false,
                schema(
                    mapOf(
                        "hour" to s("integer", "Hour of day, 0-23"),
                        "minute" to s("integer", "Minute, 0-59"),
                        "label" to s("string", "Optional alarm label")
                    ), listOf("hour", "minute")
                ), false
            ),
            def(
                ToolUids.DAILY_CALENDAR_EVENT, "calendar_event",
                "Create an event in the primary calendar. Needs calendar permission. Parameters: title (event title), startIso (start time in ISO format, for example 2026-11-07T09:00:00+07:00), endIso (end time in ISO format), description (optional event description).",
                ToolCategory.DAILY, false,
                schema(
                    mapOf(
                        "title" to s("string", "Event title"),
                        "startIso" to s("string", "Start time in ISO format"),
                        "endIso" to s("string", "End time in ISO format"),
                        "description" to s("string", "Optional event description")
                    ), listOf("title", "startIso", "endIso")
                ), false
            ),
            def(
                ToolUids.DAILY_CLIPBOARD_WRITE, "clipboard_write",
                "Copy text to the clipboard. Parameters: text (text to copy).",
                ToolCategory.DAILY, false,
                schema(mapOf("text" to s("string", "Text to copy to the clipboard")), listOf("text")),
                false
            )
        )
    }
}
