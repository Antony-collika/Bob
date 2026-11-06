package com.bob.ai.data.tools

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.bob.ai.data.workspace.SafWorkspaceManager
import com.bob.ai.domain.ToolExecutionResult
import com.bob.ai.domain.ToolUids
import com.bob.ai.domain.WorkspaceManager
import com.bob.ai.domain.int
import com.bob.ai.domain.string
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * File / directory tools (spec §11). Every path is relative to the workspace
 * root; absolute paths and ".." escapes are rejected by [SafWorkspaceManager].
 * Outputs never expose absolute paths.
 */
class FileToolsExecutor(private val workspaceManager: WorkspaceManager) {

    private val saf: SafWorkspaceManager? get() = workspaceManager as? SafWorkspaceManager
    private val resolver: ContentResolver? get() = saf?.contentResolver

    fun handles(uid: String): Boolean = uid.startsWith("file.")

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
        val root = manager.rootDocument()
            ?: return ToolExecutionResult(false, "", "Chưa chọn thư mục workspace")
        return try {
            val output = when (uid) {
                ToolUids.FILE_LIST_DIR -> listDir(manager, root, args)
                ToolUids.FILE_TREE -> tree(manager, root, args)
                ToolUids.FILE_INFO -> info(manager, args)
                ToolUids.FILE_MKDIR -> mkdir(manager, args)
                ToolUids.FILE_COPY -> copy(manager, args)
                ToolUids.FILE_MOVE -> move(manager, args)
                ToolUids.FILE_RENAME -> rename(manager, args)
                ToolUids.FILE_DELETE -> delete(manager, args)
                ToolUids.FILE_SEARCH_NAME -> searchName(manager, root, args)
                ToolUids.FILE_SEARCH_CONTENT -> searchContent(manager, root, args)
                ToolUids.FILE_ZIP -> zip(manager, args)
                ToolUids.FILE_UNZIP -> unzip(manager, args)
                else -> return ToolExecutionResult(false, "", "Công cụ không hỗ trợ: $uid")
            }
            ToolExecutionResult(true, output)
        } catch (e: Exception) {
            ToolExecutionResult(false, "", e.message ?: "Lỗi không xác định")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun requireDoc(manager: SafWorkspaceManager, relativePath: String): DocumentFile {
        return manager.resolve(relativePath)
            ?: throw IllegalArgumentException("Không tìm thấy: ${displayPath(relativePath)}")
    }

    /** Display form of a path: cleaned relative path, never absolute. */
    private fun displayPath(relativePath: String): String {
        val clean = relativePath.replace('\\', '/').trim().trimStart('/')
        return clean.ifEmpty { "(thư mục gốc)" }
    }

    private fun childName(relativePath: String): String =
        relativePath.replace('\\', '/').trim().trimEnd('/').substringAfterLast('/')

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    private fun describe(doc: DocumentFile, relativePath: String): String {
        val type = if (doc.isDirectory) "thư mục" else "file"
        val size = if (doc.isDirectory) "" else " • ${formatSize(doc.length())}"
        return "$relativePath ($type$size • sửa lần cuối ${formatDate(doc.lastModified())})"
    }

    private fun stringList(args: JsonObject, key: String): List<String> {
        val array = args[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun copyStream(
        resolver: ContentResolver,
        src: DocumentFile,
        destParent: DocumentFile,
        name: String
    ): DocumentFile? {
        if (src.isDirectory) {
            val dir = destParent.createDirectory(name) ?: return null
            for (child in src.listFiles()) {
                copyStream(resolver, child, dir, child.name ?: "file") ?: return null
            }
            return dir
        }
        val mime = src.type ?: mimeFor(name)
        val dest = destParent.createFile(mime, name) ?: return null
        try {
            resolver.openInputStream(src.uri)?.use { input ->
                resolver.openOutputStream(dest.uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Không mở được file đích để ghi")
            } ?: throw IllegalStateException("Không mở được file nguồn để đọc")
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        return dest
    }

    private fun deleteRecursive(doc: DocumentFile): Boolean {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) deleteRecursive(child)
        }
        return doc.delete()
    }

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    private fun listDir(manager: SafWorkspaceManager, root: DocumentFile, args: JsonObject): String {
        val path = args.string("path", "")
        val dir = if (path.isBlank()) root else requireDoc(manager, path)
        if (!dir.isDirectory) throw IllegalArgumentException("Không phải thư mục: ${displayPath(path)}")
        val children = dir.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name ?: "" })
        if (children.isEmpty()) return "Thư mục ${displayPath(path)} đang trống."
        val lines = children.map { child ->
            val type = if (child.isDirectory) "thư mục" else "file"
            val size = if (child.isDirectory) "-" else formatSize(child.length())
            val mime = if (child.isDirectory) "-" else (child.type ?: "-")
            "${child.name}\t$type\t$size\t${formatDate(child.lastModified())}\t$mime"
        }
        return "Danh sách ${displayPath(path)} (${children.size} mục):\n" +
            "Tên\tLoại\tKích thước\tSửa lần cuối\tMIME\n" + lines.joinToString("\n")
    }

    private fun tree(manager: SafWorkspaceManager, root: DocumentFile, args: JsonObject): String {
        val path = args.string("path", "")
        val maxDepth = args.int("maxDepth", 3).coerceIn(1, 10)
        val start = if (path.isBlank()) root else requireDoc(manager, path)
        if (!start.isDirectory) throw IllegalArgumentException("Không phải thư mục: ${displayPath(path)}")
        val lines = mutableListOf<String>()
        val startName = if (path.isBlank()) (manager.workspaceDisplayName() ?: "workspace") else childName(path)
        lines.add(startName)
        var count = 0
        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > maxDepth || count >= 500) return
            val children = dir.listFiles()
                .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name ?: "" })
            for ((index, child) in children.withIndex()) {
                if (count >= 500) return
                count++
                val last = index == children.size - 1
                val branch = if (last) "└── " else "├── "
                lines.add(prefix + branch + (child.name ?: ""))
                if (child.isDirectory) {
                    walk(child, prefix + if (last) "    " else "│   ", depth + 1)
                }
            }
        }
        walk(start, "", 1)
        if (count >= 500) lines.add("… (đã giới hạn 500 mục)")
        return lines.joinToString("\n")
    }

    private fun info(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val doc = requireDoc(manager, path)
        val type = if (doc.isDirectory) "Thư mục" else "File"
        val size = if (doc.isDirectory) "—" else formatSize(doc.length())
        val mime = doc.type ?: "—"
        return "Tên: ${doc.name}\nLoại: $type\nĐường dẫn: ${displayPath(path)}\n" +
            "Kích thước: $size\nMIME: $mime\nSửa lần cuối: ${formatDate(doc.lastModified())}"
    }

    private fun mkdir(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val clean = manager.sanitize(path)
            ?: throw IllegalArgumentException("Đường dẫn không hợp lệ: ${displayPath(path)}")
        val existing = manager.resolve(clean)
        if (existing != null) {
            if (existing.isDirectory) return "Thư mục đã tồn tại: ${displayPath(path)}"
            throw IllegalArgumentException("Đã tồn tại một file cùng tên: ${displayPath(path)}")
        }
        var current = manager.rootDocument()
            ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        for (segment in clean.split('/')) {
            val next = current.findFile(segment)
            current = when {
                next == null -> current.createDirectory(segment)
                    ?: throw IllegalStateException("Không tạo được thư mục: $segment")
                next.isDirectory -> next
                else -> throw IllegalArgumentException("Đã tồn tại một file cùng tên: $segment")
            }
        }
        return "Đã tạo thư mục: ${displayPath(path)}"
    }

    private fun copy(manager: SafWorkspaceManager, args: JsonObject): String {
        val from = args.string("from", "")
        val to = args.string("to", "")
        if (from.isBlank() || to.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: from, to")
        }
        val resolver = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val src = requireDoc(manager, from)
        if (manager.resolve(to) != null) {
            throw IllegalArgumentException("Đích đã tồn tại: ${displayPath(to)}")
        }
        val parent = manager.ensureParentDir(to)
            ?: throw IllegalArgumentException("Đường dẫn đích không hợp lệ: ${displayPath(to)}")
        copyStream(resolver, src, parent, childName(to))
            ?: throw IllegalStateException("Không sao chép được: ${displayPath(from)}")
        return "Đã sao chép ${displayPath(from)} → ${displayPath(to)}"
    }

    private fun move(manager: SafWorkspaceManager, args: JsonObject): String {
        val from = args.string("from", "")
        val to = args.string("to", "")
        if (from.isBlank() || to.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: from, to")
        }
        val resolver = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val src = requireDoc(manager, from)
        if (manager.resolve(to) != null) {
            throw IllegalArgumentException("Đích đã tồn tại: ${displayPath(to)}")
        }
        val parent = manager.ensureParentDir(to)
            ?: throw IllegalArgumentException("Đường dẫn đích không hợp lệ: ${displayPath(to)}")
        copyStream(resolver, src, parent, childName(to))
            ?: throw IllegalStateException("Không di chuyển được: ${displayPath(from)}")
        deleteRecursive(src)
        return "Đã di chuyển ${displayPath(from)} → ${displayPath(to)}"
    }

    private fun rename(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val newName = args.string("newName", "").trim()
        if (path.isBlank() || newName.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, newName")
        }
        if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            throw IllegalArgumentException("Tên mới không hợp lệ: $newName")
        }
        val doc = requireDoc(manager, path)
        val parent = manager.resolveParent(path)
        if (parent?.findFile(newName) != null) {
            throw IllegalArgumentException("Đã tồn tại: $newName")
        }
        if (!doc.renameTo(newName)) {
            throw IllegalStateException("Không đổi tên được: ${displayPath(path)}")
        }
        return "Đã đổi tên ${displayPath(path)} → $newName"
    }

    private fun delete(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        if (path.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: path")
        val doc = requireDoc(manager, path)
        val wasDir = doc.isDirectory
        if (!deleteRecursive(doc)) {
            throw IllegalStateException("Không xóa được: ${displayPath(path)}")
        }
        return if (wasDir) "Đã xóa thư mục: ${displayPath(path)}" else "Đã xóa file: ${displayPath(path)}"
    }

    private fun searchName(manager: SafWorkspaceManager, root: DocumentFile, args: JsonObject): String {
        val query = args.string("query", "")
        val path = args.string("path", "")
        if (query.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: query")
        val start = if (path.isBlank()) root else requireDoc(manager, path)
        if (!start.isDirectory) throw IllegalArgumentException("Không phải thư mục: ${displayPath(path)}")
        val base = manager.sanitize(path) ?: ""
        val results = mutableListOf<String>()
        fun walk(dir: DocumentFile, relBase: String) {
            if (results.size >= 200) return
            for (child in dir.listFiles()) {
                if (results.size >= 200) return
                val name = child.name ?: continue
                val rel = if (relBase.isEmpty()) name else "$relBase/$name"
                if (name.contains(query, ignoreCase = true)) {
                    results.add(describe(child, rel))
                }
                if (child.isDirectory) walk(child, rel)
            }
        }
        walk(start, base)
        if (results.isEmpty()) return "Không tìm thấy file nào có tên chứa \"$query\"."
        return "Tìm thấy ${results.size} kết quả cho \"$query\":\n" + results.joinToString("\n")
    }

    private fun searchContent(manager: SafWorkspaceManager, root: DocumentFile, args: JsonObject): String {
        val query = args.string("query", "")
        val path = args.string("path", "")
        if (query.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: query")
        val start = if (path.isBlank()) root else requireDoc(manager, path)
        if (!start.isDirectory) throw IllegalArgumentException("Không phải thư mục: ${displayPath(path)}")
        val resolver = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val base = manager.sanitize(path) ?: ""
        val results = mutableListOf<String>()
        fun walk(dir: DocumentFile, relBase: String) {
            if (results.size >= 200) return
            for (child in dir.listFiles()) {
                if (results.size >= 200) return
                val name = child.name ?: continue
                val rel = if (relBase.isEmpty()) name else "$relBase/$name"
                if (child.isDirectory) {
                    walk(child, rel)
                } else if (isTextExtension(name) && child.length() <= 2_000_000) {
                    val text = try {
                        resolver.openInputStream(child.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    text.lines().forEachIndexed { index, line ->
                        if (results.size >= 200) return@forEachIndexed
                        if (line.contains(query, ignoreCase = true)) {
                            results.add("$rel:${index + 1}: ${line.trim()}")
                        }
                    }
                }
            }
        }
        walk(start, base)
        if (results.isEmpty()) return "Không tìm thấy nội dung \"$query\" trong file chữ nào."
        return "Tìm thấy ${results.size} dòng chứa \"$query\":\n" + results.joinToString("\n")
    }

    private fun zip(manager: SafWorkspaceManager, args: JsonObject): String {
        val paths = stringList(args, "paths")
        val output = args.string("output", "")
        if (paths.isEmpty()) throw IllegalArgumentException("Thiếu tham số bắt buộc: paths")
        if (output.isBlank()) throw IllegalArgumentException("Thiếu tham số bắt buộc: output")
        val resolver = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val parent = manager.ensureParentDir(output)
            ?: throw IllegalArgumentException("Đường dẫn đích không hợp lệ: ${displayPath(output)}")
        manager.resolve(output)?.delete()
        val dest = parent.createFile("application/zip", childName(output))
            ?: throw IllegalStateException("Không tạo được file zip: ${displayPath(output)}")
        var entries = 0
        try {
            resolver.openOutputStream(dest.uri)?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    fun add(doc: DocumentFile, entryPath: String) {
                        if (doc.isDirectory) {
                            zip.putNextEntry(ZipEntry("$entryPath/"))
                            zip.closeEntry()
                            for (child in doc.listFiles()) {
                                add(child, "$entryPath/${child.name}")
                            }
                        } else {
                            zip.putNextEntry(ZipEntry(entryPath))
                            resolver.openInputStream(doc.uri)?.use { input -> input.copyTo(zip) }
                                ?: throw IllegalStateException("Không đọc được: ${doc.name}")
                            zip.closeEntry()
                            entries++
                        }
                    }
                    for (path in paths) {
                        val doc = requireDoc(manager, path)
                        add(doc, childName(path))
                    }
                }
            } ?: throw IllegalStateException("Không mở được file zip để ghi")
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        return "Đã nén ${paths.size} mục ($entries file) thành: ${displayPath(output)}"
    }

    private fun unzip(manager: SafWorkspaceManager, args: JsonObject): String {
        val path = args.string("path", "")
        val outputDir = args.string("outputDir", "")
        if (path.isBlank() || outputDir.isBlank()) {
            throw IllegalArgumentException("Thiếu tham số bắt buộc: path, outputDir")
        }
        val resolver = resolver ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        val archive = requireDoc(manager, path)
        if (archive.isDirectory) throw IllegalArgumentException("Không phải file zip: ${displayPath(path)}")
        val outClean = manager.sanitize(outputDir)
            ?: throw IllegalArgumentException("Đường dẫn đích không hợp lệ: ${displayPath(outputDir)}")
        var files = 0
        var dirs = 0
        resolver.openInputStream(archive.uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = if (outClean.isEmpty()) entry.name else "$outClean/${entry.name}"
                    val cleanTarget = manager.sanitize(target)
                    if (cleanTarget == null) {
                        throw IllegalArgumentException("File zip chứa đường dẫn không an toàn: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        ensureDir(manager, cleanTarget)
                        dirs++
                    } else {
                        val parent = manager.ensureParentDir(cleanTarget)
                            ?: throw IllegalStateException("Không tạo được thư mục cho: $cleanTarget")
                        manager.resolve(cleanTarget)?.delete()
                        val file = parent.createFile(mimeFor(childName(cleanTarget)), childName(cleanTarget))
                            ?: throw IllegalStateException("Không tạo được file: $cleanTarget")
                        resolver.openOutputStream(file.uri)?.use { output ->
                            zip.copyTo(output)
                        } ?: throw IllegalStateException("Không ghi được file: $cleanTarget")
                        files++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("Không mở được file zip: ${displayPath(path)}")
        return "Đã giải nén $files file, $dirs thư mục vào: ${displayPath(outputDir)}"
    }

    private fun ensureDir(manager: SafWorkspaceManager, relativePath: String) {
        var current = manager.rootDocument()
            ?: throw IllegalStateException("Chưa chọn thư mục workspace")
        for (segment in relativePath.split('/')) {
            val next = current.findFile(segment)
            current = when {
                next == null -> current.createDirectory(segment)
                    ?: throw IllegalStateException("Không tạo được thư mục: $segment")
                next.isDirectory -> next
                else -> throw IllegalArgumentException("Đã tồn tại một file cùng tên: $segment")
            }
        }
    }

    companion object {
        /** Extensions treated as readable text for content search. */
        fun isTextExtension(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in TextFileExtensions.ALL
        }

        fun mimeFor(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "txt", "md", "log" -> "text/plain"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "xml" -> "text/xml"
                "yaml", "yml" -> "text/yaml"
                "zip" -> "application/zip"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }
    }
}

/** Text extensions shared by the file and text executors. */
internal object TextFileExtensions {
    val ALL: Set<String> = setOf(
        "txt", "md", "markdown", "csv", "json", "jsonl", "xml", "html", "htm",
        "yaml", "yml", "log", "kt", "kts", "java", "js", "mjs", "ts", "tsx",
        "jsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "css",
        "scss", "sql", "sh", "bash", "zsh", "properties", "gradle", "toml",
        "ini", "cfg", "conf", "env", "tex", "rst", "adoc", "diff", "patch",
        "srt", "vtt", "svg"
    )
    val BINARY: Set<String> = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "zip", "gz", "tar",
        "7z", "rar", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "apk", "aab", "mp3", "wav", "ogg", "flac", "mp4", "mkv", "mov",
        "avi", "webm", "db", "sqlite", "bin", "exe", "dll", "so", "class",
        "jar", "dex", "ttf", "otf", "woff", "woff2", "eot", "psd", "ai"
    )
}
