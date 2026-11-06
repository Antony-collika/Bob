package com.bob.ai.data.tools

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.bob.ai.data.workspace.SafWorkspaceManager
import com.bob.ai.domain.ToolExecutionResult
import com.bob.ai.domain.ToolUids
import com.bob.ai.domain.WorkspaceManager
import com.bob.ai.domain.bool
import com.bob.ai.domain.int
import com.bob.ai.domain.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json as KxJson

/**
 * Text / CSV / JSON / convert tools (spec §12). Every path is relative to the
 * workspace root; absolute paths and ".." escapes are rejected by
 * [SafWorkspaceManager]. Binary-looking files are refused by extension for
 * content search/replace.
 */
class TextToolsExecutor(private val workspaceManager: WorkspaceManager) {

    private val saf: SafWorkspaceManager? get() = workspaceManager as? SafWorkspaceManager
    private val resolver: ContentResolver? get() = saf?.contentResolver

    private val json = KxJson { ignoreUnknownKeys = true; prettyPrint = false }
    private val prettyJson = KxJson { ignoreUnknownKeys = true; prettyPrint = true }

    fun handles(uid: String): Boolean =
        uid.startsWith("text.") || uid.startsWith("csv.") ||
            uid.startsWith("json.") || uid.startsWith("convert.")

    suspend fun execute(uid: String, args: JsonObject): ToolExecutionResult {
        val manager = saf
            ?: return ToolExecutionResult(false, "", "Chưa chọn thư mục workspace")
        if (!workspaceManager.hasWorkspace()) {
            return ToolExecutionResult(false, "", "Chưa chọn thư mục workspace")
        }
        if (!workspaceManager.permissionStillValid()) {
            return ToolExecutionResult(
                false, "",
                "Quyền truy cập workspace đã hết hạn, vui lòng chọn lại thư mục workspace"
            )
        }
        if (manager.rootDocument() == null) {
            return ToolExecutionResult(false, "", "Chưa chọn thư mục workspace")
        }
        return try {
            val output = when (uid) {
                ToolUids.TEXT_READ -> textRead(manager, args)
                ToolUids.TEXT_READ_RANGE -> textReadRange(manager, args)
                ToolUids.TEXT_READ_MULTI -> textReadMulti(manager, args)
                ToolUids.TEXT_WRITE_NEW -> textWriteNew(manager, args)
                ToolUids.TEXT_WRITE_OVERWRITE -> textWriteOverwrite(manager, args)
                ToolUids.TEXT_APPEND -> textAppend(manager, args)
                ToolUids.TEXT_INSERT_LINE -> textInsertLine(manager, args)
                ToolUids.TEXT_DELETE_LINES -> textDeleteLines(manager, args)
                ToolUids.TEXT_REPLACE_EXACT -> textReplaceExact(manager, args)
                ToolUids.TEXT_FIND_REPLACE -> textFindReplace(manager, args)
                ToolUids.TEXT_DIFF -> textDiff(manager, args)
                ToolUids.TEXT_COUNT -> textCount(manager, args)
                ToolUids.CSV_READ -> csvRead(manager, args)
                ToolUids.CSV_FILTER -> csvFilter(manager, args)
                ToolUids.CSV_SORT -> csvSort(manager, args)
                ToolUids.CSV_STATS -> csvStats(manager, args)
                ToolUids.CSV_ADD_ROW -> csvAddRow(manager, args)
                ToolUids.CSV_EDIT_CELL -> csvEditCell(manager, args)
                ToolUids.JSON_VALIDATE -> jsonValidate(manager, args)
                ToolUids.JSON_PRETTY -> jsonPretty(manager, args)
                ToolUids.JSON_GET -> jsonGet(manager, args)
                ToolUids.JSON_SET -> jsonSet(manager, args)
                ToolUids.CONVERT_CSV_TO_JSON -> convertCsvToJson(manager, args)
                ToolUids.CONVERT_JSON_TO_CSV -> convertJsonToCsv(manager, args)
                ToolUids.CONVERT_MD_TO_HTML -> convertMdToHtml(manager, args)
                else -> return ToolExecutionResult(false, "", "Công cụ không hỗ trợ: $uid")
            }
            ToolExecutionResult(true, output)
        } catch (e: Exception) {
            ToolExecutionResult(false, "", e.message ?: "Lỗi không xác định")
        }
    }

