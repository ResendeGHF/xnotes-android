package com.xnotes.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything needed to reopen a Math Studio animation exactly as exported: the tab, the mode,
 * every expression, the `t` range and the view window/centre. Filmstrip PNGs carry this as JSON
 * in an `iTXt` chunk (see [com.xnotes.platform.MathAnimPng]); tapping the play badge on such an
 * image reopens Math Studio with the animation restored and playing.
 */
data class MathAnimSpec(
    val version: Int = 1,
    val tab: String = "GRAPH_2D",
    val mode: String = "CARTESIAN",
    val exprs: List<String> = emptyList(),
    val px: List<String> = emptyList(),
    val py: List<String> = emptyList(),
    val pz: List<String> = emptyList(),
    val implicit: List<String> = emptyList(),
    val complex: String = "",
    val turns: Double = 2.0,
    val component: String = "MAGNITUDE",
    val tMin: Double = 0.0,
    val tMax: Double = 6.28,
    val tSteps: Int = 60,
    val shareT: Boolean = false,
    val range: Double = 5.0,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val cz: Double = 0.0,
    val level: Double = 0.55,
    val x0: Double = -10.0,
    val x1: Double = 10.0,
    val y0: Double = -6.25,
    val y1: Double = 6.25,
    val zRange: Double = 3.0,
    val usePath: Boolean = false,
    val pathX: String = "",
    val pathY: String = "",
    val pathZ: String = "",
    val pathR0: Double = 0.0,
    val pathR1: Double = 6.28,
    /** True when x0..z1 carry an explicit domain box (3D); legacy badges use range/cx/cy/cz. */
    val dom: Boolean = false,
    val z0: Double = -5.0,
    val z1: Double = 5.0,
    val grid: Boolean = true,
    /** Per-entry colours (0 = automatic series colour), parallel to exprs / params / implicit. */
    val ecol: List<Int> = emptyList(),
    val pcol: List<Int> = emptyList(),
    val icol: List<Int> = emptyList(),
    /** Per-entry visibility, parallel to the colour arrays; missing entries default to visible. */
    val evis: List<Boolean> = emptyList(),
    val pvis: List<Boolean> = emptyList(),
    val ivis: List<Boolean> = emptyList(),
) {
    fun toJson(): String = JSONObject()
        .put("v", version)
        .put("tab", tab)
        .put("mode", mode)
        .put("exprs", JSONArray(exprs))
        .put("px", JSONArray(px))
        .put("py", JSONArray(py))
        .put("pz", JSONArray(pz))
        .put("impl", JSONArray(implicit))
        .put("complex", complex)
        .put("turns", turns.finiteOr(2.0))
        .put("comp", component)
        .put("tmin", tMin.finiteOr(0.0))
        .put("tmax", tMax.finiteOr(6.28))
        .put("tsteps", tSteps)
        .put("shareT", shareT)
        .put("range", range.finiteOr(5.0))
        .put("cx", cx.finiteOr(0.0))
        .put("cy", cy.finiteOr(0.0))
        .put("cz", cz.finiteOr(0.0))
        .put("level", level.finiteOr(0.55))
        .put("x0", x0.finiteOr(-10.0))
        .put("x1", x1.finiteOr(10.0))
        .put("y0", y0.finiteOr(-6.25))
        .put("y1", y1.finiteOr(6.25))
        .put("zrange", zRange.finiteOr(3.0))
        .put("usepath", usePath)
        .put("pathx", pathX)
        .put("pathy", pathY)
        .put("pathz", pathZ)
        .put("pathr0", pathR0.finiteOr(0.0))
        .put("pathr1", pathR1.finiteOr(6.28))
        .put("dom", dom)
        .put("z0", z0.finiteOr(-5.0))
        .put("z1", z1.finiteOr(5.0))
        .put("grid", grid)
        .put("ecol", JSONArray(ecol))
        .put("pcol", JSONArray(pcol))
        .put("icol", JSONArray(icol))
        .put("evis", JSONArray(evis))
        .put("pvis", JSONArray(pvis))
        .put("ivis", JSONArray(ivis))
        .toString()

    companion object {
        internal fun parse(json: String): MathAnimSpec? = runCatching {
            val o = JSONObject(json)
            if (o.optInt("v", 1) != 1) return null
            fun arr(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.optString(it) }
            }
            fun arrInt(key: String): List<Int> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.optInt(it) }
            }
            fun arrBool(key: String): List<Boolean> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.optBoolean(it, true) }
            }
            MathAnimSpec(
                tab = o.optString("tab", "GRAPH_2D"),
                mode = o.optString("mode", "CARTESIAN"),
                exprs = arr("exprs"),
                px = arr("px"),
                py = arr("py"),
                pz = arr("pz"),
                implicit = arr("impl"),
                complex = o.optString("complex", ""),
                turns = o.optDouble("turns", 2.0),
                component = o.optString("comp", "MAGNITUDE"),
                tMin = o.optDouble("tmin", 0.0),
                tMax = o.optDouble("tmax", 6.28),
                tSteps = o.optInt("tsteps", 60).coerceIn(2, 400),
                shareT = o.optBoolean("shareT", false),
                range = o.optDouble("range", 5.0),
                cx = o.optDouble("cx", 0.0),
                cy = o.optDouble("cy", 0.0),
                cz = o.optDouble("cz", 0.0),
                level = o.optDouble("level", 0.55),
                x0 = o.optDouble("x0", -10.0),
                x1 = o.optDouble("x1", 10.0),
                y0 = o.optDouble("y0", -6.25),
                y1 = o.optDouble("y1", 6.25),
                zRange = o.optDouble("zrange", 3.0),
                usePath = o.optBoolean("usepath", false),
                pathX = o.optString("pathx", ""),
                pathY = o.optString("pathy", ""),
                pathZ = o.optString("pathz", ""),
                pathR0 = o.optDouble("pathr0", 0.0),
                pathR1 = o.optDouble("pathr1", 6.28),
                dom = o.optBoolean("dom", false),
                z0 = o.optDouble("z0", -5.0),
                z1 = o.optDouble("z1", 5.0),
                grid = o.optBoolean("grid", true),
                ecol = arrInt("ecol"),
                pcol = arrInt("pcol"),
                icol = arrInt("icol"),
                evis = arrBool("evis"),
                pvis = arrBool("pvis"),
                ivis = arrBool("ivis"),
            )
        }.getOrNull()

        internal fun capture2D(snap: Plot2DSnap): MathAnimSpec? {
            val a = snap.anim ?: return null
            return MathAnimSpec(
                tab = "GRAPH_2D",
                mode = snap.mode.name,
                exprs = snap.expressions.map { it.text },
                ecol = snap.expressions.map { it.colorArgb ?: 0 },
                evis = snap.expressions.map { it.visible },
                px = snap.paramCurves.map { it.x },
                py = snap.paramCurves.map { it.y },
                pcol = snap.paramCurves.map { it.colorArgb ?: 0 },
                pvis = snap.paramCurves.map { it.visible },
                implicit = snap.implicitList.map { it.text },
                icol = snap.implicitList.map { it.colorArgb ?: 0 },
                ivis = snap.implicitList.map { it.visible },
                complex = snap.complex,
                turns = snap.polarTurns,
                tMin = a.lo, tMax = a.hi, tSteps = a.steps, shareT = a.shareT,
                x0 = snap.xMin, x1 = snap.xMax, y0 = snap.yMin, y1 = snap.yMax,
                grid = snap.showGrid,
            )
        }

        internal fun capture3D(snap: Plot3DSnap): MathAnimSpec? {
            val a = snap.animRange ?: return null
            return MathAnimSpec(
                tab = "GRAPH_3D",
                mode = snap.mode.name,
                exprs = snap.exprs.map { it.text },
                ecol = snap.exprs.map { it.colorArgb ?: 0 },
                evis = snap.exprs.map { it.visible },
                px = snap.params.map { it.x },
                py = snap.params.map { it.y },
                pz = snap.params.map { it.z },
                pcol = snap.params.map { it.colorArgb ?: 0 },
                pvis = snap.params.map { it.visible },
                implicit = if (snap.mode.name == "IMPLICIT") snap.exprs.map { it.text } else emptyList(),
                complex = snap.exprs.firstOrNull()?.text.orEmpty(),
                component = snap.component.name,
                tMin = a.lo, tMax = a.hi, tSteps = a.steps, shareT = snap.shareT,
                range = (snap.x1 - snap.x0) / 2,
                cx = (snap.x0 + snap.x1) / 2, cy = (snap.y0 + snap.y1) / 2, cz = (snap.z0 + snap.z1) / 2,
                dom = true,
                x0 = snap.x0, x1 = snap.x1, y0 = snap.y0, y1 = snap.y1,
                z0 = snap.z0, z1 = snap.z1,
                grid = snap.showGrid,
            )
        }

        internal fun captureFields(snap: FieldSnap): MathAnimSpec? {
            val a = snap.animRange ?: return null
            val scalars = snap.scalar2 + snap.scalar3
            val vps = snap.vector2.map { it.p } + snap.vector3.map { it.p }
            val vqs = snap.vector2.map { it.q } + snap.vector3.map { it.q }
            val vcols = snap.vector2.map { it.colorArgb ?: 0 } + snap.vector3.map { it.colorArgb ?: 0 }
            val vvis = snap.vector2.map { it.visible } + snap.vector3.map { it.visible }
            return MathAnimSpec(
                tab = "FIELDS",
                mode = snap.mode.name,
                exprs = scalars.map { it.text },
                ecol = scalars.map { it.colorArgb ?: 0 },
                evis = scalars.map { it.visible },
                px = vps,
                py = vqs,
                pz = snap.vector3.map { it.r },
                pcol = vcols,
                pvis = vvis,
                tMin = a.lo, tMax = a.hi, tSteps = a.steps,
                range = snap.xRange,
                cx = snap.cx, cy = snap.cy, cz = snap.cz,
                level = snap.level,
                x0 = -snap.xRange, x1 = snap.xRange, y0 = -snap.yRange, y1 = snap.yRange,
                zRange = snap.zRange,
                grid = snap.showGrid,
                usePath = snap.usePath,
                pathX = snap.pathX, pathY = snap.pathY, pathZ = snap.pathZ,
                pathR0 = snap.pathR0, pathR1 = snap.pathR1,
            )
        }
    }

    /** Restores the setup into the live pane states and returns the tab to select. */
    internal fun applyTo(plot2d: Plot2DState, plot3d: Plot3DState, fields: FieldState): String {
        when (tab) {
            "GRAPH_3D" -> plot3d.restore(this)
            "FIELDS" -> fields.restore(this)
            else -> plot2d.restore(this)
        }
        return tab
    }
}

