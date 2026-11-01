package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.model.TableItem
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Quote a CSV field only when it needs it, doubling embedded quotes. */
private fun csvField(s: String): String {
    val needs = s.contains(',') || s.contains('"') || s.contains('\n') ||
        s.startsWith(' ') || s.endsWith(' ') || s.startsWith('\t') || s.endsWith('\t')
    return if (!needs) s else "\"" + s.replace("\"", "\"\"") + "\""
}

/** A cell grid as comma-separated values, padded to a rectangle. */
internal fun tableGridToCsv(grid: List<List<String>>): String {
    val w = grid.maxOfOrNull { it.size } ?: 0
    if (w == 0) return ""
    return grid.joinToString("\n") { row ->
        List(w) { row.getOrElse(it) { "" } }.joinToString(",") { csvField(it) }
    }
}

/** A cell grid as a LaTeX tabular with rules. */
internal fun tableGridToLatex(grid: List<List<String>>): String {
    val w = grid.maxOfOrNull { it.size } ?: 0
    if (w == 0) return ""
    val cols = List(w) { "c" }.joinToString("|", "|", "|")
    val body = grid.joinToString(" \\\\\n\\hline\n") { row ->
        List(w) { row.getOrElse(it) { "" }.replace("&", "\\&") }.joinToString(" & ") + " \\\\"
    }
    return "\\begin{tabular}{$cols}\n\\hline\n$body\n\\hline\n\\end{tabular}"
}

/** A cell grid as a GitHub-style Markdown table. */
internal fun tableGridToMarkdown(grid: List<List<String>>): String {
    val w = grid.maxOfOrNull { it.size } ?: 0
    if (w == 0 || grid.isEmpty()) return ""
    fun rowCells(row: List<String>) = List(w) { row.getOrElse(it) { "" }.replace("|", "\\|") }
    val head = "| " + rowCells(grid[0]).joinToString(" | ") + " |"
    val rule = "| " + List(w) { "---" }.joinToString(" | ") + " |"
    val body = grid.drop(1).map { "| " + rowCells(it).joinToString(" | ") + " |" }
    return (listOf(head, rule) + body).joinToString("\n")
}

/** Strip balanced outer parentheses, repeatedly: "((1)/(2))" reads as "(1)/(2)". */
internal fun stripOuterParens(s: String): String {
    var t = s.trim()
    while (t.length >= 2 && t.startsWith("(") && matchingClose(t, 0) == t.length - 1) {
        t = t.substring(1, t.length - 1).trim()
    }
    return t
}

private fun matchingClose(s: String, open: Int): Int {
    var depth = 0
    for (i in open until s.length) {
        when (s[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return -1
}

/**
 * Assign strokes to the cell holding each stroke's centre. Strokes outside the grid are dropped,
 * so stray ink never lands in a cell by accident.
 */
internal fun assignCells(table: com.xnotes.core.model.TableItem, strokes: List<com.xnotes.core.model.Stroke>): Map<Pair<Int, Int>, List<com.xnotes.core.model.Stroke>> {
    val out = LinkedHashMap<Pair<Int, Int>, MutableList<com.xnotes.core.model.Stroke>>()
    val bounds = table.rect
    for (s in strokes) {
        val c = s.bounds().center
        if (!c.x.isFinite() || !c.y.isFinite()) continue
        if (c.x < bounds.left || c.x > bounds.right || c.y < bounds.top || c.y > bounds.bottom) continue
        val fc = (0 until table.cols).firstOrNull { c.x <= table.colX(it + 1) } ?: (table.cols - 1)
        val fr = (0 until table.rows).firstOrNull { c.y <= table.rowY(it + 1) } ?: (table.rows - 1)
        out.getOrPut(fc to fr) { ArrayList() }.add(s)
    }
    return out
}

/** Split one line on comma (quote-aware), semicolon, tab, or whitespace runs, in that order. */
private fun splitTableLine(line: String): List<String> {
    if (',' in line) {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ } else inQuotes = false
                } else cur.append(c)
            } else if (c == '"') inQuotes = true
            else if (c == ',') { out.add(cur.toString()); cur.clear() }
            else cur.append(c)
            i++
        }
        out.add(cur.toString())
        return out
    }
    if (';' in line) return line.split(';')
    if ('\t' in line) return line.split('\t')
    return line.trim().split(Regex("\\s+"))
}

/** CSV-ish text to rows; blank and `#` comment lines are skipped. */
internal fun parseTableCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        rows += splitTableLine(line)
    }
    return rows
}

