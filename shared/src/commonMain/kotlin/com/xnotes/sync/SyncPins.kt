package com.xnotes.sync

import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.withLock
import okio.FileSystem
import okio.Path

/**
 * Persistently pinned companion/host certificate fingerprints:
 * `{"pins": [{"name": …, "fp": …}]}`. Tmp+rename writes, so a crash never
 * half-writes. Same shape as the Android `CompanionPins`.
 */
class SyncPins(dir: File) {
    data class Pin(val name: String, val fp: String)

    private val lock = Lock()
    private val fs: FileSystem = FileSystem.SYSTEM
    private val file: Path = dir.asPath() / FILE_NAME
    private var pins: LinkedHashMap<String, Pin> = LinkedHashMap()

    init {
        withLock(lock) { reloadLocked() }
    }

    /** Fingerprint pinned under [name], or null. */
    fun fpFor(name: String): String? = withLock(lock) { pins[name]?.fp }

    /** Name pinned for [fp], or null. */
    fun nameFor(fp: String): String? = withLock(lock) { pins.values.firstOrNull { it.fp == fp }?.name }

    /** Trust [fp] as [name] (re-pinning replaces). Persists immediately. */
    fun pin(name: String, fp: String) {
        withLock(lock) {
            pins[name] = Pin(name, fp.lowercase())
            saveLocked()
        }
    }

    /** Forget [name]. True when something was removed. */
    fun unpin(name: String): Boolean = withLock(lock) {
        (pins.remove(name) != null).also { if (it) saveLocked() }
    }

    fun all(): List<Pin> = withLock(lock) { pins.values.toList() }

    private fun reloadLocked() {
        pins = LinkedHashMap()
        if (!fs.exists(file)) return
        try {
            val text = fs.read(file) { readUtf8() }
            // Tiny JSON, parsed by hand: {"pins": [{"name": …, "fp": …}]}.
            val names = ArrayList<String>()
            val fps = ArrayList<String>()
            var i = 0
            while (true) {
                i = text.indexOf("\"name\"", i)
                if (i < 0) break
                quoted(text, text.indexOf(':', i) + 1)?.let { names += it } ?: break
                i = text.indexOf("\"fp\"", i)
                if (i < 0) break
                quoted(text, text.indexOf(':', i) + 1)?.let { fps += it } ?: break
            }
            for (k in names.indices) {
                if (k >= fps.size) break
                val fp = fps[k].lowercase()
                if (names[k].isNotEmpty() && fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }) {
                    pins[names[k]] = Pin(names[k], fp)
                }
            }
        } catch (_: Exception) {
            pins = LinkedHashMap()
        }
    }

    private fun quoted(text: String, from: Int): String? {
        val open = text.indexOf('"', from)
        if (open < 0) return null
        val sb = StringBuilder()
        var i = open + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                sb.append(text[i + 1])
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun escaped(s: String): String = buildString {
        for (c in s) {
            if (c == '"' || c == '\\') append('\\')
            append(c)
        }
    }

    private fun saveLocked() {
        val sb = StringBuilder("{\"pins\": [")
        pins.values.forEachIndexed { i, p ->
            if (i > 0) sb.append(", ")
            sb.append("{\"name\": \"").append(escaped(p.name)).append("\", \"fp\": \"").append(p.fp).append("\"}")
        }
        sb.append("]}")
        try {
            val parent = file.parent ?: return
            fs.createDirectories(parent)
            val tmp = parent / "$FILE_NAME.tmp"
            fs.write(tmp) { writeUtf8(sb.toString()) }
            fs.atomicMove(tmp, file)
        } catch (_: Exception) {
            Unit
        }
    }

    companion object {
        const val FILE_NAME = "companion_pins.json"
    }
}