private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

/** Applies a stored entry style: colour 0 means automatic, missing visibility means shown. */
private fun styledEntries(texts: List<String>, colors: List<Int>, vis: List<Boolean>): List<MathEntry> =
    texts.mapIndexed { i, t ->
        MathEntry(t, colors.getOrNull(i)?.takeIf { it != 0 }, vis.getOrNull(i) ?: true)
    }

private fun Plot2DState.restore(s: MathAnimSpec) {
    mode = runCatching { Plot2DMode.valueOf(s.mode) }.getOrDefault(Plot2DMode.CARTESIAN)
    if (s.exprs.isNotEmpty()) expressions = styledEntries(s.exprs, s.ecol, s.evis).take(6)
    if (s.px.isNotEmpty() && s.py.isNotEmpty()) {
        paramCurves = s.px.zip(s.py).take(6).mapIndexed { i, (x, y) ->
            ParamCurve2D(x, y, s.pcol.getOrNull(i)?.takeIf { it != 0 }, s.pvis.getOrNull(i) ?: true)
        }
    }
    if (s.implicit.isNotEmpty()) implicitList = styledEntries(s.implicit, s.icol, s.ivis).take(6)
    if (s.complex.isNotEmpty()) complex = s.complex
    polarTurns = s.turns.toString()
    xMin = s.x0; xMax = s.x1; yMin = s.y0; yMax = s.y1
    showGrid = s.grid
    s.fillAnimFor2D(this)
}

