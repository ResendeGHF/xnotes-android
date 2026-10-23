package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.AngleMode
import com.xnotes.math.Complex
import com.xnotes.math.EvalScope
import com.xnotes.math.Evaluator
import com.xnotes.math.ExpressionParser
import com.xnotes.math.MathCatalog
import com.xnotes.math.Numbers
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The tabs of the maths workspace. */
internal enum class MathTab(val labelRes: Int, val icon: ImageVector) {
    CALCULATOR(R.string.math_tab_calculator, Icons.Outlined.Calculate),
    GRAPH_2D(R.string.math_tab_graph2d, Icons.AutoMirrored.Outlined.ShowChart),
    GRAPH_3D(R.string.math_tab_graph3d, Icons.Outlined.ViewInAr),
    FIELDS(R.string.math_tab_fields, Icons.Outlined.ScatterPlot),
    CALCULUS(R.string.math_tab_calculus, Icons.Outlined.Insights),
    SYMBOLIC(R.string.math_tab_symbolic, Icons.Outlined.Functions),
    UNITS(R.string.math_tab_units, Icons.Outlined.Straighten),
    CATALOG(R.string.math_tab_catalog, Icons.AutoMirrored.Outlined.MenuBook),
}

/** A history line in the calculator. */
internal data class CalcHistory(val expression: String, val result: String)

/** Everything the calculator keeps between tab switches. */
internal class CalculatorState {
    var input by mutableStateOf(TextFieldValue(""))
    var result by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var angle by mutableStateOf(AngleMode.RADIANS)
    var ans by mutableStateOf(Complex.ZERO)
    val history = mutableStateListOf<CalcHistory>()
    val variables = mutableStateMapOf<String, Complex>()

    fun insert(text: String) {
        val value = input
        val start = value.selection.start.coerceIn(0, value.text.length)
        val end = value.selection.end.coerceIn(start, value.text.length)
        val next = value.text.replaceRange(start, end, text)
        input = TextFieldValue(next, TextRange(start + text.length))
    }

    fun backspace() {
        val value = input
        val start = value.selection.start.coerceIn(0, value.text.length)
        val end = value.selection.end.coerceIn(start, value.text.length)
        input = when {
            start != end -> TextFieldValue(value.text.removeRange(start, end), TextRange(start))
            start == 0 -> value
            else -> TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
        }
    }

    fun clear() {
        input = TextFieldValue("")
        result = ""
        error = null
    }

    fun evaluate() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        error = null
        try {
            val assignment = ASSIGNMENT.matchEntire(text)
            if (assignment != null) {
                val name = assignment.groupValues[1]
                val rhs = assignment.groupValues[2]
                val value = eval(rhs)
                variables[name] = value
                ans = value
                val shown = Numbers.formatComplex(value)
                result = "$name = $shown"
                addHistory(text, shown)
            } else {
                val value = eval(text)
                ans = value
                result = Numbers.formatComplex(value)
                addHistory(text, result)
            }
        } catch (t: Throwable) {
            result = ""
            error = t.message ?: t.javaClass.simpleName
        }
    }

    private fun eval(text: String): Complex {
        val expr = ExpressionParser.parse(text)
        val scope = EvalScope(angle)
        scope.vars.putAll(variables)
        scope.ans = ans
        scope.maxIterations = 500_000
        return Evaluator.evaluate(expr, scope)
    }

    private fun addHistory(expression: String, value: String) {
        history.removeAll { it.expression == expression }
        history.add(0, CalcHistory(expression, value))
        if (history.size > 60) history.removeAt(history.lastIndex)
    }

    private companion object {
        val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$")
    }
}

/**
 * The full-screen maths workspace opened from a note or a canvas: calculator, 2D and 3D plots,
 * scalar and vector fields, numerical calculus, symbolic calculus, units and a searchable reference.
 * Every pane shares the app palette and the same text-field and button vocabulary so it reads as one
 * instrument rather than eight unrelated screens.
 */
