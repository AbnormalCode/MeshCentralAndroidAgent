package com.meshcentral.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import java.io.InputStream
import java.io.OutputStream

/** One entry of a folder the device user has shared with the agent. */
internal data class StorageEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

/**
 * Access to a folder the device user picked with the Android document picker.
 *
 * Scoped storage stops an ordinary application from reading the whole file system,
 * so the file manager can only reach media files and whatever folder the user hands
 * over here. The grant is persisted across reboots and can be withdrawn from the
 * agent menu at any time.
 */
internal object StorageAccess {
    // The name of this root as MeshCentral's file manager shows it.
    const val ROOT_NAME = "Files"

    private const val PREF_KEY = "pref_file_access_tree"

    fun createPickerIntent(): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        return intent
    }

    /** Remember a folder the user just granted, keeping the grant across restarts. */
    fun setTree(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY, uri.toString())
                .apply()
            true
        } catch (ex: Exception) {
            println("StorageAccess: unable to persist $uri, $ex")
            false
        }
    }

    /** Forget the shared folder and release the grant. */
    fun clear(context: Context) {
        val uri = storedTree(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_KEY).apply()
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (ex: Exception) {
            }
        }
    }

    fun hasAccess(context: Context): Boolean {
        return tree(context) != null
    }

    /** The name of the shared folder, for the agent user interface. */
    fun folderName(context: Context): String? {
        val uri = tree(context) ?: return null
        val rootDocument = try {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } catch (ex: Exception) {
            return null
        }
        context.contentResolver.query(
            rootDocument,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    /** True when this virtual path belongs to the shared folder. */
    fun isStoragePath(path: String): Boolean {
        return (path == ROOT_NAME) || path.startsWith("$ROOT_NAME/")
    }

    /** List the entries of a folder below the shared root. */
    fun listFolder(context: Context, path: String): List<StorageEntry>? {
        val uri = tree(context) ?: return null
        val documentId = documentIdForPath(context, uri, path) ?: return null
        val children = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        } catch (ex: Exception) {
            return null
        }

        val result = ArrayList<StorageEntry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val mimeType = cursor.getString(1)
                result.add(
                    StorageEntry(
                        name,
                        mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (cursor.isNull(2)) 0L else cursor.getLong(2),
                        if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    )
                )
            }
        } ?: return null
        return result
    }

    /** Open a file below the shared root for reading, and report its size. */
    fun openForRead(context: Context, path: String): Pair<InputStream, Long>? {
        val uri = tree(context) ?: return null
        val documentId = documentIdForPath(context, uri, path) ?: return null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        val size = documentSize(context, documentUri)
        val stream = try {
            context.contentResolver.openInputStream(documentUri)
        } catch (ex: Exception) {
            null
        } ?: return null
        return Pair(stream, size)
    }

    /** Create, or replace, a file in a folder below the shared root. */
    fun openForWrite(context: Context, path: String, name: String): OutputStream? {
        if (!isSafeFileName(name)) return null
        val uri = tree(context) ?: return null
        val parentId = documentIdForPath(context, uri, path) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)

        // Replace an existing file rather than letting Android create "name (1)".
        val existing = childDocumentId(context, uri, parentId, name)
        if (existing != null) {
            try {
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(uri, existing)
                )
            } catch (ex: Exception) {
            }
        }

        val created = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeTypeForName(name),
                name
            )
        } catch (ex: Exception) {
            null
        } ?: return null

        return try {
            context.contentResolver.openOutputStream(created)
        } catch (ex: Exception) {
            null
        }
    }

    /** Create a folder below the shared root. */
    fun createFolder(context: Context, path: String, name: String): Boolean {
        if (!isSafeFileName(name)) return false
        val uri = tree(context) ?: return false
        val parentId = documentIdForPath(context, uri, path) ?: return false
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
        return try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            ) != null
        } catch (ex: Exception) {
            false
        }
    }

    /** Rename a file or folder below the shared root. */
    fun rename(context: Context, path: String, oldName: String, newName: String): Boolean {
        if (!isSafeFileName(oldName) || !isSafeFileName(newName)) return false
        val uri = tree(context) ?: return false
        val parentId = documentIdForPath(context, uri, path) ?: return false
        val documentId = childDocumentId(context, uri, parentId, oldName) ?: return false
        return try {
            DocumentsContract.renameDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(uri, documentId),
                newName
            ) != null
        } catch (ex: Exception) {
            false
        }
    }

    /** Delete a file or folder below the shared root. */
    fun delete(context: Context, path: String, name: String): Boolean {
        if (!isSafeFileName(name)) return false
        val uri = tree(context) ?: return false
        val parentId = documentIdForPath(context, uri, path) ?: return false
        val documentId = childDocumentId(context, uri, parentId, name) ?: return false
        return try {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
            )
        } catch (ex: Exception) {
            false
        }
    }

    private fun documentSize(context: Context, documentUri: Uri): Long {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0L
    }

    /**
     * Walk a virtual path such as "Files/Reports/2026" down from the shared root.
     * Every segment is looked up by display name, so a path can never escape the
     * folder the user granted.
     */
    private fun documentIdForPath(context: Context, tree: Uri, path: String): String? {
        if (!isStoragePath(path)) return null
        var documentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (ex: Exception) {
            return null
        }
        val segments = path.removePrefix(ROOT_NAME).split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (!isSafeFileName(segment)) return null
            documentId = childDocumentId(context, tree, documentId, segment) ?: return null
        }
        return documentId
    }

    private fun childDocumentId(context: Context, tree: Uri, parentId: String, name: String): String? {
        val children = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        } catch (ex: Exception) {
            return null
        }
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return cursor.getString(0)
            }
        }
        return null
    }

    private fun storedTree(context: Context): Uri? {
        val value = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, null) ?: return null
        return try {
            Uri.parse(value)
        } catch (ex: Exception) {
            null
        }
    }

    /** The shared folder, but only while the grant is still held. */
    private fun tree(context: Context): Uri? {
        val uri = storedTree(context) ?: return null
        val held = try {
            context.contentResolver.persistedUriPermissions.any {
                (it.uri == uri) && it.isReadPermission
            }
        } catch (ex: Exception) {
            false
        }
        return if (held) uri else null
    }

    private fun mimeTypeForName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "log", "csv" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