private fun MathAnimSpec.fillAnimFor2D(state: Plot2DState) {
    state.anim.tMinStr = tMin.toString()
    state.anim.tMaxStr = tMax.toString()
    state.anim.tStepsStr = tSteps.toString()
    state.anim.shareT = shareT
    state.anim.enabled = true
    state.anim.tCurrent = minOf(tMin, tMax)
    state.anim.playing = true
}

private fun Plot3DState.restore(s: MathAnimSpec) {
    mode = runCatching { SurfaceMode.valueOf(s.mode) }.getOrDefault(SurfaceMode.CARTESIAN)
    when (mode) {
        SurfaceMode.CARTESIAN -> if (s.exprs.isNotEmpty()) cartesianList = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        SurfaceMode.CYLINDRICAL -> if (s.exprs.isNotEmpty()) cylList = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        SurfaceMode.SPHERICAL -> if (s.exprs.isNotEmpty()) sphList = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        SurfaceMode.PARAMETRIC -> if (s.px.isNotEmpty()) {
            paramList = s.px.indices.map { i ->
                Param3D(
                    s.px.getOrElse(i) { "0" }, s.py.getOrElse(i) { "0" }, s.pz.getOrElse(i) { "0" },
                    s.pcol.getOrNull(i)?.takeIf { it != 0 }, s.pvis.getOrNull(i) ?: true,
                )
            }.take(4)
        }
        SurfaceMode.IMPLICIT -> if (s.implicit.isNotEmpty()) implicitList = styledEntries(s.implicit, s.ecol, s.evis).take(4)
        else if (s.exprs.isNotEmpty()) implicitList = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        SurfaceMode.COMPLEX -> if (s.complex.isNotEmpty()) complexExpression = s.complex
    }
    component = runCatching { ComplexComponent.valueOf(s.component) }.getOrDefault(ComplexComponent.MAGNITUDE)
    if (s.dom) {
        xMin = s.x0; xMax = s.x1; yMin = s.y0; yMax = s.y1; zMin = s.z0; zMax = s.z1
    } else {
        // Legacy badge: explicit domain box from the old range + centre.
        xMin = s.cx - s.range; xMax = s.cx + s.range
        yMin = s.cy - s.range; yMax = s.cy + s.range
        zMin = s.cz - s.range; zMax = s.cz + s.range
    }
    showGrid = s.grid
    orbit.reset()
    s.fillAnimFor3D(this)
}

