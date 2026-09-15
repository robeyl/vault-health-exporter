package com.vaulthealth.exporter.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.vaulthealth.core.checksum.Sha256
import com.vaulthealth.core.naming.FileNames
import com.vaulthealth.exporter.domain.WriteOutcome

/**
 * Writes into a user-chosen SAF tree (the Syncthing-synced vault).
 *
 * Immutable exports are never overwritten: we write a temp sibling, read it back to verify,
 * then rename into place. Derived files (daily summaries) are overwritten in place.
 */
class VaultWriter(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun canWrite(treeUri: Uri): Boolean =
        runCatching { DocumentFile.fromTreeUri(context, treeUri)?.canWrite() == true }.getOrDefault(false)

    fun exists(treeUri: Uri, dirPath: String, fileName: String): Boolean =
        findByPath(treeUri, dirPath, fileName) != null

    fun list(treeUri: Uri, dirPath: String): List<String> {
        val dir = findDir(treeUri, segments(dirPath)) ?: return emptyList()
        val childrenUri = childrenUri(dir)
        val names = mutableListOf<String>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) names.add(cursor.getString(idx))
        }
        return names.sorted()
    }

    fun ensureDirectories(treeUri: Uri, dirPaths: List<String>): List<String> =
        dirPaths.filter { findOrCreateDir(treeUri, segments(it)) == null }

    fun writeImmutable(
        treeUri: Uri,
        dirPath: String,
        fileName: String,
        content: String,
    ): WriteOutcome {
        val dir = findOrCreateDir(treeUri, segments(dirPath))
            ?: return WriteOutcome.Failed("Vault folder is not accessible: $dirPath")
        if (child(dir, fileName) != null) return WriteOutcome.AlreadyExists(fileName)

        val tempName = "$fileName.tmp"
        child(dir, tempName)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }

        val tempUri = createDocument(dir, tempName)
            ?: return WriteOutcome.Failed("Could not create $tempName")
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            resolver.openOutputStream(tempUri, "w")?.use { stream ->
                stream.write(bytes)
                stream.flush()
            } ?: return WriteOutcome.Failed("Could not open $tempName for writing")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
            return WriteOutcome.Failed(t.message ?: "Write failed for $fileName")
        }

        val readBack = readUri(tempUri)
        if (readBack != content) {
            runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
            return WriteOutcome.Failed("Verification failed immediately after writing $fileName")
        }

        val renamed = runCatching { DocumentsContract.renameDocument(resolver, tempUri, fileName) }
            .getOrNull()
            ?: return WriteOutcome.Failed("Could not finalise $fileName (rename failed)")

        val actualName = displayName(renamed)
        if (actualName != null && actualName != fileName) {
            return WriteOutcome.Failed("Provider renamed $fileName to $actualName")
        }

        return WriteOutcome.Written(
            fileName = fileName,
            sha256 = Sha256.hex(readBack),
            bytes = bytes.size,
            verified = true,
        )
    }

    fun writeChecksum(
        treeUri: Uri,
        dirPath: String,
        fileName: String,
        sha256: String,
    ): WriteOutcome = writeImmutable(
        treeUri = treeUri,
        dirPath = dirPath,
        fileName = FileNames.checksum(fileName),
        content = Sha256.checksumLine(sha256, fileName),
    )

    /** Overwrites the target. Used only for derived (regenerable) files such as daily summaries. */
    fun writeDerived(treeUri: Uri, dirPath: String, fileName: String, content: String): WriteOutcome {
        val dir = findOrCreateDir(treeUri, segments(dirPath))
            ?: return WriteOutcome.Failed("Vault folder is not accessible: $dirPath")
        val target = child(dir, fileName) ?: createDocument(dir, fileName)
            ?: return WriteOutcome.Failed("Could not create $fileName")
        try {
            resolver.openOutputStream(target, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: return WriteOutcome.Failed("Could not open $fileName for writing")
        } catch (t: Throwable) {
            return WriteOutcome.Failed(t.message ?: "Write failed for $fileName")
        }
        val readBack = readUri(target)
            ?: return WriteOutcome.Failed("Could not read back $fileName")
        return WriteOutcome.Written(
            fileName = fileName,
            sha256 = Sha256.hex(readBack),
            bytes = readBack.toByteArray(Charsets.UTF_8).size,
            verified = readBack == content,
        )
    }

    fun readText(treeUri: Uri, dirPath: String, fileName: String): String? {
        val uri = findByPath(treeUri, dirPath, fileName) ?: return null
        return readUri(uri)
    }

    // ---- internals -------------------------------------------------------

    private fun segments(dirPath: String): List<String> =
        dirPath.split('/').filter { it.isNotBlank() }

    private fun findDir(treeUri: Uri, dirSegments: List<String>): Uri? {
        var current = rootDocument(treeUri) ?: return null
        for (segment in dirSegments) {
            current = child(current, segment) ?: return null
        }
        return current
    }

    private fun findOrCreateDir(treeUri: Uri, dirSegments: List<String>): Uri? {
        var current = rootDocument(treeUri) ?: return null
        for (segment in dirSegments) {
            current = child(current, segment)
                ?: createDocument(current, segment, DocumentsContract.Document.MIME_TYPE_DIR)
                ?: return null
        }
        return current
    }

    private fun findByPath(treeUri: Uri, dirPath: String, fileName: String): Uri? {
        val dir = findDir(treeUri, segments(dirPath)) ?: return null
        return child(dir, fileName)
    }

    private fun rootDocument(treeUri: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
    }.getOrNull()

    private fun child(parent: Uri, displayName: String): Uri? {
        val childrenUri = childrenUri(parent)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                }
            }
        }
        return null
    }

    private fun childrenUri(parent: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parent,
        DocumentsContract.getDocumentId(parent),
    )

    private fun createDocument(
        parent: Uri,
        name: String,
        mimeType: String = "application/octet-stream",
    ): Uri? = runCatching {
        DocumentsContract.createDocument(resolver, parent, mimeType, name)
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun readUri(uri: Uri): String? = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
