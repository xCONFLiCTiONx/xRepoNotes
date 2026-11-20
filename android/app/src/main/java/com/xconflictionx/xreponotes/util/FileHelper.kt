package com.xconflictionx.xreponotes.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xconflictionx.xreponotes.data.TextDocument
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object FileHelper {
    private val databaseExtensions = setOf("db", "sqlite", "sqlite3", "db3", "s3db", "sl3", "sqlite-wal", "sqlite-shm", "mdb", "accdb", "realm")

    fun readDocumentFromUri(context: Context, uri: Uri): TextDocument {
        val resolver = context.contentResolver
        var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled.txt"
        var fileSize = 0L
        val mimeType = resolver.getType(uri) ?: "text/plain"
        if (uri.scheme == "content") {
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) { }
        } else if (uri.scheme == "file") {
            try { val f = File(uri.path ?: ""); if (f.exists()) fileSize = f.length() } catch (_: Exception) { }
        }

        if (isDatabaseFile(fileName, mimeType)) return TextDocument(
            uri = uri, fileName = fileName, fileSize = fileSize, mimeType = mimeType,
            isReadOnly = true, errorMessage = "Database files are not supported in the text editor."
        )

        return try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Unable to open input stream for URI: $uri")
            if (fileSize <= 0) fileSize = bytes.size.toLong()
            if (bytes.size >= 16 && String(bytes, 0, 16, Charsets.US_ASCII) == "SQLite format 3\u0000") {
                return TextDocument(uri = uri, fileName = fileName, fileSize = fileSize, mimeType = mimeType,
                    isReadOnly = true, errorMessage = "SQLite database files are not supported in the text editor.")
            }
            val sampleSize = minOf(bytes.size, 1024)
            val isBinary = (0 until sampleSize).any { bytes[it] == 0.toByte() }
            val text = String(bytes, Charsets.UTF_8)
            TextDocument(uri = uri, fileName = fileName, content = text, initialContent = text,
                fileSize = fileSize, mimeType = mimeType, isReadOnly = false, isBinaryWarning = isBinary)
        } catch (e: Exception) {
            TextDocument(uri = uri, fileName = fileName, fileSize = fileSize, mimeType = mimeType,
                isReadOnly = true, errorMessage = "Failed to load file: " + (e.localizedMessage ?: "Unknown error"))
        }
    }

    private fun isDatabaseFile(name: String, mimeType: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in databaseExtensions ||
            mimeType.contains("sqlite", ignoreCase = true) ||
            mimeType.contains("ms-access", ignoreCase = true) ||
            mimeType.contains("vnd.ms-access", ignoreCase = true)
    }

    fun saveDocumentToUri(
        context: Context,
        uri: Uri,
        content: String,
        expectedOriginalContent: String? = null
    ): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver

        if (expectedOriginalContent != null) {
            val currentContent = try {
                if (uri.scheme == "file") {
                    File(uri.path ?: return false).readText(Charsets.UTF_8)
                } else {
                    resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return false
                }
            } catch (_: Exception) { return false }
            if (currentContent != expectedOriginalContent) return false
        }

        if (uri.scheme == "file") {
            val destination = File(uri.path ?: return false)
            if (!destination.exists() || !destination.isFile || !destination.canWrite()) return false
            val temporary = File(destination.parentFile, destination.name + ".xreponotes-tmp")
            val backup = File(destination.parentFile, destination.name + ".xreponotes-bak")
            return try {
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
                if (backup.exists()) backup.delete()
                if (!destination.renameTo(backup)) return false
                if (!temporary.renameTo(destination)) { backup.renameTo(destination); return false }
                backup.delete()
                true
            } catch (_: Exception) {
                if (backup.exists() && !destination.exists()) backup.renameTo(destination)
                temporary.delete()
                false
            }
        }

        val targetName = try {
            if (uri.scheme == "content") resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            } ?: "" else uri.lastPathSegment?.substringAfterLast('/') ?: ""
        } catch (_: Exception) { "" }
        if (isDatabaseFile(targetName, resolver.getType(uri) ?: "")) return false
        try {
            val signature = resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(16)
                val count = input.read(header)
                if (count == 16) String(header, Charsets.US_ASCII) else ""
            } ?: ""
            if (signature == "SQLite format 3\u0000") return false
        } catch (_: Exception) { }
        try {
            resolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                    stream.fd.sync()
                }
                return true
            }
        } catch (_: Exception) { }

        return try {
            resolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(bytes); stream.flush() } ?: return false
            true
        } catch (_: Exception) { false }
    }
}