private fun MathAnimSpec.fillAnimFor3D(state: Plot3DState) {
    state.anim.tMinStr = tMin.toString()
    state.anim.tMaxStr = tMax.toString()
    state.anim.tStepsStr = tSteps.toString()
    state.anim.shareT = shareT
    state.anim.enabled = true
    state.anim.tCurrent = minOf(tMin, tMax)
    state.anim.playing = true
}

private fun FieldState.restore(s: MathAnimSpec) {
    mode = runCatching { FieldMode.valueOf(s.mode) }.getOrDefault(FieldMode.SCALAR_2D)
    when (mode) {
        FieldMode.SCALAR_2D -> if (s.exprs.isNotEmpty()) scalar2List = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        FieldMode.SCALAR_3D -> if (s.exprs.isNotEmpty()) scalar3List = styledEntries(s.exprs, s.ecol, s.evis).take(4)
        FieldMode.VECTOR_2D -> if (s.px.isNotEmpty()) {
            vector2List = s.px.indices.map { i ->
                Vec2Field(
                    s.px.getOrElse(i) { "0" }, s.py.getOrElse(i) { "0" },
                    s.pcol.getOrNull(i)?.takeIf { it != 0 }, s.pvis.getOrNull(i) ?: true,
                )
            }.take(4)
        }
        FieldMode.VECTOR_3D -> if (s.px.isNotEmpty()) {
            vector3List = s.px.indices.map { i ->
                Vec3Field(
                    s.px.getOrElse(i) { "0" }, s.py.getOrElse(i) { "0" }, s.pz.getOrElse(i) { "0" },
                    s.pcol.getOrNull(i)?.takeIf { it != 0 }, s.pvis.getOrNull(i) ?: true,
                )
            }.take(4)
        }
    }
    xRange = s.range.toString(); yRange = ((s.y1 - s.y0) / 2).toString(); zRange = s.zRange.toString()
    centerX = s.cx.toString(); centerY = s.cy.toString(); centerZ = s.cz.toString()
    level = s.level.toFloat()
    showGrid = s.grid
    if (s.usePath) {
        if (mode == FieldMode.SCALAR_2D || mode == FieldMode.VECTOR_2D) {
            usePath2D = true
            pathX2 = s.pathX; pathY2 = s.pathY
            pathS0 = s.pathR0.toString(); pathS1 = s.pathR1.toString()
        } else {
            usePath3D = true
            surfX3 = s.pathX; surfY3 = s.pathY; surfZ3 = s.pathZ
            surfU0 = s.pathR0.toString(); surfU1 = s.pathR1.toString()
        }
    }
    orbit.reset()
    anim.tMinStr = s.tMin.toString()
    anim.tMaxStr = s.tMax.toString()
    anim.tStepsStr = s.tSteps.toString()
    anim.enabled = true
    anim.tCurrent = minOf(s.tMin, s.tMax)
    anim.playing = true
}
