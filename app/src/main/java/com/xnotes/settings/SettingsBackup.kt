package com.xnotes.settings

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Settings backups. Two forms share this object:
 *
 * - [export]/[import] write and read one portable zip: `settings.json` plus the user's imported
 *   fonts, templates, stickers and code theme. The math add-on is not in it (it downloads again);
 *   what only means something on this device (the chosen folder, recent files) is left out on
 *   export and kept on import.
 * - [portable]/[merged] form the JSON settings backup the editor shares, taking out what belongs to
 *   this device or its folders; an import takes on every key a backup holds and keeps the rest, so
 *   an older or newer backup still reads.
 */
object SettingsBackup {

    const val FORMAT = "xnotes-settings"
    const val VERSION = 1

    private const val SETTINGS_ENTRY = "settings.json"

    /** Folders under the app's files dir that travel with the settings. */
    private val ASSET_DIRS = listOf("fonts", "templates", "stamps", "theme")

    /** Settings about this device or its folders: the notes folder, pins, recents, per-folder views, window state. */
    private val DEVICE_SETTINGS = setOf(
        "browse_root", "sidebar_visible", "backstage_rail", "pinned_folders", "folder_views", "recent_docs",
        "last_folder", "render_scale", "finger_draw_auto_checked",
    )

    /** The same among the preferences: performance switches, and paths to this device's own files. */
    private val DEVICE_PREFS = setOf(
        "max_cache_resolution", "disable_front_buffering", "start_fullscreen", "code_theme_path", "code_theme_name", "page_template_pdf",
    )

    private const val CODE_THEME = "theme/code.toml"

    /** [settings] as [Settings.toJson] wrote them, with what belongs to this device taken out. */
    fun portable(settings: JSONObject): JSONObject {
        val out = JSONObject(settings.toString())
        for (k in DEVICE_SETTINGS) out.remove(k)
        out.optJSONObject("prefs")?.let { p -> for (k in DEVICE_PREFS) p.remove(k) }
        return out
    }

    /** [current] with every key of [incoming] that may travel put in its place, preferences one by one. */
    fun merged(current: JSONObject, incoming: JSONObject): JSONObject {
        val out = JSONObject(current.toString())
        for (k in incoming.keys()) {
            if (k in DEVICE_SETTINGS) continue
            if (k == "prefs") {
                val mine = out.optJSONObject("prefs") ?: JSONObject()
                val theirs = incoming.optJSONObject("prefs") ?: continue
                for (pk in theirs.keys()) if (pk !in DEVICE_PREFS) mine.put(pk, theirs.get(pk))
                out.put("prefs", mine)
            } else {
                out.put(k, incoming.get(k))
            }
        }
        return out
    }

    /** Write [settings] and the asset folders under [filesDir] to [out] as a zip. */
    fun export(settings: JSONObject, filesDir: File, out: OutputStream) {
        val portableSettings = JSONObject(settings.toString())
        for (k in DEVICE_SETTINGS) portableSettings.remove(k)
        val theme = File(filesDir, CODE_THEME).takeIf { it.isFile }
        // The root is both the plain settings a simple reader takes and the wrapped form the
        // editor's newer backup reader expects, so either can read what the other wrote.
        val root = JSONObject(portableSettings.toString())
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("settings", JSONObject(portableSettings.toString()))
            .put("code_theme", theme != null)
            .apply {
                if (theme != null) settings.optJSONObject("prefs")
                    ?.optString("code_theme_name")?.ifEmpty { null }?.let { put("code_theme_name", it) }
            }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (dir in ASSET_DIRS) {
                val rootDir = File(filesDir, dir)
                rootDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("$dir/" + f.relativeTo(rootDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            // The editor's reader looks for this name at the root; keep it beside theme/code.toml.
            if (theme != null) {
                zip.putNextEntry(ZipEntry("code-theme.toml"))
                theme.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Read a zip made by [export], put its asset files under [filesDir] and return the settings to
     * adopt: the imported ones with [current]'s device-only keys kept. Null, with nothing written,
     * when [input] is not such a zip.
     */
    fun import(input: InputStream, current: JSONObject, filesDir: File): JSONObject? {
        val staged = HashMap<String, ByteArray>()
        var settings: JSONObject? = null
        var rootTheme: ByteArray? = null
        var themeName: String? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                val bytes = zip.readBytes()
                when {
                    name == SETTINGS_ENTRY -> settings = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    name == "code-theme.toml" -> rootTheme = bytes
                    safeAssetPath(name) -> staged[name] = bytes
                }
            }
        }
        val raw = settings ?: return null
        val wrapped = raw.optString("format") == FORMAT
        val incoming = if (wrapped) raw.optJSONObject("settings") ?: JSONObject() else raw
        if (wrapped) themeName = raw.optString("code_theme_name").ifEmpty { null }
        // An upstream zip keeps the theme at the root; put it where this device's settings point.
        if (rootTheme != null && CODE_THEME !in staged) staged[CODE_THEME] = rootTheme
        for ((name, bytes) in staged) {
            val file = File(filesDir, name)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        for (k in DEVICE_SETTINGS) if (current.has(k)) incoming.put(k, current.get(k)) else incoming.remove(k)
        // The code theme's path names the file on the device it came from; point it at the one just put down.
        incoming.optJSONObject("prefs")?.let { prefs ->
            if (CODE_THEME in staged) {
                prefs.put("code_theme_path", File(filesDir, CODE_THEME).path)
                themeName?.let { if (!prefs.has("code_theme_name")) prefs.put("code_theme_name", it) }
            } else {
                prefs.remove("code_theme_path")
                prefs.remove("code_theme_name")
            }
        }
        return incoming
    }

    /** Only files inside the travelling folders, never a path that climbs out of them. */
    private fun safeAssetPath(name: String): Boolean {
        val parts = name.split('/')
        return parts.size >= 2 && parts.first() in ASSET_DIRS && parts.none { it == ".." || it.isEmpty() }
    }
}
