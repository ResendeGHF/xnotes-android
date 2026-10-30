package com.xnotes.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.math.AngleMode
import com.xnotes.math.Complex
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes a whole Math Studio workspace — every tab — to JSON and back, so each note keeps its
 * own studio. The JSON rides in the note bundle (`math_studio` in the manifest), which is what
 * makes it survive leaving the note, reopening it, exporting and importing.
 *
 * Unknown or malformed pieces restore as factory defaults; an unreadable blob restores nothing.
 */
internal object MathStudioData {
    fun capture(
        tab: String,
        calculator: CalculatorState,
        solver: SolverState,
        plot2d: Plot2DState,
        plot3d: Plot3DState,
        fields: FieldState,
        calculus: CalculusState,
        symbolic: SymbolicState,
        units: UnitState,
    ): String = JSONObject()
        .put("v", 1)
        .put("tab", tab)
        .put("calc", JSONObject()
            .put("input", calculator.input.text)
            .put("result", calculator.result)
            .put("angle", calculator.angle.name)
            .put("ans", complexOf(calculator.ans))
            .put("hist", JSONArray(calculator.history.map {
                JSONObject().put("e", it.expression).put("r", it.result)
            }))
            .put("vars", JSONObject().apply {
                for ((k, v) in calculator.variables) put(k, complexOf(v))
            }))
        .put("solver", JSONObject()
            .put("input", solver.input.text)
            .put("op", solver.op.name)
            .put("var", solver.variable)
            .put("at", solver.at)
            .put("from", solver.from)
            .put("to", solver.to)
            .put("order", solver.order)
            .put("y0", solver.y0)
            .put("method", solver.odeMethod)
            .put("smethod", solver.method.name)
            .put("title", solver.title)
            .put("result", solver.result)
            .put("detail", solver.detail)
            .put("latex", solver.latex)
            .put("insert", solver.insertLatex)
            .put("error", solver.error))
        .put("plot2d", JSONObject()
            .put("mode", plot2d.mode.name)
            .put("exprs", entriesOf(plot2d.expressions))
            .put("params", JSONArray(plot2d.paramCurves.map {
                JSONObject().put("x", it.x).put("y", it.y).put("c", it.colorArgb ?: 0).put("v", it.visible)
            }))
            .put("impl", entriesOf(plot2d.implicitList))
            .put("complex", plot2d.complex)
            .put("turns", plot2d.polarTurns)
            .put("x0", plot2d.xMin).put("x1", plot2d.xMax)
            .put("y0", plot2d.yMin).put("y1", plot2d.yMax)
            .put("grid", plot2d.showGrid)
            .put("anim", animOf(plot2d.anim)))
        .put("plot3d", JSONObject()
            .put("mode", plot3d.mode.name)
            .put("cart", entriesOf(plot3d.cartesianList))
            .put("cyl", entriesOf(plot3d.cylList))
            .put("sph", entriesOf(plot3d.sphList))
            .put("params", JSONArray(plot3d.paramList.map {
                JSONObject().put("x", it.x).put("y", it.y).put("z", it.z).put("c", it.colorArgb ?: 0).put("v", it.visible)
            }))
            .put("impl", entriesOf(plot3d.implicitList))
            .put("cexpr", plot3d.complexExpression)
            .put("comp", plot3d.component.name)
            .put("x0", plot3d.xMin).put("x1", plot3d.xMax)
            .put("y0", plot3d.yMin).put("y1", plot3d.yMax)
            .put("z0", plot3d.zMin).put("z1", plot3d.zMax)
            .put("grid", plot3d.showGrid)
            .put("res", plot3d.resolution.toDouble())
            .put("quat", quatOf(plot3d.orbit.quat))
            .put("zoom", plot3d.orbit.zoom.toDouble())
            .put("anim", animOf(plot3d.anim)))
        .put("fields", JSONObject()
            .put("mode", fields.mode.name)
            .put("s2", entriesOf(fields.scalar2List))
            .put("v2", JSONArray(fields.vector2List.map {
                JSONObject().put("p", it.p).put("q", it.q).put("c", it.colorArgb ?: 0).put("v", it.visible)
            }))
            .put("s3", entriesOf(fields.scalar3List))
            .put("v3", JSONArray(fields.vector3List.map {
                JSONObject().put("p", it.p).put("q", it.q).put("r", it.r).put("c", it.colorArgb ?: 0).put("v", it.visible)
            }))
            .put("xr", fields.xRange).put("yr", fields.yRange).put("zr", fields.zRange)
            .put("cx", fields.centerX).put("cy", fields.centerY).put("cz", fields.centerZ)
            .put("density", fields.density.toDouble())
            .put("grid", fields.showGrid)
            .put("level", fields.level.toDouble())
            .put("up2", fields.usePath2D)
            .put("px2", fields.pathX2).put("py2", fields.pathY2)
            .put("s0", fields.pathS0).put("s1", fields.pathS1)
            .put("up3", fields.usePath3D)
            .put("sx", fields.surfX3).put("sy", fields.surfY3).put("sz", fields.surfZ3)
            .put("u0", fields.surfU0).put("u1", fields.surfU1)
            .put("ox", fields.probeX).put("oy", fields.probeY).put("oz", fields.probeZ)
            .put("quat", quatOf(fields.orbit.quat))
            .put("zoom", fields.orbit.zoom.toDouble())
            .put("anim", animOf(fields.anim)))
        .put("calc2", JSONObject()
            .put("tool", calculus.tool.name)
            .put("expr", calculus.expression)
            .put("var", calculus.variable)
            .put("at", calculus.at)
            .put("order", calculus.order)
            .put("a", calculus.a).put("b", calculus.b)
            .put("method", calculus.method)
            .put("ode", calculus.odeExpression)
            .put("x0", calculus.x0).put("y0", calculus.y0).put("x1", calculus.x1)
            .put("steps", calculus.steps)
            .put("odem", calculus.odeMethod)
            .put("ra", calculus.rootA).put("rb", calculus.rootB)
            .put("guess", calculus.guess)
            .put("rm", calculus.rootMethod)
            .put("sexpr", calculus.seriesExpression)
            .put("sfrom", calculus.seriesFrom).put("sto", calculus.seriesTo)
            .put("title", calculus.title)
            .put("result", calculus.result)
            .put("detail", calculus.detail)
            .put("error", calculus.error))
        .put("sym", JSONObject()
            .put("expr", symbolic.expression)
            .put("var", symbolic.variable)
            .put("at", symbolic.at)
            .put("order", symbolic.order)
            .put("title", symbolic.title)
            .put("result", symbolic.result)
            .put("detail", symbolic.detail)
            .put("latex", symbolic.latex)
            .put("error", symbolic.error))
        .put("units", JSONObject()
            .put("cat", units.category)
            .put("from", units.from)
            .put("to", units.to)
            .put("value", units.value))
        .toString()

