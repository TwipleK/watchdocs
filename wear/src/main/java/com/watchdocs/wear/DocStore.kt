package com.watchdocs.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class DocType(val label: String, val color: Long) {
    PDF("PDF", 0xFFE53935),
    DOCX("DOC", 0xFF1E88E5),
    XLSX("XLS", 0xFF43A047),
    UNKNOWN("?", 0xFF757575);

    companion object {
        fun of(name: String): DocType = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PDF
            "docx" -> DOCX
            "xlsx" -> XLSX
            else -> UNKNOWN
        }
    }
}

/** Documents live in app-private storage: filesDir/docs. */
object DocStore {
    private val _docs = MutableStateFlow<List<File>>(emptyList())
    val docs: StateFlow<List<File>> = _docs

    fun dir(ctx: Context): File = File(ctx.filesDir, "docs").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), sanitize(name))

    fun refresh(ctx: Context) {
        _docs.value = dir(ctx).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun delete(ctx: Context, name: String) {
        file(ctx, name).delete()
        refresh(ctx)
    }

    fun sanitize(raw: String): String {
        val n = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_")
            .trim()
        return if (n.isBlank() || n == "." || n == "..") "document" else n
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