@Composable
internal fun MathStudioScreen(onClose: () -> Unit, onInsertGraph: (ByteArray) -> Unit) {
    val palette = LocalPalette.current
    BackHandler(onBack = onClose)

    var tabName by rememberSaveable { mutableStateOf(MathTab.CALCULATOR.name) }
    val tab = MathTab.entries.firstOrNull { it.name == tabName } ?: MathTab.CALCULATOR
    val calculator = remember { CalculatorState() }
    val plot2d = remember { Plot2DState() }
    val plot3d = remember { Plot3DState() }
    val fields = remember { FieldState() }
    val calculus = remember { CalculusState() }
    val symbolic = remember { SymbolicState() }
    val units = remember { UnitState() }

    fun useInCalculator(text: String) {
        calculator.input = TextFieldValue(text, TextRange(text.length))
        tabName = MathTab.CALCULATOR.name
    }

    Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor())) {
        MathStudioBar(onClose = onClose)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(palette.panel.toComposeColor())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (entry in MathTab.entries) {
                MathTabChip(entry, selected = entry == tab) { tabName = entry.name }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                MathTab.CALCULATOR -> CalculatorPane(calculator)
                MathTab.GRAPH_2D -> Plot2DPane(plot2d, onInsertGraph)
                MathTab.GRAPH_3D -> Plot3DPane(plot3d, onInsertGraph)
                MathTab.FIELDS -> FieldPane(fields, onInsertGraph)
                MathTab.CALCULUS -> CalculusPane(calculus)
                MathTab.SYMBOLIC -> SymbolicPane(symbolic)
                MathTab.UNITS -> UnitPane(units)
                MathTab.CATALOG -> CatalogPane(::useInCalculator)
            }
        }
    }
}

@Composable
private fun MathStudioBar(onClose: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(palette.panel.toComposeColor())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MathIconButton(XnotesIcons.prev, stringResource(R.string.math_close), onClick = onClose)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Outlined.Functions,
            contentDescription = null,
            tint = palette.accent.toComposeColor(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.math_studio),
                color = palette.text.toComposeColor(),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.math_subtitle),
                color = palette.textDim.toComposeColor(),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(R.string.math_reference_count, MathCatalog.functions.size, MathCatalog.constants.size),
            color = palette.textDim.toComposeColor(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun MathTabChip(tab: MathTab, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            tab.icon,
            contentDescription = null,
            tint = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor(),
            modifier = Modifier.size(15.dp),
        )
        Text(
            stringResource(tab.labelRes),
            color = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor(),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------- calculator

@Composable
private fun CalculatorPane(state: CalculatorState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        if (wide) {
            Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CalculatorBody(state, Modifier.weight(1f))
                HistoryPanel(state, Modifier.width(290.dp).fillMaxHeight())
            }
        } else {
            CalculatorBody(state, Modifier.fillMaxSize().padding(8.dp))
        }
    }
}