    /** Restores every pane from [json]; returns the tab to select, or null when unreadable. */
    fun restore(
        json: String,
        calculator: CalculatorState,
        solver: SolverState,
        plot2d: Plot2DState,
        plot3d: Plot3DState,
        fields: FieldState,
        calculus: CalculusState,
        symbolic: SymbolicState,
        units: UnitState,
    ): String? = runCatching {
        val o = JSONObject(json)
        if (o.optInt("v", 1) != 1) return null
        o.optJSONObject("calc")?.let { c ->
            calculator.input = TextFieldValue(c.optString("input", ""))
            calculator.result = c.optString("result", "")
            calculator.angle = runCatching { AngleMode.valueOf(c.optString("angle", "RADIANS")) }.getOrDefault(AngleMode.RADIANS)
            calculator.ans = complexOr(c.optJSONObject("ans"), Complex.ZERO)
            calculator.history.clear()
            c.optJSONArray("hist")?.let { a ->
                for (i in 0 until a.length()) {
                    val h = a.optJSONObject(i) ?: continue
                    calculator.history.add(CalcHistory(h.optString("e", ""), h.optString("r", "")))
                }
            }
            calculator.variables.clear()
            c.optJSONObject("vars")?.let { v ->
                for (k in v.keys()) {
                    complexOrNull(v.optJSONObject(k))?.let { calculator.variables[k] = it }
                }
            }
            calculator.error = null
            calculator.justEvaluated = false
        }
        o.optJSONObject("solver")?.let { j ->
            solver.input = TextFieldValue(j.optString("input", ""))
            solver.op = runCatching { SolverOp.valueOf(j.optString("op", "EVALUATE")) }.getOrDefault(SolverOp.EVALUATE)
            solver.variable = j.optString("var", "x")
            solver.at = j.optString("at", "0")
            solver.from = j.optString("from", "0")
            solver.to = j.optString("to", "1")
            solver.order = j.optString("order", "1")
            solver.y0 = j.optString("y0", "1")
            solver.odeMethod = j.optString("method", "RK45")
            if (j.has("smethod")) solver.method = runCatching { SolverMethod.valueOf(j.optString("smethod", "AUTO")) }.getOrDefault(SolverMethod.AUTO)
            solver.title = j.optString("title", "")
            solver.result = j.optString("result", "")
            solver.detail = if (j.has("detail")) j.optString("detail") else null
            solver.latex = if (j.has("latex")) j.optString("latex") else null
            solver.insertLatex = j.optString("insert", "")
            solver.error = if (j.has("error")) j.optString("error") else null
            solver.odePoints = emptyList()
        }
        o.optJSONObject("plot2d")?.let { j ->
            plot2d.mode = runCatching { Plot2DMode.valueOf(j.optString("mode", "CARTESIAN")) }.getOrDefault(Plot2DMode.CARTESIAN)
            entriesFrom(j.optJSONArray("exprs"))?.let { plot2d.expressions = it }
            j.optJSONArray("params")?.let { a ->
                plot2d.paramCurves = List(a.length()) { i ->
                    val e = a.optJSONObject(i)
                    ParamCurve2D(e?.optString("x", "") ?: "", e?.optString("y", "") ?: "",
                        e?.optInt("c", 0)?.takeIf { it != 0 }, e?.optBoolean("v", true) ?: true)
                }
            }
            entriesFrom(j.optJSONArray("impl"))?.let { plot2d.implicitList = it }
            if (j.has("complex")) plot2d.complex = j.optString("complex", "")
            if (j.has("turns")) plot2d.polarTurns = j.optString("turns", "2")
            plot2d.xMin = j.optDouble("x0", -10.0); plot2d.xMax = j.optDouble("x1", 10.0)
            plot2d.yMin = j.optDouble("y0", -6.25); plot2d.yMax = j.optDouble("y1", 6.25)
            if (j.has("grid")) plot2d.showGrid = j.optBoolean("grid", true)
            animFrom(j.optJSONObject("anim"), plot2d.anim)
        }
        o.optJSONObject("plot3d")?.let { j ->
            plot3d.mode = runCatching { SurfaceMode.valueOf(j.optString("mode", "CARTESIAN")) }.getOrDefault(SurfaceMode.CARTESIAN)
            entriesFrom(j.optJSONArray("cart"))?.let { plot3d.cartesianList = it }
            entriesFrom(j.optJSONArray("cyl"))?.let { plot3d.cylList = it }
            entriesFrom(j.optJSONArray("sph"))?.let { plot3d.sphList = it }
            j.optJSONArray("params")?.let { a ->
                plot3d.paramList = List(a.length()) { i ->
                    val e = a.optJSONObject(i)
                    Param3D(e?.optString("x", "") ?: "", e?.optString("y", "") ?: "", e?.optString("z", "") ?: "",
                        e?.optInt("c", 0)?.takeIf { it != 0 }, e?.optBoolean("v", true) ?: true)
                }
            }
            entriesFrom(j.optJSONArray("impl"))?.let { plot3d.implicitList = it }
            if (j.has("cexpr")) plot3d.complexExpression = j.optString("cexpr", "")
            plot3d.component = runCatching { ComplexComponent.valueOf(j.optString("comp", "MAGNITUDE")) }.getOrDefault(ComplexComponent.MAGNITUDE)
            plot3d.xMin = j.optDouble("x0", -5.0); plot3d.xMax = j.optDouble("x1", 5.0)
            plot3d.yMin = j.optDouble("y0", -5.0); plot3d.yMax = j.optDouble("y1", 5.0)
            plot3d.zMin = j.optDouble("z0", -5.0); plot3d.zMax = j.optDouble("z1", 5.0)
            if (j.has("grid")) plot3d.showGrid = j.optBoolean("grid", true)
            if (j.has("res")) plot3d.resolution = j.optDouble("res", 40.0).toFloat()
            quatFrom(j.optJSONObject("quat"))?.let { plot3d.orbit.quat = it }
            if (j.has("zoom")) plot3d.orbit.zoom = j.optDouble("zoom", 1.0).toFloat()
            animFrom(j.optJSONObject("anim"), plot3d.anim)
        }
        o.optJSONObject("fields")?.let { j ->
            fields.mode = runCatching { FieldMode.valueOf(j.optString("mode", "SCALAR_2D")) }.getOrDefault(FieldMode.SCALAR_2D)
            entriesFrom(j.optJSONArray("s2"))?.let { fields.scalar2List = it }
            j.optJSONArray("v2")?.let { a ->
                fields.vector2List = List(a.length()) { i ->
                    val e = a.optJSONObject(i)
                    Vec2Field(e?.optString("p", "") ?: "", e?.optString("q", "") ?: "",
                        e?.optInt("c", 0)?.takeIf { it != 0 }, e?.optBoolean("v", true) ?: true)
                }
            }
            entriesFrom(j.optJSONArray("s3"))?.let { fields.scalar3List = it }
            j.optJSONArray("v3")?.let { a ->
                fields.vector3List = List(a.length()) { i ->
                    val e = a.optJSONObject(i)
                    Vec3Field(e?.optString("p", "") ?: "", e?.optString("q", "") ?: "", e?.optString("r", "") ?: "",
                        e?.optInt("c", 0)?.takeIf { it != 0 }, e?.optBoolean("v", true) ?: true)
                }
            }
            if (j.has("xr")) fields.xRange = j.optString("xr", "5")
            if (j.has("yr")) fields.yRange = j.optString("yr", "5")
            if (j.has("zr")) fields.zRange = j.optString("zr", "3")
            if (j.has("cx")) fields.centerX = j.optString("cx", "0")
            if (j.has("cy")) fields.centerY = j.optString("cy", "0")
            if (j.has("cz")) fields.centerZ = j.optString("cz", "0")
            if (j.has("density")) fields.density = j.optDouble("density", 12.0).toFloat()
            if (j.has("grid")) fields.showGrid = j.optBoolean("grid", true)
            if (j.has("level")) fields.level = j.optDouble("level", 0.55).toFloat()
            if (j.has("up2")) fields.usePath2D = j.optBoolean("up2", false)
            if (j.has("px2")) fields.pathX2 = j.optString("px2", "")
            if (j.has("py2")) fields.pathY2 = j.optString("py2", "")
            if (j.has("s0")) fields.pathS0 = j.optString("s0", "0")
            if (j.has("s1")) fields.pathS1 = j.optString("s1", "6.28")
            if (j.has("up3")) fields.usePath3D = j.optBoolean("up3", false)
            if (j.has("sx")) fields.surfX3 = j.optString("sx", "")
            if (j.has("sy")) fields.surfY3 = j.optString("sy", "")
            if (j.has("sz")) fields.surfZ3 = j.optString("sz", "")
            if (j.has("u0")) fields.surfU0 = j.optString("u0", "-3.14")
            if (j.has("u1")) fields.surfU1 = j.optString("u1", "3.14")
            if (j.has("ox")) fields.probeX = j.optString("ox", "0.5")
            if (j.has("oy")) fields.probeY = j.optString("oy", "0.5")
            if (j.has("oz")) fields.probeZ = j.optString("oz", "0.5")
            quatFrom(j.optJSONObject("quat"))?.let { fields.orbit.quat = it }
            if (j.has("zoom")) fields.orbit.zoom = j.optDouble("zoom", 1.0).toFloat()
            animFrom(j.optJSONObject("anim"), fields.anim)
        }
        o.optJSONObject("calc2")?.let { j ->
            calculus.tool = runCatching { CalculusTool.valueOf(j.optString("tool", "DERIVATIVE")) }.getOrDefault(CalculusTool.DERIVATIVE)
            if (j.has("expr")) calculus.expression = j.optString("expr", "")
            if (j.has("var")) calculus.variable = j.optString("var", "x")
            if (j.has("at")) calculus.at = j.optString("at", "1")
            if (j.has("order")) calculus.order = j.optString("order", "1")
            if (j.has("a")) calculus.a = j.optString("a", "0")
            if (j.has("b")) calculus.b = j.optString("b", "1")
            if (j.has("method")) calculus.method = j.optString("method", "adaptive")
            if (j.has("ode")) calculus.odeExpression = j.optString("ode", "")
            if (j.has("x0")) calculus.x0 = j.optString("x0", "0")
            if (j.has("y0")) calculus.y0 = j.optString("y0", "1")
            if (j.has("x1")) calculus.x1 = j.optString("x1", "8")
            if (j.has("steps")) calculus.steps = j.optString("steps", "400")
            if (j.has("odem")) calculus.odeMethod = j.optString("odem", "RK45")
            if (j.has("ra")) calculus.rootA = j.optString("ra", "-2")
            if (j.has("rb")) calculus.rootB = j.optString("rb", "2")
            if (j.has("guess")) calculus.guess = j.optString("guess", "1")
            if (j.has("rm")) calculus.rootMethod = j.optString("rm", "bisection")
            if (j.has("sexpr")) calculus.seriesExpression = j.optString("sexpr", "")
            if (j.has("sfrom")) calculus.seriesFrom = j.optString("sfrom", "1")
            if (j.has("sto")) calculus.seriesTo = j.optString("sto", "60")
            calculus.title = j.optString("title", "")
            calculus.result = j.optString("result", "")
            calculus.detail = if (j.has("detail")) j.optString("detail") else null
            calculus.error = if (j.has("error")) j.optString("error") else null
            calculus.points = emptyList()
        }
        o.optJSONObject("sym")?.let { j ->
            if (j.has("expr")) symbolic.expression = j.optString("expr", "")
            if (j.has("var")) symbolic.variable = j.optString("var", "x")
            if (j.has("at")) symbolic.at = j.optString("at", "0")
            if (j.has("order")) symbolic.order = j.optString("order", "6")
            symbolic.title = j.optString("title", "")
            symbolic.result = j.optString("result", "")
            symbolic.detail = if (j.has("detail")) j.optString("detail") else null
            symbolic.latex = if (j.has("latex")) j.optString("latex") else null
            symbolic.error = if (j.has("error")) j.optString("error") else null
        }
        o.optJSONObject("units")?.let { j ->
            if (j.has("cat")) units.category = j.optString("cat", "")
            if (j.has("from")) units.from = j.optString("from", "")
            if (j.has("to")) units.to = j.optString("to", "")
            if (j.has("value")) units.value = j.optString("value", "1")
        }
        if (o.has("tab")) o.optString("tab") else null
    }.getOrNull()

