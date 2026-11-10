package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PathsTest {
    @Test fun plainPath() {
        assertEquals("MyNote", Paths.stem("/storage/emulated/0/Documents/MyNote.pnotes"))
    }

    @Test fun safExternalStorageUri() {
        // content://.../document/primary%3ADocuments%2FMyNote.pnotes
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FMyNote.pnotes"
        assertEquals("MyNote", Paths.stem(uri))
    }

    @Test fun safEncodedSpaces() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADocs%2FMy%20Lecture%20Notes.pnotes"
        assertEquals("My Lecture Notes", Paths.stem(uri))
    }

    @Test fun documentIdSeparatorStripped() {
        // A bare "primary:Documents" doc id reduces to its trailing segment.
        assertEquals("Documents", Paths.baseName("primary%3ADocuments"))
    }

    @Test fun bareName() {
        assertEquals("Note", Paths.stem("Note.pnotes"))
    }
}
