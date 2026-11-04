package com.xnotes.editor

import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.FakeUiScheduler
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.PdfTextController
import com.xnotes.canvas.PdfTextSource
import com.xnotes.canvas.TestPalette
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.pdf.FakePageText
import com.xnotes.core.pdf.PageText
import com.xnotes.core.pdf.TextPos
import com.xnotes.core.pdf.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkTextTest {
    private val text = FakePageText().line("hello world", 10f, 20f).build()

    private val state = CanvasState(
        Document(mutableListOf(Page(300.0, 200.0, pdfPage = 0)), dpi = 72), FakeSurfaceFactory(), TestPalette(),
    ).apply {
        viewportW = 800
        viewportH = 1000
        relayout()
    }
    private val history = History()
    private val controller = InteractionController(state, history, FakeTextMeasurer(), requestRender = {}, scheduler = FakeUiScheduler())
    private val editor = NoteEditor(state, history, controller, FakeTextMeasurer(), object : NoteEditorHost {
        override fun requestRender() = Unit
        override fun contentChanged() = Unit
        override fun viewChanged() = Unit
        override fun notice(notice: EditorNotice) = Unit
    })

    private val source = object : PdfTextSource {
        override fun peek(page: Int): PageText? = if (page == 0) text else null
        override fun prefetch(page: Int) = Unit
        override fun request(page: Int, onReady: (PageText?) -> Unit) = onReady(peek(page))
    }
    private val texts = PdfTextController(state, source, onViewChanged = {}, requestRender = {}, scheduler = FakeUiScheduler())

    private val markups get() = state.document.pages[0].markups

    @Test fun markingTheSelectedWordMakesOneMarkupWithItsTextAndUndoesIt() {
        val sel = TextSelection(TextPos(0, 0), TextPos(0, 5))
        editor.markText(texts, sel, MarkupType.UNDERLINE) { source.peek(it) }

        assertEquals(1, markups.size)
        assertEquals(MarkupType.UNDERLINE, markups[0].type)
        assertEquals("hello", markups[0].text)
        assertTrue(markups[0].quads.isNotEmpty())

        editor.undo()
        assertEquals(0, markups.size)
    }

    @Test fun markingTextWithNoPdfTextMakesNothing() {
        editor.markText(texts, TextSelection(TextPos(1, 0), TextPos(1, 3)), MarkupType.HIGHLIGHT) { source.peek(it) }
        assertEquals(0, markups.size)
    }
}
