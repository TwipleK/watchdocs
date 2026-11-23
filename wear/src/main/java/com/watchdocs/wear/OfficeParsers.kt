package com.watchdocs.wear

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.util.zip.ZipFile

// ---------------------------------------------------------------- DOCX

enum class ParaStyle { NORMAL, TITLE, HEADING, LIST, TABLE_ROW }

data class Para(val text: String, val style: ParaStyle)

private fun newParser(input: InputStream): XmlPullParser =
    Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

object DocxParser {
    fun parse(file: File): List<Para> = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("Not a valid DOCX file")
        zip.getInputStream(entry).use { parseBody(it) }
    }

    private fun parseBody(input: InputStream): List<Para> {
        val p = newParser(input)
        val out = mutableListOf<Para>()
        val sb = StringBuilder()
        val cell = StringBuilder()
        val rowCells = mutableListOf<String>()
        var style = ParaStyle.NORMAL
        var tableDepth = 0
        var inText = false
        var inTabStops = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { sb.setLength(0); style = ParaStyle.NORMAL }
                    "w:pStyle" -> {
                        val v = p.getAttributeValue(null, "w:val")?.lowercase().orEmpty()
                        style = when {
                            v.startsWith("title") || v.startsWith("titel") -> ParaStyle.TITLE
                            v.startsWith("heading") || v.contains("berschrift") -> ParaStyle.HEADING
                            v.contains("list") -> ParaStyle.LIST
                            else -> style
                        }
                    }
                    "w:numPr" -> if (style == ParaStyle.NORMAL) style = ParaStyle.LIST
                    "w:tabs" -> inTabStops = true
                    "w:t" -> inText = true
                    "w:tab" -> if (!inTabStops) sb.append("    ")
                    "w:br", "w:cr" -> sb.append('\n')
                    "w:tbl" -> tableDepth++
                    "w:tr" -> if (tableDepth == 1) rowCells.clear()
                    "w:tc" -> if (tableDepth == 1) cell.setLength(0)
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inText = false
                    "w:tabs" -> inTabStops = false
                    "w:p" -> {
                        val t = sb.toString().trim()
                        if (tableDepth > 0) {
                            if (t.isNotEmpty()) {
                                if (cell.isNotEmpty()) cell.append(' ')
                                cell.append(t)
                            }
                        } else if (t.isNotEmpty()) {
                            out += Para(t, style)
                        }
                    }
                    "w:tc" -> if (tableDepth == 1) rowCells += cell.toString()
                    "w:tr" -> if (tableDepth == 1 && rowCells.any { it.isNotBlank() }) {
                        out += Para(rowCells.joinToString("  │  "), ParaStyle.TABLE_ROW)
                    }
                    "w:tbl" -> tableDepth--
                }
            }
            event = p.next()
        }
        return out
    }
}

// ---------------------------------------------------------------- XLSX

class Sheet(val name: String, val rows: List<List<String>>, val truncated: Boolean) {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

object XlsxParser {
    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 60

    fun parse(file: File): List<Sheet> = ZipFile(file).use { zip ->
        fun open(path: String) = zip.getEntry(path)?.let { zip.getInputStream(it) }

        val shared = open("xl/sharedStrings.xml")?.use { readShared(it) } ?: emptyList()
        val rels = open("xl/_rels/workbook.xml.rels")?.use { readRels(it) } ?: emptyMap()
        val workbook = open("xl/workbook.xml") ?: error("Not a valid XLSX file")
        val sheetRefs = workbook.use { readWorkbook(it) }

        val sheets = sheetRefs.mapIndexedNotNull { i, (name, rid) ->
            val target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
            val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
            open(path)?.use { readSheet(name, it, shared) }
        }
        if (sheets.isEmpty()) error("No worksheets found")
        sheets
    }

    private fun readShared(input: InputStream): List<String> {
        val p = newParser(input)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inT = false
        var inPhonetic = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> out += sb.toString()
                }
            }
            event = p.next()
        }
        return out
    }

    private fun readRels(input: InputStream): Map<String, String> {
        val p = newParser(input)
        val out = mutableMapOf<String, String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = target
            }
            event = p.next()
        }
        return out
    }

    private fun readWorkbook(input: InputStream): List<Pair<String, String>> {
        val p = newParser(input)
        val out = mutableListOf<Pair<String, String>>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: "Sheet${out.size + 1}"
                val rid = p.getAttributeValue(null, "r:id").orEmpty()
                out += name to rid
            }
            event = p.next()
        }
        return out
    }

    private fun readSheet(name: String, input: InputStream, shared: List<String>): Sheet {
        val p = newParser(input)
        val cells = HashMap<Long, String>()
        var maxRow = -1
        var maxCol = -1
        var curRow = -1
        var curCol = -1
        var type: String? = null
        val value = StringBuilder()
        var capture = false
        var truncated = false

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        curRow = p.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (curRow + 1)
                        curCol = -1
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        curCol = ref?.let { colIndex(it) } ?: (curCol + 1)
                        type = p.getAttributeValue(null, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "str", "inlineStr", "e" -> raw
                            else -> formatNumber(raw)
                        }
                        if (text.isNotEmpty()) {
                            if (curRow in 0 until MAX_ROWS && curCol in 0 until MAX_COLS) {
                                cells[(curRow.toLong() shl 20) or curCol.toLong()] = text
                                if (curRow > maxRow) maxRow = curRow
                                if (curCol > maxCol) maxCol = curCol
                            } else {
                                truncated = true
                            }
                        }
                    }
                }
            }
            event = p.next()
        }

        val rows = (0..maxRow).map { r ->
            (0..maxCol).map { c -> cells[(r.toLong() shl 20) or c.toLong()].orEmpty() }
        }
        return Sheet(name, rows, truncated)
    }

    /** "BC12" -> 54 (zero-based). */
    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    private fun formatNumber(raw: String): String {
        val d = raw.trim().toDoubleOrNull() ?: return raw
        if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }
}

fun columnName(index: Int): String {
    var i = index + 1
    val sb = StringBuilder()
    while (i > 0) {
        val rem = (i - 1) % 26
        sb.insert(0, 'A' + rem)
        i = (i - 1) / 26
    }
    return sb.toString()
}
