package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

class Command(val label: String, val hint: String, val run: () -> Unit)

/** The commands matching every word typed, the first nine, as the desktop's CommandBar.svelte. */
fun matching(commands: List<Command>, query: String): List<Command> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return commands.filter { c -> words.all { "${c.label} ${c.hint}".lowercase().contains(it) } }.take(9)
}

/**
 * Ctrl+K, or the search button: type a few letters of what you want and press Enter. A 560 wide
 * box 56 below the top, over everything.
 */
@Composable
fun CommandBar(commands: List<Command>, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val matches = matching(commands, query)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun choose(c: Command?) {
        if (c == null) return
        onClose()
        c.run()
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }.testTag("command-bar-backdrop")) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .width(560.dp)
                .background(Rw.bg, RoundedCornerShape(8.dp))
                .border(1.dp, Rw.border2, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = "Command bar" },
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    selected = 0
                },
                singleLine = true,
                textStyle = Rw.text(14f, Rw.t1),
                cursorBrush = SolidColor(Rw.t1),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { choose(matches.getOrNull(selected)) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { selected = minOf(selected + 1, matches.size - 1); true }
                            Key.DirectionUp -> { selected = maxOf(selected - 1, 0); true }
                            Key.Enter, Key.NumPadEnter -> { choose(matches.getOrNull(selected)); true }
                            Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Command" }
                    .testTag("command-input"),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) BasicText("Type a command, such as focus VER or add telemetry", style = Rw.text(14f, Rw.t3))
                        inner()
                    }
                },
            )
            if (matches.isEmpty()) BasicText("No matching command.", style = Rw.text(13f, Rw.t3), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            matches.forEachIndexed { i, c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(if (i == selected) Rw.hover else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, null) { choose(c) }
                        .padding(horizontal = 14.dp)
                        .testTag("command-$i"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(c.label, style = Rw.text(13f, if (i == selected) Rw.t1 else Rw.t2), modifier = Modifier.weight(1f), maxLines = 1)
                    BasicText(c.hint, style = Rw.text(13f, Rw.t3), maxLines = 1)
                }
            }
        }
    }
}
