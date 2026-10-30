package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexAiProvidersTest {

    @Test fun unknownProviderFallsBackToGemini() {
        assertEquals(LatexAiProvider.GEMINI, LatexAiProvider.ofId("gemini"))
        assertEquals(LatexAiProvider.OPENAI, LatexAiProvider.ofId("openai"))
        assertEquals(LatexAiProvider.ANTHROPIC, LatexAiProvider.ofId("anthropic"))
        assertEquals(LatexAiProvider.CUSTOM, LatexAiProvider.ofId("custom"))
        assertEquals(LatexAiProvider.GEMINI, LatexAiProvider.ofId("bogus"))
        assertEquals(LatexAiProvider.GEMINI, LatexAiProvider.ofId(""))
    }

    @Test fun builtInsHaveDefaultsCustomHasNone() {
        for (p in listOf(LatexAiProvider.GEMINI, LatexAiProvider.OPENAI, LatexAiProvider.ANTHROPIC)) {
            assertTrue(LatexAiConfig.defaultModel(p).isNotEmpty())
            assertTrue(LatexAiConfig.defaultBaseUrl(p).isNotEmpty())
        }
        assertEquals("", LatexAiConfig.defaultModel(LatexAiProvider.CUSTOM))
        assertEquals("", LatexAiConfig.defaultBaseUrl(LatexAiProvider.CUSTOM))
    }

    @Test fun onlyGeminiAndClaudeRequireAKey() {
        assertTrue(LatexAiConfig.needsApiKey(LatexAiProvider.GEMINI))
        assertTrue(LatexAiConfig.needsApiKey(LatexAiProvider.ANTHROPIC))
        assertFalse(LatexAiConfig.needsApiKey(LatexAiProvider.OPENAI))
        assertFalse(LatexAiConfig.needsApiKey(LatexAiProvider.CUSTOM))
    }
}
