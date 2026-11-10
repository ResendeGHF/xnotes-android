package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentKindTest {

    @Test fun newExtensionsResolve() {
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("Plan.pnotes"))
        assertEquals(DocumentKind.CANVAS, DocumentKind.ofName("Board.pcanvas"))
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("PLAN.PNOTES"))
    }

    @Test fun legacyExtensionsStillResolve() {
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("Plan.xnote"))
        assertEquals(DocumentKind.CANVAS, DocumentKind.ofName("Board.xcanvas"))
        assertEquals("Plan", DocumentKind.stripSuffix("Plan.xnote"))
        assertEquals("Board", DocumentKind.stripSuffix("Board.xcanvas"))
    }

    @Test fun unknownNamesAreNotDocuments() {
        assertNull(DocumentKind.ofName("Plan.pdf"))
        assertNull(DocumentKind.ofName("notes.txt"))
        assertEquals("Plan.pdf", DocumentKind.stripSuffix("Plan.pdf"))
    }

    @Test fun withSuffixWritesNewExtensions() {
        assertEquals("Plan.pnotes", DocumentKind.withSuffix("Plan", DocumentKind.NOTE))
        assertEquals("Board.pcanvas", DocumentKind.withSuffix("Board", DocumentKind.CANVAS))
        // Already suffixed is left alone; a legacy suffix of the same kind migrates.
        assertEquals("Plan.pnotes", DocumentKind.withSuffix("Plan.pnotes", DocumentKind.NOTE))
        assertEquals("Plan.pnotes", DocumentKind.withSuffix("Plan.xnote", DocumentKind.NOTE))
        assertEquals("Board.pcanvas", DocumentKind.withSuffix("Board.xcanvas", DocumentKind.CANVAS))
    }
}