    private fun complexOf(v: Complex): JSONObject =
        JSONObject().put("re", v.re).put("im", v.im)

    private fun complexOr(o: JSONObject?, fallback: Complex): Complex = complexOrNull(o) ?: fallback

    private fun complexOrNull(o: JSONObject?): Complex? = runCatching {
        if (o == null) return null
        Complex(o.optDouble("re", 0.0), o.optDouble("im", 0.0))
    }.getOrNull()

    private fun entriesOf(list: List<MathEntry>): JSONArray = JSONArray(
        list.map { JSONObject().put("t", it.text).put("c", it.colorArgb ?: 0).put("v", it.visible) },
    )

    private fun entriesFrom(a: JSONArray?): List<MathEntry>? {
        if (a == null) return null
        return List(a.length()) { i ->
            val e = a.optJSONObject(i)
            MathEntry(e?.optString("t", "") ?: "", e?.optInt("c", 0)?.takeIf { it != 0 }, e?.optBoolean("v", true) ?: true)
        }
    }

    private fun animOf(a: AnimState): JSONObject = JSONObject()
        .put("on", a.enabled)
        .put("min", a.tMinStr).put("max", a.tMaxStr).put("steps", a.tStepsStr)
        .put("share", a.shareT)
        .put("t", a.tCurrent)
        .put("playing", a.playing)

    private fun animFrom(o: JSONObject?, a: AnimState) {
        if (o == null) return
        if (o.has("min")) a.tMinStr = o.optString("min", "0")
        if (o.has("max")) a.tMaxStr = o.optString("max", "6.28")
        if (o.has("steps")) a.tStepsStr = o.optString("steps", "60")
        if (o.has("share")) a.shareT = o.optBoolean("share", false)
        if (o.has("t")) a.tCurrent = o.optDouble("t", 0.0)
        if (o.has("on")) a.enabled = o.optBoolean("on", false)
        if (o.has("playing")) a.playing = o.optBoolean("playing", false)
    }

    private fun quatOf(q: Quat): JSONObject = JSONObject()
        .put("w", q.w).put("x", q.x).put("y", q.y).put("z", q.z)

    private fun quatFrom(o: JSONObject?): Quat? = runCatching {
        if (o == null) return null
        Quat(o.optDouble("w", 1.0), o.optDouble("x", 0.0), o.optDouble("y", 0.0), o.optDouble("z", 0.0)).normalized()
    }.getOrNull()
}
