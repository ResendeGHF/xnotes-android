package com.xnotes.ui

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matplotlib-style field palettes, wrapped 3D lighting and empty-view detection. */
class MathFieldVisualTest {

    @Test fun jetPaletteMatchesLegacyRamp() {
        for (t in listOf(0.0, 0.12, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            assertEquals("t=$t", heightColor(t).toArgb(), fieldRampColor(FieldPalette.JET, t).toArgb())
        }
    }

    @Test fun fieldRampEndpointsUseFirstAndLastStop() {
        assertEquals(0xFF440154.toInt(), fieldRampColor(FieldPalette.VIRIDIS, 0.0).toArgb())
        assertEquals(0xFFFDE725.toInt(), fieldRampColor(FieldPalette.VIRIDIS, 1.0).toArgb())
        assertEquals(0xFF000004.toInt(), fieldRampColor(FieldPalette.INFERNO, 0.0).toArgb())
    }

    @Test fun palettesHaveStopsAndDiffer() {
        for (pal in FieldPalette.entries) {
            assertTrue(pal.name, pal.stops.size >= 5)
        }
        val firsts = FieldPalette.entries.map { fieldRampArgb(it, 0.0) }.toSet()
        assertTrue(firsts.size > 1)
        val lasts = FieldPalette.entries.map { fieldRampArgb(it, 1.0) }.toSet()
        assertTrue(lasts.size > 1)
    }

    @Test fun swatchCoversFullRamp() {
        val sw = FieldPalette.TURBO.swatch()
        assertEquals(5, sw.size)
        assertEquals(fieldRampColor(FieldPalette.TURBO, 0.0).toArgb(), sw.first().toArgb())
        assertEquals(fieldRampColor(FieldPalette.TURBO, 1.0).toArgb(), sw.last().toArgb())
    }

    @Test fun shadeLitWrapsInsteadOfMirroring() {
        // Hard backface falls to ambient, never mirrors the key light.
        assertEquals(0.34, shadeLit(-1.0, -1.0), 1e-9)
        assertEquals(0.34, shadeLit(-0.55, -10.0), 1e-9)
        // Fully lit front clamps at the highlight ceiling.
        assertEquals(1.3, shadeLit(1.0, 1.0), 1e-9)
        // Neutral facet sits between ambient and full.
        assertEquals(0.34 + 0.66 * (0.55 / 1.55), shadeLit(0.0, 0.0), 1e-9)
        // Monotonic in the diffuse term.
        assertTrue(shadeLit(0.5, 0.0) > shadeLit(-0.5, 0.0))
        assertTrue(shadeLit(0.0, 1.0) > shadeLit(0.0, -1.0))
    }

    @Test fun plot3dHasContentNeedsVisibleNonBlankEntries() {
        val s = Plot3DState()
        assertFalse(s.hasContent())
        s.cartesianList = listOf(MathEntry(""))
        assertFalse(s.hasContent())
        s.cartesianList = listOf(MathEntry("x", visible = false))
        assertFalse(s.hasContent())
        s.cartesianList = listOf(MathEntry("x"))
        assertTrue(s.hasContent())
        s.mode = SurfaceMode.CYLINDRICAL
        assertFalse(s.hasContent())
        s.cylList = listOf(MathEntry("2"))
        assertTrue(s.hasContent())
        s.mode = SurfaceMode.PARAMETRIC
        assertFalse(s.hasContent())
        s.paramList = listOf(Param3D("u", "v", "u"))
        assertTrue(s.hasContent())
        s.mode = SurfaceMode.COMPLEX
        assertFalse(s.hasContent())
        s.complexExpression = "z"
        assertTrue(s.hasContent())
    }

    @Test fun fieldHasContentNeedsVisibleNonBlankEntries() {
        val s = FieldState()
        assertFalse(s.hasContent())
        s.scalar2List = listOf(MathEntry(""))
        assertFalse(s.hasContent())
        s.scalar2List = listOf(MathEntry("x", visible = false))
        assertFalse(s.hasContent())
        s.scalar2List = listOf(MathEntry("x"))
        assertTrue(s.hasContent())
        // Path mode with a blank curve leaves nothing to draw.
        s.usePath2D = true
        assertFalse(s.hasContent())
        s.pathX2 = "cos(s)"
        assertTrue(s.hasContent())
        s.usePath2D = false
        s.mode = FieldMode.VECTOR_2D
        assertFalse(s.hasContent())
        s.vector2List = listOf(Vec2Field("y", "-x"))
        assertTrue(s.hasContent())
    }

    @Test fun fieldPaletteRoundTrip() {
        val f = FieldState()
        f.palette = FieldPalette.VIRIDIS
        val json = MathStudioData.capture("FIELDS", CalculatorState(), SolverState(), Plot2DState(),
            Plot3DState(), f, CalculusState(), SymbolicState(), UnitState())
        val g = FieldState()
        MathStudioData.restore(json, CalculatorState(), SolverState(), Plot2DState(),
            Plot3DState(), g, CalculusState(), SymbolicState(), UnitState())
        assertEquals(FieldPalette.VIRIDIS, g.palette)
    }

    @Test fun fieldPaletteDefaultsToJetForOldNotes() {
        val json = org.json.JSONObject()
            .put("v", 1)
            .put("fields", org.json.JSONObject().put("mode", "SCALAR_2D"))
            .toString()
        val g = FieldState()
        g.palette = FieldPalette.PLASMA
        MathStudioData.restore(json, CalculatorState(), SolverState(), Plot2DState(),
            Plot3DState(), g, CalculusState(), SymbolicState(), UnitState())
        // No palette key: the stored value is left alone.
        assertEquals(FieldPalette.PLASMA, g.palette)
    }

    @Test fun animSpecPaletteRoundTrip() {
        val snap = FieldSnap(FieldMode.SCALAR_2D,
            listOf(MathEntry("x")), emptyList(), emptyList(), emptyList(),
            5.0, 5.0, 3.0, 12, 0.55, Quat.IDENTITY, 1f, true,
            "0.5", "0.5", "0.5", null, AnimSnap(0.0, 1.0, 8, 0.0, false),
            0.0, 0.0, 0.0, false, "", "", "", 0.0, 6.28, FieldPalette.MAGMA)
        val spec = MathAnimSpec.captureFields(snap)
        assertNotNull(spec)
        val parsed = MathAnimSpec.parse(spec!!.toJson())
        assertNotNull(parsed)
        assertEquals("MAGMA", parsed!!.pal)
        val f = FieldState()
        parsed.applyTo(Plot2DState(), Plot3DState(), f)
        assertEquals(FieldPalette.MAGMA, f.palette)
    }

    @Test fun animSpecLegacyBadgeKeepsJet() {
        val spec = MathAnimSpec(tab = "FIELDS", mode = "SCALAR_2D")
        assertEquals("JET", spec.pal)
        val f = FieldState()
        spec.applyTo(Plot2DState(), Plot3DState(), f)
        assertEquals(FieldPalette.JET, f.palette)
        assertNull(MathAnimSpec.parse("not json"))
    }
}
