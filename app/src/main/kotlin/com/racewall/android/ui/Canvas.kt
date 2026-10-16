package com.racewall.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.racewall.android.Frame
import com.racewall.android.Mode
import com.racewall.android.R
import com.racewall.android.state.COLS
import com.racewall.android.state.Grid
import com.racewall.android.state.Layouts
import com.racewall.android.state.Panel
import com.racewall.android.state.ROWS
import com.racewall.android.state.Rect
import com.racewall.android.widgets.Values
import com.racewall.android.widgets.WidgetScope
import com.racewall.android.widgets.WidgetSpec
import com.racewall.android.widgets.widget
import kotlinx.serialization.json.JsonElement
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Holding a panel header still this long arms it; the next drag then enters edit mode. */
const val HOLD_MS = 3000L
private const val HOLD_SLOP = 6f

/** What a panel can ask of the window. */
class CanvasActions(
    val setLayouts: (Layouts) -> Unit,
    val setEdit: (Boolean) -> Unit,
    val onFocus: (String) -> Unit,
    val range: (String, Double, Double, List<String>?) -> Any?,
)

/**
 * The panel canvas, as the desktop's Canvas.svelte: the active layout's panels on the 48 x 30 grid,
 * 6 in from the edges. In edit mode the grid shows, borders turn dashed, panels move by their
 * header and resize by their corner, and their bodies stop taking taps.
 */
@Composable
fun PanelCanvas(
    layouts: Layouts,
    frame: Frame,
    mode: Mode,
    focus: String?,
    edit: Boolean,
    chromeAlpha: Float,
    actions: CanvasActions,
    modifier: Modifier = Modifier,
    /** The faint panel fill for LCD screens. */
    dimPanels: Boolean = false,
) {
    val layout = layouts.current()
    val latest by rememberUpdatedState(layouts)
    var openSettings by remember { mutableStateOf<String?>(null) }
    // Arranging panels hides the gear, so its popover goes too, and stays shut afterwards.
    LaunchedEffect(edit) { if (edit) openSettings = null }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(6.dp)
            .drawBehind {
                if (!edit) return@drawBehind
                val c = Color(0xFF0F0F0F)
                for (i in 0..COLS) drawLine(c, Offset(size.width * i / COLS, 0f), Offset(size.width * i / COLS, size.height), 1f)
                for (j in 0..ROWS) drawLine(c, Offset(0f, size.height * j / ROWS), Offset(size.width, size.height * j / ROWS), 1f)
            }
            .testTag("canvas"),
    ) {
        val cw = maxWidth / COLS
        val ch = maxHeight / ROWS
        val density = LocalDensity.current
        val cwPx = with(density) { cw.toPx() }
        val chPx = with(density) { ch.toPx() }

        /** Moves or resizes a panel while the finger is down; saved when it is let go. */
        fun drag(panel: Panel, resize: Boolean, dx: Float, dy: Float, start: Rect) {
            val spec = widget(panel.widget)
            val min = spec?.min ?: (4 to 4)
            val next = Grid.moved(start, (dx / cwPx).roundToInt(), (dy / chPx).roundToInt(), resize, min)
            val current = latest.current().panels
            if (Grid.fits(next, current, panel.id)) actions.setLayouts(latest.withPanels { list -> list.map { if (it.id == panel.id) it.at(next) else it } })
        }

        for (panel in layout.panels) {
            val spec = widget(panel.widget)
            val title = spec?.title ?: panel.widget
            val collapsed = panel.collapsed == true
            // The widget's scope and header text are worked out before the header is drawn.
            val scope = spec?.let {
                WidgetScope(
                    frame = frame.only(it.reads),
                    mode = mode,
                    focus = focus,
                    values = Values(it.fields, panel.settings),
                    onFocus = actions.onFocus,
                    range = actions.range,
                    setSetting = { key, value -> setPanelSetting(latest, panel.id, key, value, actions.setLayouts) },
                    panelId = panel.id,
                )
            }
            val meta = when {
                collapsed -> ""
                spec != null && scope != null -> runCatching { spec.meta(scope) }.getOrDefault("")
                else -> ""
            }
            Box(
                Modifier
                    .offset(cw * panel.x, ch * panel.y)
                    .size(cw * panel.w, if (collapsed) 26.dp + 2.dp + 6.dp else ch * panel.h)
                    .padding(3.dp)
                    .semantics { contentDescription = title }
                    .testTag("panel-${panel.id}"),
            ) {
                PanelFrame(
                    panel = panel,
                    title = title,
                    meta = meta,
                    edit = edit,
                    collapsed = collapsed,
                    chromeAlpha = chromeAlpha,
                    fill = if (dimPanels) Color(0xFF0B0B0B) else Color.Transparent,
                    hasSettings = spec?.fields?.isNotEmpty() == true,
                    onCollapse = {
                        // A collapsed panel stops drawing; expanding it draws the whole current state again.
                        if (!collapsed && openSettings == panel.id) openSettings = null
                        actions.setLayouts(latest.withPanels { list -> list.map { if (it.id == panel.id) it.copy(collapsed = if (collapsed) null else true) else it } })
                    },
                    onGear = { openSettings = if (openSettings == panel.id) null else panel.id },
                    onRemove = { actions.setLayouts(latest.withPanels { list -> list.filter { it.id != panel.id } }) },
                    onHeaderDrag = { dx, dy, start -> drag(panel, false, dx, dy, start) },
                    onArm = {
                        // A collapsed panel opens to its full area, so it can be placed and resized.
                        if (collapsed) actions.setLayouts(latest.withPanels { list -> list.map { if (it.id == panel.id) it.copy(collapsed = null) else it } })
                        actions.setEdit(true)
                    },
                    onResize = { dx, dy, start -> drag(panel, true, dx, dy, start) },
                ) {
                    when {
                        spec != null && scope != null -> spec.content(scope)
                        else -> BasicText("The widget \"${panel.widget}\" is not installed or is turned off.", style = Rw.text(13f, Rw.t3), modifier = Modifier.padding(12.dp))
                    }
                }
                if (openSettings == panel.id && spec != null && !collapsed) {
                    PanelSettings(spec, panel, title, onSet = { key, value -> setPanelSetting(latest, panel.id, key, value, actions.setLayouts) }, onReset = {
                        actions.setLayouts(latest.withPanels { list -> list.map { if (it.id == panel.id) it.copy(settings = emptyMap()) else it } })
                    }, onClose = { openSettings = null })
                }
            }
        }
    }
}

