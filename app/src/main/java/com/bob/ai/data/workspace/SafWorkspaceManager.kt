package com.bob.ai.data.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.bob.ai.domain.WorkspaceManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.workspaceDataStore by preferencesDataStore(name = "bob_workspace")

private val KEY_TREE_URI = stringPreferencesKey("tree_uri")
private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")

/**
 * SAF workspace implementation (spec §10). Stores the tree Uri picked by the
 * user in DataStore Preferences. The UI layer must call
 * `takePersistableUriPermission` on the Uri before calling [setWorkspace].
 *
 * All tool paths are relative to the workspace root; see [sanitize] and
 * [resolve] — nothing may escape the root.
 */
class SafWorkspaceManager(private val context: Context) : WorkspaceManager {

    /** ContentResolver, exposed so the tool executors can open document streams. */
    val contentResolver: ContentResolver get() = context.contentResolver

    private fun readStored(): Pair<String?, String?> = runBlocking {
        context.workspaceDataStore.data.map { prefs ->
            prefs[KEY_TREE_URI] to prefs[KEY_DISPLAY_NAME]
        }.first()
    }

    override fun hasWorkspace(): Boolean {
        val (uri, _) = readStored()
        return !uri.isNullOrBlank()
    }

    override fun workspaceUriString(): String? = readStored().first

    override fun workspaceDisplayName(): String? = readStored().second

    override suspend fun setWorkspace(uriString: String, displayName: String?) {
        context.workspaceDataStore.edit { prefs ->
            prefs[KEY_TREE_URI] = uriString
            if (displayName.isNullOrBlank()) {
                prefs.remove(KEY_DISPLAY_NAME)
            } else {
                prefs[KEY_DISPLAY_NAME] = displayName
            }
        }
    }

    override suspend fun clearWorkspace() {
        context.workspaceDataStore.edit { prefs ->
            prefs.remove(KEY_TREE_URI)
            prefs.remove(KEY_DISPLAY_NAME)
        }
    }

    override fun permissionStillValid(): Boolean {
        val uriString = workspaceUriString() ?: return false
        if (uriString.isBlank()) return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    // ------------------------------------------------------------------
    // Helpers used by the file / text tool executors
    // ------------------------------------------------------------------

    /** The workspace root document, or null when no workspace is set / invalid. */
    fun rootDocument(): DocumentFile? {
        val uriString = workspaceUriString() ?: return null
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clean a user/model supplied relative path. Returns the cleaned relative
     * path ("" means the workspace root), or null when the path is unsafe:
     * absolute, or containing a ".." segment that could escape the root.
     * Backslashes are treated as separators; "." and empty segments are dropped.
     */
    fun sanitize(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/').trim()
        if (normalized.isEmpty()) return ""
        if (normalized.startsWith("/")) return null
        val out = mutableListOf<String>()
        for (segment in normalized.split('/')) {
            when (segment) {
                "", "." -> Unit // drop
                ".." -> return null
                else -> out.add(segment)
            }
        }
        return out.joinToString("/")
    }

    /**
     * Resolve a relative path under the workspace root. Returns null when the
     * path is unsafe (see [sanitize]) or the document does not exist.
     */
    fun resolve(relativePath: String): DocumentFile? {
        val clean = sanitize(relativePath) ?: return null
        var current = rootDocument() ?: return null
        if (clean.isEmpty()) return current
        for (segment in clean.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** Resolve the parent directory of a relative path (root for top-level paths). */
    fun resolveParent(relativePath: String): DocumentFile? {
        val clean = sanitize(relativePath) ?: return null
        if (clean.isEmpty()) return null
        val parent = clean.substringBeforeLast('/', "")
        if (parent.isEmpty()) return rootDocument()
        return resolve(parent)
    }

    /**
     * Resolve the parent directory of [relativePath], creating any missing
     * intermediate directories. Returns null when the path is unsafe, the
     * root is unavailable, or a path segment already exists as a file.
     */
    fun ensureParentDir(relativePath: String): DocumentFile? {
        val clean = sanitize(relativePath) ?: return null
        val parent = clean.substringBeforeLast('/', "")
        if (parent.isEmpty()) return rootDocument()
        var current = rootDocument() ?: return null
        for (segment in parent.split('/')) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }
}
