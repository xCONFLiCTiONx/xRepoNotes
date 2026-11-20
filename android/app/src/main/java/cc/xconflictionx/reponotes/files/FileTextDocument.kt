package cc.xconflictionx.reponotes.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.Locale

data class TextDocument(
    val uri: Uri,
    val name: String,
    val text: String,
    val charset: Charset,
    val bom: ByteArray
)

object FileTextDocument {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private val databaseExtensions = setOf(
        "db", "sqlite", "sqlite3", "db3", "mdb", "accdb",
        "realm", "rdb", "sdf", "mdf", "ldf", "ibd", "frm",
        "wal", "shm", "db-wal", "db-shm", "db-journal", "sqlite-wal", "sqlite-shm", "sqlite-journal"
    )

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index) ?: "Untitled"
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
    }

    fun read(context: Context, uri: Uri): TextDocument {
        val name = displayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension !in databaseExtensions) {
            "Database files are not supported in the File Editor."
        }

        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_BYTES) { "This file is larger than the 20 MB editor limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Android could not open this file.")

        require(!bytes.startsWith("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
            "SQLite database files cannot be opened in the File Editor."
        }
        require(!bytes.startsWith(byteArrayOf(0x53, 0x51, 0x4c, 0x43))) {
            "This appears to be a database file, not a text document."
        }

        var charset: Charset
        val bom: ByteArray
        val offset: Int
        when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> {
                charset = StandardCharsets.UTF_8
                bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                offset = 3
            }
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> {
                charset = Charset.forName("UTF-16LE")
                bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                offset = 2
            }
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> {
                charset = Charset.forName("UTF-16BE")
                bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                offset = 2
            }
            else -> {
                charset = StandardCharsets.UTF_8
                bom = byteArrayOf()
                offset = 0
            }
        }

        val payload = bytes.copyOfRange(offset, bytes.size)
        require(!(charset == StandardCharsets.UTF_8 && payload.any { it == 0.toByte() })) {
            "This file contains binary data and cannot be opened as text."
        }

        val text = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            if (offset != 0) throw IllegalArgumentException("The file's text encoding is invalid.")
            charset = Charset.forName("windows-1252")
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        }

        val controlCount = text.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' && it != '\u000C' }
        require(text.isEmpty() || controlCount.toDouble() / text.length < 0.01) {
            "This file appears to contain binary data, not readable text."
        }

        return TextDocument(uri, name, text, charset, bom)
    }

    fun write(context: Context, document: TextDocument, text: String) {
        val encoded = try {
            val buffer = document.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException(
                "This file's original encoding cannot represent every character you entered. Use Save As to save a UTF-8 copy."
            )
        }

        val output = context.contentResolver.openOutputStream(document.uri, "wt")
            ?: throw IllegalStateException("Android did not grant permission to save this file.")
        output.use {
            if (document.bom.isNotEmpty()) it.write(document.bom)
            it.write(encoded)
            it.flush()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
