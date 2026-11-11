package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentKindTest {

    @Test fun newExtensionsResolve() {
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("Plan.pnote"))
        assertEquals(DocumentKind.CANVAS, DocumentKind.ofName("Board.pcanva"))
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("PLAN.PNOTE"))
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
        // The mistaken .pnotes plural was never shipped: it stays unknown.
        assertNull(DocumentKind.ofName("Plan.pnotes"))
        assertEquals("Plan.pdf", DocumentKind.stripSuffix("Plan.pdf"))
    }

    @Test fun withSuffixWritesNewExtensions() {
        assertEquals("Plan.pnote", DocumentKind.withSuffix("Plan", DocumentKind.NOTE))
        assertEquals("Board.pcanva", DocumentKind.withSuffix("Board", DocumentKind.CANVAS))
        // Already suffixed is left alone; a legacy suffix of the same kind migrates.
        assertEquals("Plan.pnote", DocumentKind.withSuffix("Plan.pnote", DocumentKind.NOTE))
        assertEquals("Plan.pnote", DocumentKind.withSuffix("Plan.xnote", DocumentKind.NOTE))
        assertEquals("Board.pcanva", DocumentKind.withSuffix("Board.xcanvas", DocumentKind.CANVAS))
    }
}
