package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmTraceTuneTest {

    @Test fun promptNamesTheContractAndCurrentSettings() {
        val p = VlmTraceTune.promptFor("threshold 128")
        assertTrue(p, p.contains("threshold 128"))
        assertTrue(p, p.contains("JSON"))
        assertTrue(p, p.contains("EDGES"))
        assertTrue(p, p.contains("REQUIRED"))
        assertTrue(p, p.contains("MUST be false"))
        assertTrue(p, p.contains("\"printed_text\""))
        assertTrue(p, p.contains("\"quarter_turns\""))
        assertTrue(p, p.contains("ONLY"))
    }

    @Test fun fullAnswerParsesEveryField() {
        val raw = """{"auto_threshold": false, "threshold": 140, "brightness": -10, "contrast": 150,
            "denoise": 12, "remove_lines": true, "printed_text": true, "invert": false,
            "enhance": true, "quarter_turns": 1}"""
        val t = VlmTraceTune.parseRecommendation(raw)!!
        assertEquals(false, t.autoThreshold)
        assertEquals(140, t.threshold)
        assertEquals(-10, t.brightness)
        assertEquals(150, t.contrast)
        assertEquals(12, t.denoise)
        assertEquals(true, t.removeLines)
        assertEquals(true, t.printedText)
        assertEquals(false, t.invert)
        assertEquals(true, t.enhance)
        assertEquals(1, t.quarterTurns)
        assertFalse(t.isEmpty())
    }

    @Test fun fencesAndProseAreToleratedAndUnknownKeysIgnored() {
        val raw = "Sure! ```json\n{\"printed_text\": true, \"mystery\": 5}\n``` Hope that helps."
        val t = VlmTraceTune.parseRecommendation(raw)!!
        assertEquals(true, t.printedText)
        assertNull(t.threshold)
    }

    @Test fun valuesAreClampedToSliderRanges() {
        val t = VlmTraceTune.parseRecommendation(
            """{"threshold": 999, "brightness": -500, "contrast": 5, "denoise": 70, "quarter_turns": 9}""",
        )!!
        assertEquals(254, t.threshold)
        assertEquals(-100, t.brightness)
        assertEquals(50, t.contrast)
        assertEquals(60, t.denoise)
        assertEquals(3, t.quarterTurns)
    }

    @Test fun garbageAndEmptyObjectsGiveNull() {
        assertNull(VlmTraceTune.parseRecommendation("no json here"))
        assertNull(VlmTraceTune.parseRecommendation(""))
        assertNull(VlmTraceTune.parseRecommendation("{}"))
        assertNull(VlmTraceTune.parseRecommendation("{\"unknown\": 1}"))
    }

    @Test fun normalizeManualDropsAutomaticModes() {
        val t = VlmTraceTune.TraceTune(autoThreshold = true, enhance = true, brightness = 5)
        val out = VlmTraceTune.normalizeManual(t, fallbackThreshold = 150)
        assertEquals(false, out.autoThreshold)
        assertEquals(150, out.threshold)
        assertEquals(false, out.enhance)
        assertEquals(5, out.brightness)
    }

    @Test fun normalizeManualKeepsExplicitManualValues() {
        val t = VlmTraceTune.TraceTune(autoThreshold = false, threshold = 140)
        val out = VlmTraceTune.normalizeManual(t, fallbackThreshold = 150)
        assertEquals(false, out.autoThreshold)
        assertEquals(140, out.threshold)
    }

    @Test fun normalizeManualLeavesOmissionsAlone() {
        val t = VlmTraceTune.TraceTune(brightness = -5)
        val out = VlmTraceTune.normalizeManual(t, fallbackThreshold = 150)
        assertNull(out.autoThreshold)
        assertNull(out.threshold)
        assertNull(out.enhance)
        assertEquals(-5, out.brightness)
    }
}
