package com.xnotes.sdocx

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.*
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.ShapeKind
import org.junit.Test
import java.io.File

// TEMPORARY diagnostic test: dumps a rich export to /tmp/sdocx-out/ for
// byte-level validation against real Samsung files. DELETE AFTER USE.
class SdocxDumpTest {
    private val fakeImages = object : ImageCodec {
        override fun probeFile(path: String): ImageSize? = ImageSize(100, 100)
    }
    private val fakeMeasurer = object : TextMeasurer {
        override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): Rect =
            Rect(0.0, 0.0, wrapWidth, 20.0)
        override fun lineHeight(font: FontSpec): Double = 20.0
        override fun metrics(font: FontSpec): LineMetrics = LineMetrics(16.0, 4.0)
        override fun advances(text: String, font: FontSpec): DoubleArray = DoubleArray(text.length) { 10.0 }
    }

    private fun stroke(tool: Tool, color: Rgba, w: Double, vararg pts: Double): Stroke {
        val s = Stroke(tool, ToolDefaults.configFor(tool).copy(baseWidth = w, rgba = color))
        val samples = ArrayList<Sample>()
        var i = 0
        while (i < pts.size) {
            samples.add(Sample(pts[i], pts[i + 1], 0.8))
            i += 2
        }
        s.setSamples(samples)
        s.finished = true
        return s
    }

    @Test fun dumpRichExport() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00.toByte(), 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F,
            0x00, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xDC.toByte(), 0xCC.toByte(), 0x59,
            0xE7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        val dir = File("/tmp/sdocx-out")
        dir.deleteRecursively()
        dir.mkdirs()
        val imgFile = File(dir, "src.png").apply { writeBytes(png) }
        val doc = Document(dpi = 150)
        doc.displayName = "MyTitle.xnote"
        val page = Page(1440.0, 2036.0)
        page.items.add(stroke(Tool.PEN, Rgba(20, 20, 20, 255), 3.0, 100.0, 100.0, 200.0, 200.0, 300.0, 150.0))
        page.items.add(stroke(Tool.HIGHLIGHTER, Rgba(255, 235, 59, 200), 16.0, 50.0, 500.0, 400.0, 520.0))
        page.items.add(stroke(Tool.PENCIL, Rgba(60, 60, 60, 255), 4.0, 600.0, 600.0, 700.0, 700.0))
        page.items.add(ImageItem(ImageData(imgFile, 1, 1), Rect(100.0, 100.0, 300.0, 300.0)))
        page.items.add(TextItem(Pt(100.0, 900.0), 600.0, 0.0, "Hello Samsung",
            Rgba(10, 20, 30, 255), 16.0, FontFace.SANS, fakeMeasurer,
            bold = true, align = HAlign.CENTER))
        val table = TableItem.create(Rect(100.0, 500.0, 400.0, 300.0), 2, 3, Rgba(0, 0, 0, 255), 2.0)
        table.setCell(0, 0, "A1")
        table.setCell(1, 2, "B3")
        page.items.add(table)
        page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(800.0, 200.0), Pt(1100.0, 400.0), Rgba(255, 0, 0, 255), 4.0))
        doc.pages.add(page)
        val out = File(dir, "export.sdocx")
        out.outputStream().use { SdocxExport.writeDocument(doc, it) }
        println("DUMPED ${out.length()} bytes to ${out.path}")
    }
}
