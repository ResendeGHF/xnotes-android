package com.xnotes.core.text

/**
 * Vision-model assisted tuning of the scan-to-strokes parameters: what to ask
 * for and how to read the answer. Pure Kotlin (no Android imports) so the
 * prompt contract and the parser are pinned by JVM unit tests; the HTTP call
 * itself is [com.xnotes.platform.VlmClient.askRaw] and only ever sends the
 * current page render to the provider the user configured in Settings.
 *
 * The model is shown the page photo side by side with the current edges
 * preview (the black-on-white ink mask the tracer skeletonizes), and answers
 * with a JSON object naming only the settings it wants changed. Every field
 * is optional: [TraceTune] carries null for anything the model left out, and
 * the dialog applies just the non-null fields (clamped to the sliders'
 * ranges). Automatic modes are never used: the model must always give
 * explicit threshold/brightness/contrast numbers (the dialog normalizes any
 * stray `true` back to manual), so a recipe stays exactly as prescribed.
 */
object VlmTraceTune {

    /** One tunable setting per nullable field; null means "leave it alone". */
    data class TraceTune(
        val autoThreshold: Boolean? = null,
        val threshold: Int? = null,
        val brightness: Int? = null,
        val contrast: Int? = null,
        val denoise: Int? = null,
        val removeLines: Boolean? = null,
        val printedText: Boolean? = null,
        val invert: Boolean? = null,
        val enhance: Boolean? = null,
        val quarterTurns: Int? = null,
    ) {
        fun isEmpty(): Boolean =
            autoThreshold == null && threshold == null && brightness == null &&
                contrast == null && denoise == null && removeLines == null &&
                printedText == null && invert == null && enhance == null &&
                quarterTurns == null
    }

    /**
     * The instruction sent with the photo + edges-preview image. It names the
     * exact JSON contract (keys, ranges, what each setting does) and asks for
     * JSON only, so the answer parses without a second model call. [current]
     * is a human-readable summary of the dialog's present settings.
     *
     * The two panels are labelled in the image itself: LEFT is "PHOTO" (the
     * scanned page), RIGHT is "EDGES" (black = kept ink, white = dropped).
     */
    fun promptFor(current: String): String =
        """You tune a document-trace filter. The LEFT panel ("PHOTO") is the scanned page; the RIGHT panel ("EDGES") is what the tracer currently extracts (black = kept ink, white = dropped).
Goal: EDGES must keep every text letter (including dots, accents, diacritics) and every illustration/diagram line, while dropping ruled-paper lines, speckles, shadows, punch holes and margin dirt.
Current settings: $current
Rules:
- Output ONLY a flat JSON object. No fences, no explanations, no commentary.
- REQUIRED keys (always include all three): "threshold" 1..254, "brightness" -100..100, "contrast" 50..200. Concrete numbers, tuned to THIS page.
- "auto_threshold" and "enhance" MUST be false or omitted. Never use automatic modes: give explicit values instead. Ink is darker than "threshold".
- Optional keys:
  "denoise": 0..60. Drops ink blobs smaller than this many pixels. Remove dust WITHOUT eating dots and accents.
  "remove_lines": boolean. True erases ruled-paper lines so handwriting traces continuously.
  "printed_text": boolean. True for typeset/printed pages (keeps neighbouring letters separate); false for handwriting (bridges faint pen lifts).
  "invert": boolean. True ONLY when the ink is LIGHTER than the page (whiteboard photo, dark paper).
  "quarter_turns": 0..3. Clockwise 90-degree steps to make the page upright (0 = already upright).
- When unsure whether a faint mark is content or noise, keep it.
- Example: {"threshold": 140, "brightness": 5, "contrast": 120, "denoise": 6, "remove_lines": true, "printed_text": false, "quarter_turns": 0}""".trimIndent()

    /**
     * Enforce the no-automatic-modes rule on a parsed [tune]: an explicit
     * `auto_threshold: true` becomes manual with [fallbackThreshold] (the
     * page's Otsu level, used only when the model gave no threshold), and an
     * explicit `enhance: true` becomes false. Omitted fields stay omitted, so
     * they keep following the dialog. Never throws.
     */
    fun normalizeManual(tune: TraceTune, fallbackThreshold: Int): TraceTune {
        var out = tune
        if (out.autoThreshold == true) {
            out = out.copy(autoThreshold = false, threshold = out.threshold ?: fallbackThreshold.coerceIn(1, 254))
        }
        if (out.enhance == true) out = out.copy(enhance = false)
        return out
    }

    /**
     * Read the model's answer into a [TraceTune], or null when it holds no
     * usable setting. Tolerates fences and surrounding prose: the first `{`
     * to the last `}` is the object, unknown keys are ignored, and every
     * value is clamped to its slider range. Never throws.
     */
    fun parseRecommendation(raw: String): TraceTune? {
        val body = runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            raw.substring(start, end + 1)
        }.getOrNull() ?: return null

        fun bool(key: String): Boolean? {
            val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(body) ?: return null
            return m.groupValues[1] == "true"
        }

        fun int(key: String, lo: Int, hi: Int): Int? {
            val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(body) ?: return null
            return m.groupValues[1].toIntOrNull()?.coerceIn(lo, hi)
        }

        val tune = TraceTune(
            autoThreshold = bool("auto_threshold"),
            threshold = int("threshold", 1, 254),
            brightness = int("brightness", -100, 100),
            contrast = int("contrast", 50, 200),
            denoise = int("denoise", 0, 60),
            removeLines = bool("remove_lines"),
            printedText = bool("printed_text"),
            invert = bool("invert"),
            enhance = bool("enhance"),
            quarterTurns = int("quarter_turns", 0, 3),
        )
        return if (tune.isEmpty()) null else tune
    }
}