private fun parsePlainNumber(s: String): Double? {
    var t = s.replace(" ", "").replace("'", "").replace(" ", "")
    if (t.isEmpty()) return null
    if ('.' in t) {
        // Dots are decimal points; commas can only be thousand separators.
        t = t.replace(",", "")
    } else if (t.count { it == ',' } == 1) {
        val before = t.substringBefore(',')
        val after = t.substringAfter(',')
        t = if (after.length == 3 && before.length in 1..3 &&
            before.all { it.isDigit() } && after.all { it.isDigit() }
        ) {
            before + after // thousands: 1,000
        } else {
            t.replace(',', '.') // decimal comma: 1,5
        }
    } else {
        t = t.replace(",", "")
    }
    // More than one dot is never a number.
    if (t.count { it == '.' } > 1) return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/**
 * A cell's number: decimals, decimal commas, thousands separators, `a/b` fractions
 * (including `1 1/2`), and trailing `%`. Null when it is not a number.
 */
internal fun parseTableNumber(raw: String): Double? {
    var s = stripOuterParens(raw)
    if (s.isEmpty()) return null
    var pct = false
    if (s.endsWith("%")) {
        pct = true
        s = s.dropLast(1).trim()
    }
    val slash = s.indexOf('/')
    if (slash > 0) {
        val numPart = stripOuterParens(s.substring(0, slash).trim())
        val denPart = stripOuterParens(s.substring(slash + 1).trim())
        val den = parsePlainNumber(denPart)
        if (den == null || den == 0.0) return null
        val numBits = numPart.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val v = when (numBits.size) {
            1 -> {
                val num = parsePlainNumber(numBits[0]) ?: return null
                num / den
            }
            // Mixed number: whole + num/den.
            2 -> {
                val whole = parsePlainNumber(numBits[0]) ?: return null
                val num = parsePlainNumber(numBits[1]) ?: return null
                whole + num / den
            }
            else -> return null
        }
        return if (pct) v / 100.0 else v
    }
    val v = parsePlainNumber(s) ?: return null
    return if (pct) v / 100.0 else v
}

private fun copyPlainText(context: Context, label: String, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    true
}.getOrDefault(false)

/**
 * Edit a canvas table's cells as text, resize the grid, copy it out as CSV / LaTeX / Markdown,
 * or send it to the Data lab. Saving replaces the whole grid as one undoable edit.
 */
@Composable
internal fun TableCellEditor(
    table: TableItem,
    onConfirm: (List<List<String>>) -> Unit,
    onSendToData: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var grid by remember(table) {
        mutableStateOf(table.cellGrid().map { it.toMutableList() }.toMutableList())
    }
    fun setCell(c: Int, r: Int, v: String) {
        val next = grid.map { it.toMutableList() }.toMutableList()
        while (next.size <= r) next.add(MutableList(next.firstOrNull()?.size ?: 1) { "" })
        val row = next[r]
        while (row.size <= c) row.add("")
        row[c] = v
        grid = next
    }
    val cols = grid.maxOfOrNull { it.size } ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Table cells", color = palette.text.toComposeColor()) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val next = grid.map { it.toMutableList() }.toMutableList()
                        next.add(MutableList(cols.coerceAtLeast(1)) { "" })
                        grid = next.takeLast(64).toMutableList()
                    }) { Text("+ row", fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (grid.size > 1) grid = grid.dropLast(1).map { it.toMutableList() }.toMutableList() },
                        enabled = grid.size > 1,
                    ) { Text("− row", fontSize = 12.sp) }
                    TextButton(onClick = {
                        if ((grid.maxOfOrNull { it.size } ?: 0) < 64)
                            grid = grid.map { (it + listOf("")).toMutableList() }.toMutableList()
                    }) { Text("+ col", fontSize = 12.sp) }
                    TextButton(
                        onClick = { if (cols > 1) grid = grid.map { it.dropLast(1).toMutableList() }.toMutableList() },
                        enabled = cols > 1,
                    ) { Text("− col", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${grid.size} × $cols — numbers, decimals or fractions (1/2, 1 1/2, 50%)",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(grid, key = { index, _ -> index }) { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in row.indices) {
                                OutlinedTextField(
                                    value = row[c],
                                    onValueChange = { setCell(c, r, it) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = palette.text.toComposeColor(),
                                    ),
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Export", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val frozen = grid.map { it.toList() }
                    TextButton(onClick = {
                        if (copyPlainText(context, "Table CSV", tableGridToCsv(frozen)))
                            Toast.makeText(context, "Copied CSV", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy CSV", fontSize = 12.sp) }
                    TextButton(onClick = {
                        if (copyPlainText(context, "Table LaTeX", tableGridToLatex(frozen)))
                            Toast.makeText(context, "Copied LaTeX", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy LaTeX", fontSize = 12.sp) }
                    TextButton(onClick = {
                        if (copyPlainText(context, "Table Markdown", tableGridToMarkdown(frozen)))
                            Toast.makeText(context, "Copied Markdown", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy Markdown", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = onSendToData, modifier = Modifier.fillMaxWidth()) {
                    Text("Send to Data lab", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(grid.map { it.toList() }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
