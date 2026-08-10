package com.opennovel.reader.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import java.io.File

/**
 * Where downloaded chapters are written.
 *
 * App-private storage is invisible to file managers and is wiped on uninstall,
 * which loses a library the user may have spent days building. So the user can
 * point Hibana at any folder through the Storage Access Framework; the tree URI
 * is persisted along with a *persistable* read/write grant, because a plain
 * grant dies with the process and the next download would silently fail.
 *
 * Both layouts coexist on purpose: chapters downloaded before a folder was
 * chosen keep their absolute file paths in the database and stay readable.
 * Everything that touches a stored location therefore branches on the scheme
 * rather than assuming one storage model.
 */
class DownloadStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The chosen folder, or null when downloads go to app-private storage.
     * Returns null too if the grant was revoked (folder deleted, SD card pulled,
     * permissions cleared) so callers transparently fall back instead of failing.
     */
    fun treeUri(): Uri? {
        val stored = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == stored && it.isReadPermission && it.isWritePermission
        }
        return stored.takeIf { held }
    }

    val isCustom: Boolean get() = treeUri() != null

    /**
     * Records the folder returned by `OpenDocumentTree` and takes the long-lived
     * grant. Returns false if the grant could not be persisted, in which case
     * nothing is stored — a remembered folder we cannot write to would break
     * every later download with no way for the user to see why.
     */
    fun set(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        if (!taken) return false
        prefs.edit { putString(KEY_TREE, uri.toString()) }
        return true
    }

    /** Reverts to app-private storage and releases the grant. */
    fun clear() {
        prefs.getString(KEY_TREE, null)?.let(Uri::parse)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit { remove(KEY_TREE) }
    }

    /** Something recognisable in the UI; providers encode the path in the tree id. */
    fun label(): String {
        val uri = treeUri() ?: return defaultRoot().absolutePath
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        return id.substringAfter(':').ifBlank { id }
    }

    fun defaultRoot(): File = File(context.filesDir, "downloads")

    /**
     * Writes one chapter into the chosen folder, under a per-novel subfolder.
     * Returns the document URI to store in the database, or null when no folder
     * is configured (the caller then writes to app-private storage).
     */
    fun writeChapter(novelId: Long, chapterId: Long, text: String): String? {
        val tree = treeUri() ?: return null
        return runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val dir = childOf(tree, root, novelId.toString())
                ?: DocumentsContract.createDocument(
                    context.contentResolver,
                    root,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    novelId.toString(),
                )
                ?: return null

            val name = "$chapterId.txt"
            // Providers rename collisions to "123 (1).txt" instead of replacing,
            // so a re-download would leak a new file every time.
            childOf(tree, dir, name)?.let { existing ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, existing) }
            }
            val file = DocumentsContract.createDocument(
                context.contentResolver,
                dir,
                MIME_TEXT,
                name,
            ) ?: return null

            context.contentResolver.openOutputStream(file, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return null
            file.toString()
        }.getOrNull()
    }

    /** Reads a stored chapter, whichever storage model produced its location. */
    fun read(location: String): String? = runCatching {
        if (location.isSafUri()) {
            context.contentResolver.openInputStream(Uri.parse(location))
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            File(location).readText(Charsets.UTF_8)
        }
    }.getOrNull()

    fun delete(location: String): Boolean = runCatching {
        if (location.isSafUri()) {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(location))
        } else {
            File(location).delete()
        }
    }.getOrDefault(false)

    private fun String.isSafUri(): Boolean = startsWith("content://")

    /** Existing child of [parent] with this display name, or null. */
    private fun childOf(tree: Uri, parent: Uri, name: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(parent),
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        null
    }.getOrNull()

    private companion object {
        const val PREFS = "download_storage"
        const val KEY_TREE = "tree_uri"
        const val MIME_TEXT = "text/plain"
    }
}
