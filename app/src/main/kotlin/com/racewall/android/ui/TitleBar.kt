package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.racewall.android.R
import com.racewall.android.state.Layouts
import com.racewall.android.state.isBuiltIn
import com.racewall.android.widgets.WidgetSpec
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the title bar can ask of the window. */
class TitleActions(
    val switchLayout: (String) -> Unit,
    /** Each returns a problem to show, or "" when done. */
    val newLayout: () -> String,
    val renameLayout: (String, String) -> String,
    val moveLayout: (Int, Int) -> Unit,
    val deleteLayout: (String) -> Unit,
    val addWidget: (String) -> String,
    val resetLayout: (String) -> Unit,
    val toggleEdit: () -> Unit,
    val openCommandBar: () -> Unit,
    val toggleSettings: () -> Unit,
)

/**
 * The title bar, as the desktop's TitleBar.svelte: logo, layout tabs, the command bar button, in
 * edit mode the Add widget menu, Edit layout, and Settings. In edit mode tabs the user made can be
 * renamed (double tap), deleted (the x) and moved (drag); the + adds a layout.
 */
@Composable
fun TitleBar(
    layouts: Layouts,
    edit: Boolean,
    settingsOpen: Boolean,
    widgets: List<WidgetSpec>,
    chromeAlpha: Float,
    actions: TitleActions,
) {
    var addOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            delay(2600)
            notice = ""
        }
    }
    /** The layout whose name is being typed. */
    var renaming by remember { mutableStateOf<String?>(null) }
    /** The layout waiting for "Delete" to be confirmed. */
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(edit) {
        if (!edit) {
            renaming = null
            deleting = null
            addOpen = false
        }
    }
    val mids = remember { mutableStateMapOf<Int, Float>() }
    val widths = remember { mutableStateMapOf<Int, Float>() }
    /** A tab held down in edit mode, and how far it has been dragged. */
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .alpha(chromeAlpha)
            .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Logo()
        Row(Modifier.fillMaxHeight().semantics { contentDescription = "Layouts" }, verticalAlignment = Alignment.CenterVertically) {
            val list = layouts.list
            val from = dragFrom
            // Where the held tab would land if let go now.
            val target = if (from == null || abs(dragDx) <= 4) -1 else {
                val centre = (mids[from] ?: 0f) + dragDx
                list.indices.count { it != from && (mids[it] ?: 0f) < centre }
            }
            fun shift(i: Int): Float {
                if (from == null || target < 0) return 0f
                val w = widths[from] ?: 0f
                return when {
                    i == from -> dragDx
                    from < target && i > from && i <= target -> -w
                    target < from && i >= target && i < from -> w
                    else -> 0f
                }
            }
            list.forEachIndexed { i, layout ->
                val name = layout.name
                Box(
                    Modifier
                        .fillMaxHeight()
                        .offset { IntOffset(shift(i).roundToInt(), 0) }
                        .background(if (from == i && target >= 0) Rw.hover else Color.Transparent)
                        .onGloballyPositioned { c ->
                            mids[i] = c.positionInParent().x + c.size.width / 2f
                            widths[i] = c.size.width.toFloat()
                        },
                ) {
                    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        if (edit && renaming == name) {
                            RenameBox(name, onDone = { to, leaving ->
                                val problem = actions.renameLayout(name, to)
                                notice = problem
                                if (problem.isEmpty() || leaving) renaming = null
                            }, onCancel = { renaming = null })
                        } else {
                            Tab(
                                name = name,
                                active = name == layouts.active,
                                edit = edit,
                                onOpen = { actions.switchLayout(name) },
                                onRename = { if (edit && !isBuiltIn(name)) { deleting = null; renaming = name } },
                                onDrag = { dx -> if (edit) { dragFrom = i; dragDx = dx.coerceIn((mids[0] ?: 0f) - (mids[i] ?: 0f), (mids[list.size - 1] ?: 0f) - (mids[i] ?: 0f)) } },
                                onDrop = {
                                    val t = target
                                    if (dragFrom != null && t >= 0 && t != i) actions.moveLayout(i, t)
                                    dragFrom = null
                                    dragDx = 0f
                                },
                            )
                            if (edit && !isBuiltIn(name)) {
                                RwButton(onClick = { renaming = null; deleting = name }, label = "Delete layout $name", horizontalPadding = 0f, modifier = Modifier.padding(start = 0.dp, end = 4.dp).offset(x = (-8).dp).size(20.dp)) { c ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(R.drawable.ic_dismiss, Rw.t3, 9f) }
                                }
                            }
                        }
                    }
                    if (edit && deleting == name) {
                        Popup(alignment = Alignment.TopStart, offset = with(density) { IntOffset(0, 40.dp.roundToPx()) }, onDismissRequest = { deleting = null }, properties = PopupProperties(focusable = true)) {
                            Column(
                                Modifier.widthIn(min = 220.dp).background(Rw.bg, RoundedCornerShape(6.dp)).border(1.dp, Rw.border2, RoundedCornerShape(6.dp)).padding(10.dp)
                                    .semantics { contentDescription = "Delete layout" },
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                BasicText("Delete layout \"$name\"?", style = Rw.body)
                                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RwButton(onClick = { deleting = null }, label = "Cancel") { c -> BasicText("Cancel", style = Rw.text(13f, c)) }
                                    RwButton(onClick = { actions.deleteLayout(name); deleting = null }, on = true, label = "Delete") { c -> BasicText("Delete", style = Rw.text(13f, c)) }
                                }
                            }
                        }
                    }
                }
            }
            if (edit) {
                RwButton(onClick = { deleting = null; renaming = actions.newLayout() }, label = "New layout", horizontalPadding = 0f, modifier = Modifier.padding(start = 4.dp).size(26.dp)) { c ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(R.drawable.ic_add, Rw.t3) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (notice.isNotEmpty()) BasicText(notice, style = Rw.text(13f, Rw.t2), modifier = Modifier.padding(end = 8.dp))
        RwButton(onClick = actions.openCommandBar, label = "Command bar") { c ->
            Icon(R.drawable.ic_search, c)
            Box(Modifier.border(1.dp, Rw.border2, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp)) { BasicText("Ctrl K", style = Rw.text(11f, Rw.t3)) }
        }
        if (edit) {
            Box {
                RwButton(onClick = { addOpen = !addOpen }, label = "Add widget") { c ->
                    Icon(R.drawable.ic_add, c)
                    BasicText("Add widget", style = Rw.text(13f, c))
                }
                if (addOpen) {
                    Popup(alignment = Alignment.TopEnd, offset = with(density) { IntOffset(0, 32.dp.roundToPx()) }, onDismissRequest = { addOpen = false }, properties = PopupProperties(focusable = true)) {
                        Column(
                            Modifier.widthIn(min = 250.dp).heightIn(max = 520.dp).background(Rw.bg, RoundedCornerShape(6.dp)).border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                                .verticalScroll(rememberScrollState()).padding(4.dp).semantics { contentDescription = "Add widget menu" },
                        ) {
                            for (w in widgets) MenuItem(w.title, "Built in") {
                                addOpen = false
                                notice = actions.addWidget(w.id)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp).drawBehind { drawLine(Rw.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }) {
                                MenuItem("Reset \"${layouts.active}\" to default", "") {
                                    addOpen = false
                                    actions.resetLayout(layouts.active)
                                }
                            }
                        }
                    }
                }
            }
        }
        RwButton(onClick = actions.toggleEdit, on = edit, label = "Edit layout") { c ->
            Icon(R.drawable.ic_edit, c)
            BasicText(if (edit) "Done" else "Edit layout", style = Rw.text(13f, c))
        }
        RwButton(onClick = actions.toggleSettings, on = settingsOpen, label = "Settings", horizontalPadding = 8f) { c -> Icon(R.drawable.ic_settings, c) }
    }
}

