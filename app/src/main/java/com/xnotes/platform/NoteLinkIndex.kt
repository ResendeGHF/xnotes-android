package com.xnotes.platform

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The note-link index behind the Graph screen: which files link to which, kept
 * current on every save (see `Editor.indexLinks`) and on renames/moves/deletes.
 * Mirrors the Dart `NoteLinksDatabase` behind the reference graph view: same
 * adjacency role, keyed by SAF document URI instead of a plain path.
 *
 * Each row is one outgoing link: the [sourceUri] file's page [sourcePage] links
 * [targetPages] of [targetUri]. [colorArgb] packs the button color as 0xAARRGGBB.
 * [scanMtimes] remembers each scanned file's `modified` time so the graph only
 * re-reads files that changed since its last scan.
 */
class NoteLinkIndex(context: Context) : SQLiteOpenHelper(context, "note_links.db", null, 2) {

    /** One outgoing link row. */
    data class Edge(
        val sourceUri: String,
        val sourcePage: Int,
        val targetUri: String,
        val targetName: String,
        val targetPages: List<Int>,
        val label: String,
        val colorArgb: Int,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE links (
            source_uri TEXT NOT NULL,
            source_name TEXT NOT NULL,
            source_page INTEGER NOT NULL,
            target_uri TEXT NOT NULL,
            target_name TEXT NOT NULL,
            target_pages TEXT NOT NULL,
            label TEXT NOT NULL,
            color INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX idx_links_source ON links (source_uri)")
        db.execSQL("CREATE INDEX idx_links_target ON links (target_uri)")
        db.execSQL("CREATE TABLE files (uri TEXT PRIMARY KEY, name TEXT NOT NULL, mtime INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2 folds the scan bookkeeping into a files table that also remembers
            // display names, so the graph paints instantly without any folder walk.
            // The data is a rebuildable cache: backfill what the links know and let
            // the next scan fill in modification times (unknown = rescan once).
            db.execSQL("CREATE TABLE files (uri TEXT PRIMARY KEY, name TEXT NOT NULL, mtime INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO files (uri, name, mtime) SELECT DISTINCT source_uri, source_name, -1 FROM links")
            db.execSQL("DROP TABLE IF EXISTS scan")
        }
    }

    /** Replace every outgoing link of [sourceUri] with [links] (a save with no links clears it). */
    fun setLinks(sourceUri: String, sourceName: String, links: List<com.xnotes.core.links.ParsedLink>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("links", "source_uri = ?", arrayOf(sourceUri))
            // A fresh row scans once (mtime unknown); an existing row keeps its mtime.
            val file = ContentValues().apply {
                put("uri", sourceUri)
                put("name", sourceName)
                put("mtime", -1L)
            }
            writableDatabase.insertWithOnConflict("files", null, file, SQLiteDatabase.CONFLICT_IGNORE)
            for (link in links) {
                val values = ContentValues().apply {
                    put("source_uri", sourceUri)
                    put("source_name", sourceName)
                    put("source_page", link.sourcePage)
                    put("target_uri", link.targetUri)
                    put("target_name", link.targetName)
                    put("target_pages", link.targetPages.joinToString(","))
                    put("label", link.label)
                    put("color", link.colorArgb)
                }
                writableDatabase.insert("links", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Every known outgoing link, grouped by source URI. */
    fun allEdges(): Map<String, List<Edge>> {
        val out = HashMap<String, MutableList<Edge>>()
        readableDatabase.rawQuery(
            "SELECT source_uri, source_page, target_uri, target_name, target_pages, label, color FROM links",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val source = c.getString(0) ?: continue
                val edge = Edge(
                    sourceUri = source,
                    sourcePage = c.getInt(1),
                    targetUri = c.getString(2).orEmpty(),
                    targetName = c.getString(3).orEmpty(),
                    targetPages = c.getString(4).orEmpty().split(",").mapNotNull { it.toIntOrNull() },
                    label = c.getString(5).orEmpty(),
                    colorArgb = c.getInt(6),
                )
                out.getOrPut(source) { ArrayList() }.add(edge)
            }
        }
        return out
    }


    /** Carry every row mentioning [oldUri] (both ends) over to [newUri] after a rename or move. */
    fun remap(oldUri: String, newUri: String, newName: String?) {
        if (oldUri == newUri) return
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE links SET source_uri = ? WHERE source_uri = ?", arrayOf(newUri, oldUri))
            if (newName != null) {
                writableDatabase.execSQL(
                    "UPDATE links SET source_name = ? WHERE source_uri = ?",
                    arrayOf(newName, newUri),
                )
            }
            writableDatabase.execSQL("UPDATE links SET target_uri = ? WHERE target_uri = ?", arrayOf(newUri, oldUri))
            if (newName != null) {
                writableDatabase.execSQL(
                    "UPDATE links SET target_name = ? WHERE target_uri = ?",
                    arrayOf(newName, newUri),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** Drop every row mentioning [uri] (a permanently deleted file). */
    fun remove(uri: String) {
        writableDatabase.delete("links", "source_uri = ? OR target_uri = ?", arrayOf(uri, uri))
        writableDatabase.delete("files", "uri = ?", arrayOf(uri))
    }

    /**
     * Drop rows for files no longer listed (deleted elsewhere, trashed): keeps the
     * graph truthful. Only sources are pruned — a target that is missing today may
     * be created tomorrow, and its rows come back with its source's next rescan.
     */
    fun prune(knownUris: Set<String>) {
        val current = HashSet<String>()
        readableDatabase.rawQuery("SELECT uri FROM files", null).use { c ->
            while (c.moveToNext()) current.add(c.getString(0).orEmpty())
        }
        for (uri in current) {
            if (uri !in knownUris) {
                writableDatabase.delete("links", "source_uri = ?", arrayOf(uri))
                writableDatabase.delete("files", "uri = ?", arrayOf(uri))
            }
        }
    }

    fun scanMtime(uri: String): Long? =
        readableDatabase.rawQuery("SELECT mtime FROM files WHERE uri = ?", arrayOf(uri)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    fun setScanMtime(uri: String, mtime: Long) {
        writableDatabase.execSQL("UPDATE files SET mtime = ? WHERE uri = ?", arrayOf(mtime.toString(), uri))
    }

    /** Remember [uri]'s display [name] (upserts the file row, leaves its mtime alone). */
    fun touchFile(uri: String, name: String) {
        val values = ContentValues().apply {
            put("uri", uri)
            put("name", name)
            put("mtime", -1L)
        }
        if (writableDatabase.insertWithOnConflict("files", null, values, SQLiteDatabase.CONFLICT_IGNORE) < 0) {
            writableDatabase.execSQL("UPDATE files SET name = ? WHERE uri = ?", arrayOf(name, uri))
        }
    }

    /** Drop the scan mark so the next graph pass re-reads the file (used right after a save). */
    fun markDirty(uri: String) {
        writableDatabase.execSQL("UPDATE files SET mtime = -1 WHERE uri = ?", arrayOf(uri))
    }

    /** Every known file, for an instant first paint without any folder walk. */
    data class FileEntry(val uri: String, val name: String)

    fun allFiles(): List<FileEntry> =
        readableDatabase.rawQuery("SELECT uri, name FROM files ORDER BY name COLLATE NOCASE", null).use { c ->
            val out = ArrayList<FileEntry>(c.count)
            while (c.moveToNext()) out.add(FileEntry(c.getString(0) ?: continue, c.getString(1).orEmpty()))
            out
        }
}
