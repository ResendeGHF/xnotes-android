package com.xnotes.core.util

import com.xnotes.core.util.ExportZip.Entry
import com.xnotes.core.util.ExportZip.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportZipTest {

    private fun doc(path: String) = Node(path.substringAfterLast('/'), path, "uri:$path", isDir = false)

    private fun dir(path: String, vararg children: Node) =
        Node(path.substringAfterLast('/'), path, "uri:$path", isDir = true, children = children.toList())

    private fun pdf(path: String, source: String) = Entry(path, "uri:$source", source.substringAfterLast('/'))

    private fun folder(path: String) = Entry(path, null, path.trimEnd('/').substringAfterLast('/'))

    @Test
    fun `picked documents land at the top as PDFs`() {
        assertEquals(
            listOf(pdf("Board.pdf", "a/Board.pcanvas"), pdf("Lecture 1.pdf", "b/Lecture 1.pnotes")),
            ExportZip.plan(listOf(doc("b/Lecture 1.pnotes"), doc("a/Board.pcanvas"))),
        )
    }

    @Test
    fun `a picked folder keeps its tree, empty folders included`() {
        val school = dir(
            "School",
            doc("School/Timetable.pnotes"),
            dir("School/Maths", doc("School/Maths/Algebra.pnotes"), dir("School/Maths/Old")),
            dir("School/Art"),
        )
        assertEquals(
            listOf(
                folder("School/"),
                folder("School/Art/"),
                folder("School/Maths/"),
                folder("School/Maths/Old/"),
                pdf("School/Maths/Algebra.pdf", "School/Maths/Algebra.pnotes"),
                pdf("School/Timetable.pdf", "School/Timetable.pnotes"),
            ),
            ExportZip.plan(listOf(school)),
        )
    }

    @Test
    fun `clashing names are numbered, ignoring case`() {
        val plan = ExportZip.plan(
            listOf(
                dir(
                    "Notes",
                    doc("Notes/plan.pnotes"),
                    doc("Notes/Plan.pcanvas"),
                    doc("Notes/plan (2).pnotes"),
                    dir("Notes/plan.pdf"),
                ),
                doc("x/Notes.pnotes"),
                dir("y/Notes"),
            ),
        )
        val paths = plan.map { it.path }
        assertEquals(paths.size, paths.map { it.lowercase() }.toSet().size)
        assertTrue("Notes/" in paths && "Notes (2)/" in paths && "Notes.pdf" in paths)
        assertEquals(
            setOf("Notes/plan.pdf/", "Notes/plan (2).pdf", "Notes/Plan (3).pdf", "Notes/plan (4).pdf"),
            paths.filter { it.startsWith("Notes/") && it != "Notes/" }.toSet(),
        )
    }

    @Test
    fun `a pick inside another picked folder is exported only there`() {
        val inner = dir("Top/Inner", doc("Top/Inner/deep.pnotes"))
        val top = dir("Top", inner, doc("Top/note.pnotes"))
        val plan = ExportZip.plan(listOf(doc("Top/Inner/deep.pnotes"), inner, top))
        assertEquals(
            listOf(
                folder("Top/"),
                folder("Top/Inner/"),
                pdf("Top/Inner/deep.pdf", "Top/Inner/deep.pnotes"),
                pdf("Top/note.pdf", "Top/note.pnotes"),
            ),
            plan,
        )
    }

    @Test
    fun `the same pick twice is exported once`() {
        assertEquals(listOf(pdf("a.pdf", "a.pnotes")), ExportZip.plan(listOf(doc("a.pnotes"), doc("a.pnotes"))))
    }

    @Test
    fun `names are kept to one path segment`() {
        val plan = ExportZip.plan(
            listOf(
                Node("a/b\\c.pnotes", "k1", "s1", isDir = false),
                Node("..", "k2", "s2", isDir = true, children = listOf(Node("tab\there.pnotes", "k3", "s3", isDir = false))),
                Node(".pnotes", "k4", "s4", isDir = false),
            ),
        )
        assertEquals(
            listOf(
                Entry("_/", null, ".."),
                Entry("_/tab_here.pdf", "s3", "tab\there.pnotes"),
                Entry("_.pdf", "s4", ".pnotes"),
                Entry("a_b_c.pdf", "s1", "a/b\\c.pnotes"),
            ),
            plan,
        )
    }

    @Test
    fun `folders without documents plan no PDFs`() {
        val plan = ExportZip.plan(listOf(dir("Empty", dir("Empty/Also"))))
        assertEquals(listOf(folder("Empty/"), folder("Empty/Also/")), plan)
        assertTrue(plan.none { it.source != null })
    }
}