@Composable
private fun MenuItem(text: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(horizontal = 10.dp).semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, style = Rw.text(13f, Rw.t2), modifier = Modifier.weight(1f), maxLines = 1)
        if (hint.isNotEmpty()) BasicText(hint, style = Rw.text(13f, Rw.t3), maxLines = 1)
    }
}

/** A layout tab: dim, the active one bright with the thin red line under it. */
@Composable
private fun Tab(name: String, active: Boolean, edit: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onDrag: (Float) -> Unit, onDrop: () -> Unit) {
    val editNow by rememberUpdatedState(edit)
    var lastTap by remember { mutableStateOf(0L) }
    Box(
        Modifier
            .fillMaxHeight()
            .drawBehind { if (active) drawLine(Rw.brand, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        val dx = c.position.x - down.position.x
                        if (editNow && (moved || abs(dx) > 4 * density)) {
                            moved = true
                            c.consume()
                            onDrag(dx)
                        }
                    }
                    if (moved) onDrop() else {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) onRename() else onOpen()
                        lastTap = now
                    }
                }
            }
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "Layout $name" }
            .testTag("tab-$name"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(name, style = Rw.text(13f, if (active) Rw.t1 else Rw.t3), maxLines = 1)
    }
}

/** Typing a layout's new name: Enter saves it, Escape or leaving it saves or drops it. */
@Composable
private fun RenameBox(name: String, onDone: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { if (it.text.length <= 40) value = it },
        singleLine = true,
        textStyle = Rw.text(13f, Rw.t1),
        cursorBrush = SolidColor(Rw.t1),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(value.text, false) }),
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(130.dp)
            .height(26.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onCancel()
                    true
                } else false
            }
            .onFocusChanged {
                if (focused && !it.isFocused) onDone(value.text, true)
                focused = it.isFocused
            }
            .semantics { contentDescription = "Layout name" }
            .testTag("rename"),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().background(Rw.bg, RoundedCornerShape(4.dp)).border(1.dp, if (focused) Rw.t3 else Rw.border2, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

private fun Modifier.fillMaxSize() = this.then(Modifier.fillMaxWidth().fillMaxHeight())

/** "Race" in the light grey and "Wall" in the brand red, in Russo One, 16 px, 14 before the tabs. */
@Composable
fun Logo(size: Float = 16f) {
    BasicText(
        androidx.compose.ui.text.buildAnnotatedString {
            append("Race")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Rw.brand))
            append("Wall")
            pop()
        },
        style = Rw.text(size, Rw.t1, family = Rw.logo),
        modifier = Modifier.padding(end = 14.dp).testTag("logo"),
    )
}
