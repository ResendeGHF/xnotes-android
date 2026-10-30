package com.xnotes.sdocx

import com.xnotes.core.model.PagePattern
import com.xnotes.core.tools.Tool

/**
 * Maps Samsung pen names to xnotes tools and Samsung page templates
 * to xnotes page patterns. Ports `sdocx_mapping.dart` from the Saber fork.
 */
object SdocxMapping {
    fun samsungPenToTool(penName: String?, colorArgb: Int? = null): Tool {
        val short = (penName ?: "").trim()
        return when (short) {
            "FountainPen", "MontblancFountainPen" -> Tool.PEN
            "MontblancCalligraphyPen", "ChineseBrush", "ObliquePen" -> Tool.CALLIGRAPHY
            "Pencil", "Pencil2", "Pencil3", "ColoredPencil" -> Tool.PEN
            "Marker", "Marker2", "Marker3", "Marker4", "StraightMarker", "StraightHighlighter" -> Tool.HIGHLIGHTER
            "InkPen", "InkPen2", "StraightInkPen2", "DefaultPen" -> Tool.PEN
            else -> {
                val alpha = if (colorArgb == null) 255 else (colorArgb ushr 24) and 0xFF
                if (alpha < 200) Tool.HIGHLIGHTER else Tool.PEN
            }
        }
    }

    /** Samsung template -> xnotes pattern id ("none", "lines", "grid", "dots"). */
    fun samsungTemplateToPattern(templateType: Int?, templateUri: String?): String {
        val uri = (templateUri ?: "").lowercase()
        if (uri.isNotEmpty()) {
            if (uri.contains("grid")) return PagePattern.GRID.id
            if (uri.contains("dot")) return PagePattern.DOTS.id
            if (uri.contains("line") || uri.contains("ruled") || uri.contains("lined") || uri.contains("college")) return PagePattern.LINES.id
            if (uri.contains("blank") || uri.contains("none") || uri.contains("plain")) return PagePattern.NONE.id
            return PagePattern.NONE.id
        }
        return when (templateType) {
            1, 2, 3 -> PagePattern.LINES.id
            4, 5, 6 -> PagePattern.GRID.id
            7, 8, 9 -> PagePattern.DOTS.id
            else -> PagePattern.NONE.id
        }
    }

    /** xnotes tool -> Samsung pen short name for export. */
    fun toolToSamsungPen(tool: Tool): String = when (tool) {
        Tool.HIGHLIGHTER -> "Marker4"
        Tool.CALLIGRAPHY -> "ObliquePen"
        Tool.TAPER -> "FountainPen"
        else -> "InkPen2"
    }

    /** Samsung paragraph alignment (0=left, 1=right, 2=center, else left) -> xnotes. */
    fun samsungAlignToHAlign(align: Int): com.xnotes.core.pal.HAlign = when (align) {
        1 -> com.xnotes.core.pal.HAlign.RIGHT
        2 -> com.xnotes.core.pal.HAlign.CENTER
        else -> com.xnotes.core.pal.HAlign.LEFT
    }

    /** xnotes pattern id -> Samsung numeric template type for export (null = blank, omit). */
    fun patternToSamsungTemplate(patternId: String?): Int? = when (patternId) {
        PagePattern.LINES.id -> 1
        PagePattern.GRID.id -> 4
        PagePattern.DOTS.id -> 7
        else -> null
    }
}