    // ------------------------------------------------------------------
    // Shared file helpers
    // ------------------------------------------------------------------

    private fun displayPath(relativePath: String): String {
        val clean = relativePath.replace('\\', '/').trim().trimStart('/')
        return clean.ifEmpty { "(thư mục gốc)" }
    }

    private fun childName(relativePath: String): String =
        relativePath.replace('\\', '/').trim().trimEnd('/').substringAfterLast('/')

    /** Refuse files whose extension is a known binary format. */
    private fun requireNotBinary(fileName: String) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext in TextFileExtensions.BINARY) {
            throw IllegalArgumentException("Không hỗ trợ file nhị phân: $fileName")
        }
    }

    /** For content search/replace: the file must look like a text file. */
    private fun requireTextFile(fileName: String) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val hasExt = fileName.contains('.')
        if (hasExt && ext !in TextFileExtensions.ALL) {
            throw IllegalArgumentException("Không phải file văn bản: $fileName")
        }
    }

    private fun requireTextDoc(manager: SafWorkspaceManager, relativePath: String): DocumentFile {
        val doc = manager.resolve(relativePath)
            ?: throw IllegalArgumentException("Không tìm thấy: ${displayPath(relativePath)}")
        if (doc.isDirectory) {
            throw IllegalArgumentException("Đường dẫn là thư mục, không phải file: ${displayPath(relativePath)}")
        }
        requireNotBinary(doc.name ?: relativePath)
        return doc
    }

    private fun readText(manager: SafWorkspaceManager, relativePath: String): String {
        val doc = requireTextDoc(manager, relativePath)
        if (doc.length() > MAX_READ_BYTES) {
            throw IllegalArgumentException("File quá lớn để đọc (${doc.length()} byte): ${displayPath(relativePath)}")
        }
        val res = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        return res.openInputStream(doc.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Không đọc được file: ${displayPath(relativePath)}")
    }

    /** Create the file (with parent dirs) if missing, then overwrite its content. */
    private fun writeText(manager: SafWorkspaceManager, relativePath: String, content: String): DocumentFile {
        val clean = manager.sanitize(relativePath)
            ?: throw IllegalArgumentException("Đường dẫn không hợp lệ: ${displayPath(relativePath)}")
        if (clean.isEmpty()) throw IllegalArgumentException("Đường dẫn không phải là file")
        requireNotBinary(childName(clean))
        val res = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val existing = manager.resolve(clean)
        if (existing != null) {
            if (!existing.isFile) {
                throw IllegalArgumentException("Đường dẫn không phải là file: ${displayPath(relativePath)}")
            }
            res.openOutputStream(existing.uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Không ghi được file: ${displayPath(relativePath)}")
            return existing
        }
        val parent = manager.ensureParentDir(clean)
            ?: throw IllegalArgumentException("Đường dẫn không hợp lệ: ${displayPath(relativePath)}")
        val doc = parent.createFile(FileToolsExecutor.mimeFor(childName(clean)), childName(clean))
            ?: throw IllegalStateException("Không tạo được file: ${displayPath(relativePath)}")
        res.openOutputStream(doc.uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Không ghi được file: ${displayPath(relativePath)}")
        return doc
    }

    /** Like [writeText], but write to [outputPath] when given, else return [text]. */
    private fun emitConverted(
        manager: SafWorkspaceManager,
        outputPath: String,
        text: String
    ): String {
        if (outputPath.isBlank()) return text
        writeText(manager, outputPath, text)
        return "Đã ghi kết quả vào: ${displayPath(outputPath)}\n\n$text"
    }

    private fun stringList(args: JsonObject, key: String): List<String> {
        val array = args[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    // ------------------------------------------------------------------
    // Text tools
    // ------------------------------------------------------------------

    private fun textRead(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        return readText(manager, path)
    }

    private fun textReadRange(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val text = readText(manager, path)
        val lines = text.split('\n')
        val startLine = args.int("startLine", 1).coerceAtLeast(1)
        val endLine = args.int("endLine", 0).let { if (it <= 0) lines.size else it }
            .coerceAtMost(lines.size)
        if (startLine > lines.size) {
            throw IllegalArgumentException("File chỉ có ${lines.size} dòng, startLine=$startLine vượt quá giới hạn")
        }
        if (endLine < startLine) {
            throw IllegalArgumentException("endLine ($endLine) nhỏ hơn startLine ($startLine)")
        }
        val width = endLine.toString().length
        return (startLine..endLine).joinToString("\n") { n ->
            n.toString().padStart(width) + ": " + lines[n - 1]
        }
    }

    private fun textReadMulti(manager: SafWorkspaceManager, args: JsonObject): String {
        val paths = stringList(args, "paths")
        if (paths.isEmpty()) throw IllegalArgumentException("Thiếu tham số bắt buộc: paths")
        return paths.joinToString("\n\n") { path ->
            "=== ${displayPath(path)} ===\n${readText(manager, path)}"
        }
    }

    private fun textWriteNew(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val content = args.string("content", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        if (manager.resolve(path) != null) {
            throw IllegalArgumentException("File đã tồn tại: ${displayPath(path)}")
        }
        writeText(manager, path, content)
        return "Đã tạo file mới: ${displayPath(path)}"
    }

    private fun textWriteOverwrite(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val content = args.string("content", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        writeText(manager, path, content)
        return "Đã ghi đè file: ${displayPath(path)}"
    }

    private fun textAppend(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val content = args.string("content", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val res = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val existing = manager.resolve(path)
        if (existing == null) {
            writeText(manager, path, content)
            return "Đã tạo file mới: ${displayPath(path)}"
        }
        if (!existing.isFile) throw IllegalArgumentException("Đường dẫn không phải là file: ${displayPath(path)}")
        requireNotBinary(existing.name ?: path)
        res.openOutputStream(existing.uri, "wa")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Không ghi được file: ${displayPath(path)}")
        return "Đã thêm vào cuối file: ${displayPath(path)}"
    }

    private fun textInsertLine(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val line = args.int("line", 0)
        val content = args.string("content", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val lines = readText(manager, path).split('\n').toMutableList()
        if (line < 1 || line > lines.size + 1) {
            throw IllegalArgumentException("Dòng $line không hợp lệ (file có ${lines.size} dòng)")
        }
        lines.add(line - 1, content)
        writeText(manager, path, lines.joinToString("\n"))
        return "Đã chèn vào dòng $line của file: ${displayPath(path)}"
    }

    private fun textDeleteLines(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val startLine = args.int("startLine", 0)
        val endLine = args.int("endLine", 0)
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val lines = readText(manager, path).split('\n').toMutableList()
        if (startLine < 1 || endLine < startLine || endLine > lines.size) {
            throw IllegalArgumentException(
                "Khoảng dòng $startLine–$endLine không hợp lệ (file có ${lines.size} dòng)"
            )
        }
        lines.subList(startLine - 1, endLine).clear()
        writeText(manager, path, lines.joinToString("\n"))
        return "Đã xóa dòng $startLine–$endLine của file: ${displayPath(path)}"
    }

    private fun textReplaceExact(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val oldText = args.string("oldText", "")
        val newText = args.string("newText", "")
        if (path.isBlank() || oldText.isEmpty()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, oldText")
        }
        requireTextFile(childName(path))
        val text = readText(manager, path)
        val occurrences = countOccurrences(text, oldText)
        if (occurrences == 0) throw IllegalArgumentException("Không tìm thấy đoạn cần thay trong file")
        if (occurrences > 1) {
            throw IllegalArgumentException(
                "Tìm thấy $occurrences vị trí khớp, cần đúng 1 vị trí duy nhất — hãy dùng đoạn dài hơn hoặc text.find_replace"
            )
        }
        writeText(manager, path, text.replace(oldText, newText))
        return "Đã thay thế đoạn văn bản trong file: ${displayPath(path)}"
    }

    private fun textFindReplace(manager: SafWorkspaceManager, args: JsonObject): String {
        val find = args.string("find", "")
        val replace = args.string("replace", "")
        val replaceAll = args.bool("replaceAll", false)
        if (find.isEmpty()) throw IllegalArgumentException("Thiếu tham số bắt buộc: find")
        val paths = stringList(args, "paths").ifEmpty {
            val single = args.string("path", "")
            if (single.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: paths hoặc path")
            listOf(single)
        }
        val reports = mutableListOf<String>()
        for (path in paths) {
            requireTextFile(childName(path))
            val text = readText(manager, path)
            val count = countOccurrences(text, find)
            if (count == 0) {
                reports.add("${displayPath(path)}: không tìm thấy")
                continue
            }
            val updated = if (replaceAll) text.replace(find, replace) else text.replaceFirst(find, replace)
            val replaced = if (replaceAll) count else 1
            writeText(manager, path, updated)
            reports.add("${displayPath(path)}: đã thay $replaced vị trí")
        }
        return reports.joinToString("\n")
    }

    private fun textDiff(manager: SafWorkspaceManager, args: JsonObject): String {
        val pathA = args.string("pathA", "")
        val pathB = args.string("pathB", "")
        if (pathA.isBlank() || pathB.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: pathA, pathB")
        }
        val aLines = readText(manager, pathA).split('\n')
        val bLines = readText(manager, pathB).split('\n')
        if (aLines == bLines) return "Hai file giống hệt nhau."
        val out = StringBuilder()
        out.append("--- ${displayPath(pathA)}\n")
        out.append("+++ ${displayPath(pathB)}\n")
        // Common prefix / suffix, then LCS on the middle.
        var prefix = 0
        while (prefix < minOf(aLines.size, bLines.size) && aLines[prefix] == bLines[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(aLines.size - prefix, bLines.size - prefix) &&
            aLines[aLines.size - 1 - suffix] == bLines[bLines.size - 1 - suffix]
        ) suffix++
        val aMid = aLines.subList(prefix, aLines.size - suffix)
        val bMid = bLines.subList(prefix, bLines.size - suffix)
        fun emitContext(lines: List<String>) {
            for (line in lines) out.append("  $line\n")
        }
        emitContext(aLines.subList(0, prefix))
        if (aMid.size.toLong() * bMid.size.toLong() <= 1_000_000L) {
            // LCS dynamic programming on the differing middle.
            val n = aMid.size
            val m = bMid.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (aMid[i] == bMid[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && aMid[i] == bMid[j] -> {
                        out.append("  ${aMid[i]}\n"); i++; j++
                    }
                    j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j]) -> {
                        out.append("+ ${bMid[j]}\n"); j++
                    }
                    else -> {
                        out.append("- ${aMid[i]}\n"); i++
                    }
                }
            }
        } else {
            for (line in aMid) out.append("- $line\n")
            for (line in bMid) out.append("+ $line\n")
        }
        emitContext(aLines.subList(aLines.size - suffix, aLines.size))
        return out.toString().trimEnd('\n')
    }

    private fun textCount(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val text = readText(manager, path)
        val lines = if (text.isEmpty()) 0 else text.split('\n').size
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return "File: ${displayPath(path)}\nDòng: $lines\nTừ: $words\nKý tự: ${text.length}"
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ------------------------------------------------------------------
    // CSV tools (own small parser: quoted fields, commas, newlines)
    // ------------------------------------------------------------------

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' && field.isEmpty() -> inQuotes = true
                c == ',' -> {
                    row.add(field.toString())
                    field.clear()
                }
                c == '\r' -> Unit // \n handles the line break
                c == '\n' -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun escapeCsvField(value: String): String {
        return if (value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        ) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    private fun toCsv(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        return rows.joinToString("\n") { row -> row.joinToString(",") { escapeCsvField(it) } } + "\n"
    }

    private fun readCsv(manager: SafWorkspaceManager, path: String): List<List<String>> {
        val rows = parseCsv(readText(manager, path))
        if (rows.isEmpty()) throw IllegalArgumentException("File CSV trống: ${displayPath(path)}")
        return rows
    }

    private fun writeCsv(manager: SafWorkspaceManager, path: String, rows: List<List<String>>) {
        writeText(manager, path, toCsv(rows))
    }

    /** Column by header name (case-insensitive) or 0-based index. */
    private fun columnIndex(header: List<String>, column: String): Int {
        column.toIntOrNull()?.let { index ->
            if (index in header.indices) return index
            throw IllegalArgumentException("Cột $index không tồn tại (có ${header.size} cột)")
        }
        val index = header.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (index < 0) throw IllegalArgumentException("Không tìm thấy cột: $column")
        return index
    }

    private fun formatTable(rows: List<List<String>>, maxRows: Int = 200): String {
        val shown = rows.take(maxRows)
        val colCount = shown.maxOfOrNull { it.size } ?: 0
        val widths = IntArray(colCount) { col ->
            shown.maxOfOrNull { row ->
                (row.getOrNull(col) ?: "").length.coerceAtMost(40)
            } ?: 0
        }
        fun render(row: List<String>): String {
            return (0 until colCount).joinToString(" | ") { col ->
                val cell = row.getOrNull(col) ?: ""
                val short = if (cell.length > 40) cell.take(37) + "…" else cell
                short.padEnd(widths[col])
            }
        }
        val lines = mutableListOf<String>()
        lines.add(render(shown.first()))
        lines.add((0 until colCount).joinToString("-+-") { "-".repeat(maxOf(1, widths[it])) })
        for (row in shown.drop(1)) lines.add(render(row))
        if (rows.size > maxRows) lines.add("… (đã giới hạn $maxRows/${rows.size} dòng)")
        return lines.joinToString("\n")
    }

    private fun csvRead(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val rows = readCsv(manager, path)
        return "Bảng ${displayPath(path)} (${rows.size - 1} dòng dữ liệu, ${rows.first().size} cột):\n" +
            formatTable(rows)
    }

    private fun csvFilter(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val column = args.string("column", "")
        if (path.isBlank() || column.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, column")
        }
        val rows = readCsv(manager, path)
        val header = rows.first()
        val col = columnIndex(header, column)
        val contains = args["contains"] as? JsonPrimitive
        val value = args["value"] as? JsonPrimitive
        val data = rows.drop(1)
        val filtered = when {
            contains != null -> data.filter {
                (it.getOrNull(col) ?: "").contains(contains.content, ignoreCase = true)
            }
            value != null -> data.filter { (it.getOrNull(col) ?: "") == value.content }
            else -> throw IllegalArgumentException("Thiếu tham số: value hoặc contains")
        }
        if (filtered.isEmpty()) return "Không có dòng nào khớp điều kiện lọc."
        return "Lọc được ${filtered.size}/${data.size} dòng:\n" +
            formatTable(listOf(header) + filtered)
    }

    private fun csvSort(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val column = args.string("column", "")
        if (path.isBlank() || column.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, column")
        }
        val ascending = args.bool("ascending", true)
        val rows = readCsv(manager, path)
        val header = rows.first()
        val col = columnIndex(header, column)
        val data = rows.drop(1)
        val comparator = Comparator<List<String>> { a, b ->
            val va = a.getOrNull(col) ?: ""
            val vb = b.getOrNull(col) ?: ""
            val na = va.toDoubleOrNull()
            val nb = vb.toDoubleOrNull()
            if (na != null && nb != null) na.compareTo(nb) else va.compareTo(vb)
        }
        val sorted = if (ascending) data.sortedWith(comparator) else data.sortedWith(comparator.reversed())
        return formatTable(listOf(header) + sorted)
    }

    private fun csvStats(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val column = args.string("column", "")
        if (path.isBlank() || column.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, column")
        }
        val rows = readCsv(manager, path)
        val header = rows.first()
        val col = columnIndex(header, column)
        val data = rows.drop(1)
        val values = data.map { it.getOrNull(col) ?: "" }
        val nonEmpty = values.filter { it.isNotBlank() }
        val unique = nonEmpty.toSet().size
        val out = StringBuilder()
        out.append("Cột: ${header[col]}\n")
        out.append("Số dòng: ${data.size}\n")
        out.append("Giá trị khác rỗng: ${nonEmpty.size}\n")
        out.append("Giá trị khác nhau: $unique\n")
        val numbers = nonEmpty.mapNotNull { it.toDoubleOrNull() }
        if (numbers.isNotEmpty() && numbers.size == nonEmpty.size) {
            val min = numbers.min()
            val max = numbers.max()
            val mean = numbers.average()
            out.append("Nhỏ nhất: ${formatNumber(min)}\n")
            out.append("Lớn nhất: ${formatNumber(max)}\n")
            out.append("Trung bình: ${"%.2f".format(mean)}")
        }
        return out.toString()
    }

    private fun formatNumber(value: Double): String {
        return if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9_000_000_000_000_000.0) {
            value.toLong().toString()
        } else {
            "%.2f".format(value)
        }
    }

    private fun csvAddRow(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val rowArg = args["row"] as? JsonArray
            ?: throw IllegalArgumentException("Thiếu tham số bắt buộc: row (mảng JSON)")
        val row = rowArg.map { element ->
            when (element) {
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }
        val rows = readCsv(manager, path).toMutableList()
        val header = rows.first()
        if (row.size != header.size) {
            throw IllegalArgumentException(
                "Dòng mới có ${row.size} giá trị nhưng bảng có ${header.size} cột"
            )
        }
        rows.add(row)
        writeCsv(manager, path, rows)
        return "Đã thêm dòng mới vào: ${displayPath(path)} (tổng ${rows.size - 1} dòng dữ liệu)"
    }

    private fun csvEditCell(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val column = args.string("column", "")
        val rowIndex = args.int("row", -1)
        val value = (args["value"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Thiếu tham số bắt buộc: value")
        if (path.isBlank() || column.isBlank() || rowIndex < 0) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, row, column, value")
        }
        val rows = readCsv(manager, path).map { it.toMutableList() }.toMutableList()
        val header = rows.first()
        val col = columnIndex(header, column)
        val dataIndex = rowIndex + 1 // row 0 = first data row, after the header
        if (dataIndex >= rows.size) {
            throw IllegalArgumentException("Dòng $rowIndex không tồn tại (có ${rows.size - 1} dòng dữ liệu)")
        }
        val dataRow = rows[dataIndex]
        while (dataRow.size <= col) dataRow.add("")
        dataRow[col] = value
        writeCsv(manager, path, rows)
        return "Đã sửa ô (dòng $rowIndex, cột ${header[col]}) trong: ${displayPath(path)}"
    }

    // ------------------------------------------------------------------
    // JSON tools
    // ------------------------------------------------------------------

    private fun parseJsonFile(manager: SafWorkspaceManager, path: String): JsonElement {
        val text = readText(manager, path)
        return try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON không hợp lệ trong ${displayPath(path)}: ${e.message}")
        }
    }

    private fun navigate(element: JsonElement, jsonPath: String): JsonElement? {
        if (jsonPath.isBlank()) return element
        var current = element
        for (segment in jsonPath.split('.')) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    current.getOrNull(index) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    private fun setAt(element: JsonElement, segments: List<String>, value: JsonElement): JsonElement {
        if (segments.isEmpty()) return value
        val head = segments.first()
        val rest = segments.drop(1)
        return when (element) {
            is JsonObject -> {
                val map = element.toMutableMap()
                map[head] = setAt(map[head] ?: JsonNull, rest, value)
                JsonObject(map)
            }
            is JsonArray -> {
                val index = head.toIntOrNull()
                    ?: throw IllegalArgumentException("Đường dẫn \"$head\" không phải chỉ số mảng")
                if (index < 0 || index >= element.size) {
                    throw IllegalArgumentException("Chỉ số mảng $index ngoài giới hạn (0–${element.size - 1})")
                }
                val list = element.toMutableList()
                list[index] = setAt(list[index], rest, value)
                JsonArray(list)
            }
            is JsonNull -> {
                // Missing node: create an object or an array from the next segment.
                if (head.toIntOrNull() != null) {
                    val index = head.toInt()
                    if (index < 0) throw IllegalArgumentException("Chỉ số mảng $index không hợp lệ")
                    val list = MutableList<JsonElement>(index + 1) { JsonNull }
                    list[index] = setAt(JsonNull, rest, value)
                    JsonArray(list)
                } else {
                    JsonObject(mapOf(head to setAt(JsonNull, rest, value)))
                }
            }
            else -> throw IllegalArgumentException(
                "Không thể đi sâu vào giá trị tại \"$head\" (không phải object/array)"
            )
        }
    }

    private fun jsonValidate(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val element = parseJsonFile(manager, path)
        val kind = when (element) {
            is JsonObject -> "object (${element.size} khóa)"
            is JsonArray -> "mảng (${element.size} phần tử)"
            is JsonPrimitive -> "giá trị đơn"
            else -> "giá trị"
        }
        return "JSON hợp lệ: ${displayPath(path)} là $kind."
    }

    private fun jsonPretty(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val element = parseJsonFile(manager, path)
        val formatted = prettyJson.encodeToString(JsonElement.serializer(), element)
        writeText(manager, path, formatted)
        return formatted
    }

    private fun jsonGet(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val jsonPath = args.string("jsonPath", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val element = parseJsonFile(manager, path)
        val found = navigate(element, jsonPath)
            ?: throw IllegalArgumentException("Không tìm thấy đường dẫn \"$jsonPath\" trong JSON")
        return when (found) {
            is JsonObject, is JsonArray ->
                prettyJson.encodeToString(JsonElement.serializer(), found)
            is JsonPrimitive -> found.content
            else -> found.toString()
        }
    }

    private fun jsonSet(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val jsonPath = args.string("jsonPath", "")
        if (path.isBlank() || jsonPath.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, jsonPath")
        }
        val valueArg = args["valueJson"] as? JsonPrimitive
        val value: JsonElement = when {
            valueArg != null -> try {
                json.parseToJsonElement(valueArg.content)
            } catch (e: Exception) {
                throw IllegalArgumentException("valueJson không phải JSON hợp lệ: ${e.message}")
            }
            args["value"] != null -> args["value"]!!
            else -> throw IllegalArgumentException("Thiếu tham số bắt buộc: valueJson")
        }
        val element = parseJsonFile(manager, path)
        val updated = setAt(element, jsonPath.split('.'), value)
        val formatted = prettyJson.encodeToString(JsonElement.serializer(), updated)
        writeText(manager, path, formatted)
        return "Đã đặt \"$jsonPath\" trong: ${displayPath(path)}"
    }

    // ------------------------------------------------------------------
    // Convert tools
    // ------------------------------------------------------------------

    private fun convertCsvToJson(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val output = args.string("output", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val rows = readCsv(manager, path)
        val header = rows.first()
        val array = JsonArray(rows.drop(1).map { row ->
            JsonObject(header.mapIndexed { index, name ->
                name to inferScalar(row.getOrNull(index) ?: "")
            }.toMap())
        })
        val text = prettyJson.encodeToString(JsonElement.serializer(), array)
        return emitConverted(manager, output, text)
    }

    /** Turn a CSV cell into a JSON number/boolean when it clearly looks like one. */
    private fun inferScalar(raw: String): JsonElement {
        val value = raw.trim()
        if (value.isEmpty()) return JsonPrimitive("")
        value.toLongOrNull()?.let { return JsonPrimitive(it) }
        value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if (value.equals("true", ignoreCase = true)) return JsonPrimitive(true)
        if (value.equals("false", ignoreCase = true)) return JsonPrimitive(false)
        return JsonPrimitive(raw)
    }

    private fun convertJsonToCsv(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val output = args.string("output", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val element = parseJsonFile(manager, path)
        val objects: List<JsonObject> = when (element) {
            is JsonArray -> element.map {
                it as? JsonObject
                    ?: throw IllegalArgumentException("Mảng JSON phải chứa các object để chuyển sang CSV")
            }
            is JsonObject -> listOf(element)
            else -> throw IllegalArgumentException("JSON phải là object hoặc mảng các object")
        }
        if (objects.isEmpty()) throw IllegalArgumentException("JSON trống, không có gì để chuyển đổi")
        val headers = mutableListOf<String>()
        for (obj in objects) {
            for (key in obj.keys) if (key !in headers) headers.add(key)
        }
        val rows = mutableListOf<List<String>>()
        rows.add(headers)
        for (obj in objects) {
            rows.add(headers.map { key ->
                when (val v = obj[key]) {
                    null, is JsonNull -> ""
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
            })
        }
        val text = toCsv(rows)
        return emitConverted(manager, output, text)
    }

    private fun convertMdToHtml(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val output = args.string("output", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val markdown = readText(manager, path)
        val body = markdownToHtml(markdown)
        val title = childName(path).substringBeforeLast('.')
        val html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n" +
            "<title>${escapeHtml(title)}</title>\n</head>\n<body>\n$body\n</body>\n</html>"
        return emitConverted(manager, output, html)
    }

    // ------------------------------------------------------------------
    // Minimal Markdown -> HTML
    // ------------------------------------------------------------------

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun inlineMarkdown(escaped: String): String {
        // Protect `code` spans first so ** / * inside them are not formatted.
        val codeSpans = mutableListOf<String>()
        var text = Regex("`([^`]+)`").replace(escaped) { match ->
            codeSpans.add(match.groupValues[1])
            "\u0000${codeSpans.size - 1}\u0000"
        }
        text = Regex("\\*\\*([^*]+)\\*\\*").replace(text) { "<strong>${it.groupValues[1]}</strong>" }
        text = Regex("\\*([^*]+)\\*").replace(text) { "<em>${it.groupValues[1]}</em>" }
        text = Regex("_([^_]+)_").replace(text) { "<em>${it.groupValues[1]}</em>" }
        text = Regex("!\\[([^]]*)]\\(([^)]+)\\)").replace(text) {
            "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\">"
        }
        text = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(text) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        text = Regex("\u0000(\\d+)\u0000").replace(text) { match ->
            "<code>${codeSpans[match.groupValues[1].toInt()]}</code>"
        }
        return text
    }

    private fun markdownToHtml(markdown: String): String {
        val lines = markdown.split('\n')
        val out = StringBuilder()
        var i = 0
        var inCodeBlock = false
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out.append("<p>${inlineMarkdown(escapeHtml(paragraph.joinToString(" ")))}</p>\n")
                paragraph.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (inCodeBlock) {
                if (trimmed.startsWith("```")) {
                    out.append("</code></pre>\n")
                    inCodeBlock = false
                } else {
                    out.append(escapeHtml(line)).append('\n')
                }
                i++
                continue
            }
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    out.append("<pre><code>")
                    inCodeBlock = true
                }
                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = trimmed.drop(level).trim()
                    out.append("<h$level>${inlineMarkdown(escapeHtml(text))}</h$level>\n")
                }
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    out.append("<hr>\n")
                }
                Regex("^[-*]\\s+.*").matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ul>\n")
                    while (i < lines.size && Regex("^[-*]\\s+.*").matches(lines[i].trim())) {
                        val item = lines[i].trim().replaceFirst(Regex("^[-*]\\s+"), "")
                        out.append("<li>${inlineMarkdown(escapeHtml(item))}</li>\n")
                        i++
                    }
                    out.append("</ul>\n")
                    continue
                }
                Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                    flushParagraph()
                    out.append("<ol>\n")
                    while (i < lines.size && Regex("^\\d+\\.\\s+.*").matches(lines[i].trim())) {
                        val item = lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s+"), "")
                        out.append("<li>${inlineMarkdown(escapeHtml(item))}</li>\n")
                        i++
                    }
                    out.append("</ol>\n")
                    continue
                }
                trimmed.isEmpty() -> flushParagraph()
                else -> paragraph.add(trimmed)
            }
            i++
        }
        flushParagraph()
        if (inCodeBlock) out.append("</code></pre>\n")
        return out.toString().trimEnd('\n')
    }

    companion object {
        /** Refuse to read/write files larger than this as text. */
        const val MAX_READ_BYTES: Long = 5_000_000L
    }
}
