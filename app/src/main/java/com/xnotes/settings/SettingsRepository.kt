package com.xnotes.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.platform.JsonStore

/** Loads/saves [Settings] via the atomic, failure-tolerant [JsonStore]. */
class SettingsRepository(context: Context) {
    private val store = JsonStore.settings(context.applicationContext)

    fun load(): Settings = Settings.fromJson(store.read())

    fun save(settings: Settings) = store.write(settings.toJson())
}

/**
 * The one live [Settings] for the process. A split view runs an editor per pane and both of them
 * read and write preferences, so they have to share a single copy: with a copy each, whichever pane
 * saved last would write its own stale view over the other pane's change.
 *
 * The explorer root is also a Compose state shared by every tab: switching the folder in one tab has
 * to move the others (and the PDF scan) with it, or two tabs would show different libraries.
 */
object LiveSettings {

    @Volatile private var value: Settings? = null

    /** The shared explorer root, observed by every tab. Mirrors [Settings.browseRoot]. */
    private var browseRootState by mutableStateOf<String?>(null)
    private var browseRootPrimed = false

    /**
     * Load the settings (and the shared root) before the first composition, so reading the root
     * never has to write Compose state from inside a composition.
     */
    fun prime(repo: SettingsRepository) {
        val loaded = get(repo)
        browseRootState = loaded.browseRoot
        browseRootPrimed = true
    }

    /** The current settings, loaded through [repo] the first time anything asks for them. */
    fun get(repo: SettingsRepository): Settings = value ?: repo.load().also { value = it }

    fun set(settings: Settings) {
        value = settings
        if (browseRootPrimed && browseRootState != settings.browseRoot) {
            browseRootState = settings.browseRoot
        }
    }

    /** The shared explorer root; a fallback load for callers that did not [prime] first. */
    fun browseRoot(repo: SettingsRepository): String? {
        if (!browseRootPrimed) prime(repo)
        return browseRootState
    }
}