private fun setPanelSetting(layouts: Layouts, id: String, key: String, value: JsonElement, set: (Layouts) -> Unit) =
    set(layouts.withPanels { list -> list.map { if (it.id == id) it.copy(settings = (it.settings ?: emptyMap()) + (key to value)) else it } })

/** One panel: the border, the 26 high header, and the widget below it. */
@Composable
private fun PanelFrame(
    panel: Panel,
    title: String,
    meta: String,
    edit: Boolean,
    collapsed: Boolean,
    chromeAlpha: Float,
    fill: Color,
    hasSettings: Boolean,
    onCollapse: () -> Unit,
    onGear: () -> Unit,
    onRemove: () -> Unit,
    onHeaderDrag: (Float, Float, Rect) -> Unit,
    onArm: () -> Unit,
    onResize: (Float, Float, Rect) -> Unit,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val editNow by rememberUpdatedState(edit)
    val panelNow by rememberUpdatedState(panel)
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (collapsed) it else it.fillMaxSize() }
            .clip(shape)
            .drawBehind {
                drawRect(fill)
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    if (edit) Color(0xFF3A3A3A) else Rw.border,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(6.dp.toPx() - stroke / 2),
                    style = Stroke(stroke, pathEffect = if (edit) PathEffect.dashPathEffect(floatArrayOf(3 * stroke, 3 * stroke)) else null),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .alpha(chromeAlpha)
                .pointerInput(Unit) {
                    // In edit mode the header moves the panel. Otherwise holding it still for three
                    // seconds arms it, and the next drag enters edit mode and carries on as a move.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val start = panelNow.rect
                        if (editNow) {
                            var total = Offset.Zero
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                total += c.positionChange()
                                c.consume()
                                onHeaderDrag(total.x, total.y, start)
                            }
                            return@awaitEachGesture
                        }
                        val armedAt = down.uptimeMillis + HOLD_MS
                        var armed = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: return@awaitEachGesture
                            if (!c.pressed) return@awaitEachGesture
                            val moved = c.position - down.position
                            if (!armed && c.uptimeMillis >= armedAt) armed = true
                            if (hypot(moved.x, moved.y) < HOLD_SLOP * density) continue
                            if (!armed) return@awaitEachGesture
                            onArm()
                            c.consume()
                            onHeaderDrag(moved.x, moved.y, start)
                            while (true) {
                                val m = awaitPointerEvent()
                                val mc = m.changes.firstOrNull() ?: break
                                if (!mc.pressed) break
                                mc.consume()
                                val d = mc.position - down.position
                                onHeaderDrag(d.x, d.y, start)
                            }
                            return@awaitEachGesture
                        }
                    }
                }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(title, style = Rw.text(12f, Rw.t2), maxLines = 1)
            BasicText(meta, style = Rw.text(12f, Rw.t3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("meta-${panel.id}"))
            RwButton(onClick = onCollapse, label = "${if (collapsed) "Expand" else "Collapse"} $title", horizontalPadding = 0f, modifier = Modifier.size(24.dp, 22.dp)) { c ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(if (collapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up, c) }
            }
            if (!edit && !collapsed && hasSettings) {
                RwButton(onClick = onGear, label = "Settings for $title", horizontalPadding = 0f, modifier = Modifier.size(24.dp, 22.dp)) { c ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(R.drawable.ic_settings, c) }
                }
            }
            if (edit) {
                RwButton(onClick = onRemove, label = "Remove $title", horizontalPadding = 0f, modifier = Modifier.size(24.dp, 22.dp)) { c ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(R.drawable.ic_dismiss, c) }
                }
            }
        }
        if (!collapsed) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                body()
                // While arranging, panels are moved, not used.
                if (edit) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
                if (edit) ResizeCorner(panel, onResize, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** The 12 x 12 corner the desktop drags to resize, with a larger touch area around it. */
@Composable
private fun ResizeCorner(panel: Panel, onResize: (Float, Float, Rect) -> Unit, modifier: Modifier) {
    val panelNow by rememberUpdatedState(panel)
    Box(
        modifier
            .size(32.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = panelNow.rect
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        c.consume()
                        val d = c.position - down.position
                        onResize(d.x, d.y, start)
                    }
                }
            }
            .semantics { contentDescription = "Resize" }
            .testTag("resize-${panel.id}"),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(12.dp)
                .drawBehind {
                    val w = 2.dp.toPx()
                    drawLine(Rw.t2, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    drawLine(Rw.t2, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                },
        )
    }
}