@Composable
private fun CalculatorBody(state: CalculatorState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    Column(modifier.verticalScroll(rememberScrollState())) {
        MathCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.math_expression).uppercase(),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f),
                )
                if (state.variables.isNotEmpty()) {
                    Text(
                        state.variables.entries.joinToString("  ") { (k, v) -> "$k=${Numbers.formatComplex(v)}" },
                        color = palette.textDim.toComposeColor(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.input = it; state.error = null },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = palette.text.toComposeColor(),
                    fontSize = 21.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                minLines = 2,
                maxLines = 4,
                placeholder = {
                    Text(
                        "sin(x)^2 + cos(x)^2",
                        color = palette.textDim.toComposeColor(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); state.evaluate() }),
                colors = mathFieldColors(),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MathPrimaryButton(stringResource(R.string.math_evaluate), Modifier.weight(1f)) {
                    focus.clearFocus()
                    state.evaluate()
                }
                AngleChip(state)
                MathSecondaryButton(stringResource(R.string.math_clear)) { state.clear() }
            }
            if (state.error != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    state.error.orEmpty(),
                    color = palette.danger.toComposeColor(),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (state.result.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.surface.toComposeColor())
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "=",
                        color = palette.accent.toComposeColor(),
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.result,
                        color = palette.text.toComposeColor(),
                        fontSize = 21.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.history.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.math_history).uppercase(),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 9.sp,
                        letterSpacing = 1.1.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.math_clear_history),
                        color = palette.accent.toComposeColor(),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { state.history.clear() }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.history.take(20)) { item ->
                        Column(
                            Modifier
                                .widthIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.surface.toComposeColor())
                                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                                .clickable {
                                    state.input = TextFieldValue(item.expression, TextRange(item.expression.length))
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Text(
                                item.expression,
                                color = palette.textDim.toComposeColor(),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "= ${item.result}",
                                color = palette.text.toComposeColor(),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CalculatorKeypad(state)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.math_calculator_hint),
            color = palette.textDim.toComposeColor(),
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun AngleChip(state: CalculatorState) {
    val palette = LocalPalette.current
    val degrees = state.angle == AngleMode.DEGREES
    Text(
        if (degrees) stringResource(R.string.math_angle_deg) else stringResource(R.string.math_angle_rad),
        color = palette.accent.toComposeColor(),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(8.dp))
            .clickable { state.angle = if (degrees) AngleMode.RADIANS else AngleMode.DEGREES }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

@Composable
private fun HistoryPanel(state: CalculatorState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    MathCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.math_history),
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.history.isNotEmpty()) {
                Text(
                    stringResource(R.string.math_clear),
                    color = palette.accent.toComposeColor(),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { state.history.clear() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (state.history.isEmpty()) {
            Text(
                stringResource(R.string.math_no_history),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(state.history) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surface.toComposeColor())
                            .clickable {
                                state.input = TextFieldValue(item.expression, TextRange(item.expression.length))
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            item.expression,
                            color = palette.textDim.toComposeColor(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "= ${item.result}",
                            color = palette.text.toComposeColor(),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private enum class KeyKind { NUMBER, FUNCTION, OPERATOR, ACTION }

private enum class KeyAction { INSERT, CLEAR, BACKSPACE, EVALUATE }

private data class CalcKey(
    val label: String,
    val insert: String = "",
    val kind: KeyKind = KeyKind.NUMBER,
    val action: KeyAction = KeyAction.INSERT,
    val weight: Float = 1f,
)

private val CALC_ROWS: List<List<CalcKey>> = listOf(
    listOf(
        CalcKey("sin", "sin(", KeyKind.FUNCTION), CalcKey("cos", "cos(", KeyKind.FUNCTION),
        CalcKey("tan", "tan(", KeyKind.FUNCTION), CalcKey("ln", "ln(", KeyKind.FUNCTION),
        CalcKey("log", "log(", KeyKind.FUNCTION),
    ),
    listOf(
        CalcKey("asin", "asin(", KeyKind.FUNCTION), CalcKey("acos", "acos(", KeyKind.FUNCTION),
        CalcKey("atan", "atan(", KeyKind.FUNCTION), CalcKey("sinh", "sinh(", KeyKind.FUNCTION),
        CalcKey("cosh", "cosh(", KeyKind.FUNCTION),
    ),
    listOf(
        CalcKey("tanh", "tanh(", KeyKind.FUNCTION), CalcKey("√", "sqrt(", KeyKind.FUNCTION),
        CalcKey("x²", "^2", KeyKind.FUNCTION), CalcKey("xʸ", "^", KeyKind.FUNCTION),
        CalcKey("1/x", "1/(", KeyKind.FUNCTION),
    ),
    listOf(
        CalcKey("(", "(", KeyKind.OPERATOR), CalcKey(")", ")", KeyKind.OPERATOR),
        CalcKey("π", "pi", KeyKind.FUNCTION), CalcKey("e", "e", KeyKind.FUNCTION),
        CalcKey("i", "i", KeyKind.FUNCTION),
    ),
    listOf(
        CalcKey("7"), CalcKey("8"), CalcKey("9"), CalcKey("÷", "/", KeyKind.OPERATOR),
        CalcKey("C", kind = KeyKind.ACTION, action = KeyAction.CLEAR),
    ),
    listOf(
        CalcKey("4"), CalcKey("5"), CalcKey("6"), CalcKey("×", "*", KeyKind.OPERATOR),
        CalcKey("⌫", kind = KeyKind.ACTION, action = KeyAction.BACKSPACE),
    ),
    listOf(
        CalcKey("1"), CalcKey("2"), CalcKey("3"), CalcKey("−", "-", KeyKind.OPERATOR),
        CalcKey("Ans", "ans", KeyKind.FUNCTION),
    ),
    listOf(
        CalcKey("0"), CalcKey(".", "."), CalcKey("+", "+", KeyKind.OPERATOR),
        CalcKey("=", kind = KeyKind.ACTION, action = KeyAction.EVALUATE, weight = 2f),
    ),
)

@Composable
private fun CalculatorKeypad(state: CalculatorState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in CALC_ROWS) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (key in row) {
                    CalcKeyButton(key, Modifier.weight(key.weight)) {
                        when (key.action) {
                            KeyAction.INSERT -> state.insert(key.insert)
                            KeyAction.CLEAR -> state.clear()
                            KeyAction.BACKSPACE -> state.backspace()
                            KeyAction.EVALUATE -> state.evaluate()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalcKeyButton(key: CalcKey, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val bg = when (key.kind) {
        KeyKind.NUMBER -> palette.surface.toComposeColor()
        KeyKind.FUNCTION -> palette.panel.toComposeColor()
        KeyKind.OPERATOR -> palette.surfaceHi.toComposeColor()
        KeyKind.ACTION -> palette.accent.toComposeColor()
    }
    val fg = when (key.kind) {
        KeyKind.ACTION -> palette.onAccent.toComposeColor()
        KeyKind.OPERATOR -> palette.accent.toComposeColor()
        else -> palette.text.toComposeColor()
    }
    Box(
        modifier
            .height(43.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label,
            color = fg,
            fontSize = if (key.label.length > 2) 13.sp else 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (key.kind == KeyKind.ACTION) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------------------------- catalog

@Composable
private fun CatalogPane(onUse: (String) -> Unit) {
    val palette = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val categories = remember { listOf<String?>(null) + MathCatalog.categories + "Constants" }
    val shownFunctions = MathCatalog.functions.filter { f ->
        (category == null || (category != "Constants" && f.category == category)) &&
            (q.isEmpty() || f.name.lowercase().contains(q) || f.aliases.any { it.lowercase().contains(q) } ||
                f.syntax.lowercase().contains(q) || f.description.lowercase().contains(q))
    }
    val shownConstants = if (category == null || category == "Constants") {
        MathCatalog.constants.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.value.lowercase().contains(q) ||
                it.description.lowercase().contains(q)
        }
    } else emptyList()

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        MathTextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.math_catalog_search),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (entry in categories) {
                val label = entry ?: stringResource(R.string.math_catalog_all)
                MathChip(label, selected = category == entry) { category = entry }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.math_catalog_count, shownFunctions.size + shownConstants.size),
            color = palette.textDim.toComposeColor(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(5.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (shownConstants.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.math_catalog_constants).uppercase(),
                        color = palette.accent.toComposeColor(),
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
                items(shownConstants) { c ->
                    CatalogRow(
                        title = c.name,
                        detail = c.value,
                        description = c.description,
                        onClick = { onUse(c.name) },
                    )
                }
            }
            if (shownFunctions.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.math_catalog_functions).uppercase(),
                        color = palette.accent.toComposeColor(),
                        fontSize = 10.sp,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(shownFunctions) { f ->
                    CatalogRow(
                        title = f.syntax,
                        detail = f.category,
                        description = f.description,
                        onClick = { onUse("${f.name}(") },
                    )
                }
            }
            if (shownFunctions.isEmpty() && shownConstants.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.math_catalog_empty),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(title: String, detail: String, description: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            Icons.Outlined.Functions,
            contentDescription = null,
            tint = palette.accent.toComposeColor(),
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    detail,
                    color = palette.textDim.toComposeColor(),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                description,
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// -------------------------------------------------------------------------------- shared bits

@Composable
internal fun MathCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
            .padding(11.dp),
        content = content,
    )
}

@Composable
internal fun MathSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalPalette.current.text.toComposeColor(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun MathHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = modifier,
    )
}

@Composable
internal fun MathPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) palette.accent.toComposeColor() else palette.surfaceHi.toComposeColor())
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) palette.onAccent.toComposeColor() else palette.disabled.toComposeColor(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun MathSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) palette.text.toComposeColor() else palette.disabled.toComposeColor(),
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun MathIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint ?: (if (enabled) palette.textDim else palette.disabled).toComposeColor(),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
internal fun MathChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        text,
        color = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor(),
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(
                1.dp,
                if (selected) Color.Transparent else palette.border.toComposeColor(),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
internal fun MathTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = TextStyle(
            color = LocalPalette.current.text.toComposeColor(),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        ),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = mathFieldColors(),
        shape = RoundedCornerShape(9.dp),
    )
}

@Composable
internal fun mathFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LocalPalette.current.text.toComposeColor(),
    unfocusedTextColor = LocalPalette.current.text.toComposeColor(),
    focusedBorderColor = LocalPalette.current.accent.toComposeColor(),
    unfocusedBorderColor = LocalPalette.current.border.toComposeColor(),
    focusedLabelColor = LocalPalette.current.accent.toComposeColor(),
    unfocusedLabelColor = LocalPalette.current.textDim.toComposeColor(),
    cursorColor = LocalPalette.current.accent.toComposeColor(),
    focusedContainerColor = LocalPalette.current.surface.toComposeColor(),
    unfocusedContainerColor = LocalPalette.current.surface.toComposeColor(),
)

/** A result block shared by the calculus, symbolic and unit panes. */
@Composable
internal fun MathResultCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    isError: Boolean = false,
) {
    val palette = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(palette.surface.toComposeColor())
            .border(
                1.dp,
                (if (isError) palette.danger else palette.border).toComposeColor(),
                RoundedCornerShape(9.dp),
            )
            .padding(10.dp),
    ) {
        Text(
            title.uppercase(),
            color = (if (isError) palette.danger else palette.textDim).toComposeColor(),
            fontSize = 9.sp,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = if (isError) palette.danger.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        if (detail != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                color = palette.textDim.toComposeColor(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
internal fun MathToolSelector(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for ((key, label) in options) {
            MathChip(label, selected = key == selected) { onSelect(key) }
        }
    }
}

/** A compact labelled number field used throughout the scientific panes. */
@Composable
internal fun MathNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    MathTextField(value, onValueChange, label, modifier, singleLine = true)
}

@Composable
internal fun RowScope.MathWeightedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    weight: Float = 1f,
) {
    MathTextField(value, onValueChange, label, Modifier.weight(weight))
}

@Composable
internal fun MathDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.border.toComposeColor()))
}
