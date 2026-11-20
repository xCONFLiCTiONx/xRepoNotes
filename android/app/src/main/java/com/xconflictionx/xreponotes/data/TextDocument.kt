package com.xconflictionx.xreponotes.data

import android.net.Uri

data class TextDocument(
    val uri: Uri? = null,
    val fileName: String = "Untitled.txt",
    val content: String = "",
    val initialContent: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "text/plain",
    val isReadOnly: Boolean = false,
    val isBinaryWarning: Boolean = false,
    val errorMessage: String? = null
) {
    val isModified: Boolean
        get() = content != initialContent

    val lineCount: Int
        get() = if (content.isEmpty()) 1 else content.count { it == '\n' } + 1

    val wordCount: Int
        get() = if (content.isBlank()) 0 else content.trim().split("\\s+".toRegex()).size

    val charCount: Int
        get() = content.length

    val formattedSize: String
        get() {
            val bytes = if (fileSize > 0) fileSize else content.toByteArray(Charsets.UTF_8).size.toLong()
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            }
        }
}
