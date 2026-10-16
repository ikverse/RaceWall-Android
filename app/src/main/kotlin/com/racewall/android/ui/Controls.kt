package com.racewall.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.racewall.android.R

/** A Fluent icon at the desktop's 13px icon size, tinted like text. */
@Composable
fun Icon(@DrawableRes id: Int, color: Color, size: Float = 13f) {
    Image(painterResource(id), contentDescription = null, colorFilter = ColorFilter.tint(color), modifier = Modifier.size(size.dp))
}

/**
 * The desktop's button: 28 high, 4 round, padding 0 10, text t2; t1 on press. `on` gives it the
 * thin border-2 outline the desktop uses for the chosen or main action.
 */
@Composable
fun RwButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    enabled: Boolean = true,
    label: String? = null,
    horizontalPadding: Float = 10f,
    content: @Composable RowScope.(color: Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val color = if (!enabled) Rw.t3 else if (pressed || on) Rw.t1 else Rw.t2
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .height(28.dp)
            .background(if (pressed) Rw.hover else Color.Transparent, shape)
            .border(1.dp, if (on) Rw.border2 else Color.Transparent, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (label != null) contentDescription = label }
            .padding(horizontal = horizontalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content(color) }
}

/**
 * The desktop's `<select>`: black, a border, 4 round, 28 high, the chosen option and a chevron.
 * Tapping opens the options in a list just below or above it.
 */
@Composable
fun <T> RwSelect(
    value: T,
    options: List<Pair<T, String>>,
    onChange: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fontSize: Float = 12f,
    maxWidth: Float = 320f,
    openUpwards: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == value }?.second ?: ""
    val shape = RoundedCornerShape(4.dp)
    Box(modifier) {
        Row(
            Modifier
                .height(28.dp)
                .widthIn(max = maxWidth.dp)
                .background(Rw.bg, shape)
                .border(1.dp, Rw.border, shape)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.DropdownList) { open = true }
                .semantics { contentDescription = label }
                .testTag(label)
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(text, style = Rw.text(fontSize, Rw.t2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(R.drawable.ic_chevron_down, Rw.t2, 12f)
        }
        if (open) {
            Popup(
                alignment = if (openUpwards) Alignment.BottomStart else Alignment.TopStart,
                offset = IntOffset(0, 0),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .widthIn(min = 80.dp, max = 360.dp)
                        .heightIn(max = 420.dp)
                        .background(Rw.bg, RoundedCornerShape(6.dp))
                        .border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                ) {
                    for ((v, name) in options) {
                        val chosen = v == value
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(if (chosen) Rw.hover else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    open = false
                                    if (!chosen) onChange(v)
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicText(name, style = Rw.text(fontSize, if (chosen) Rw.t1 else Rw.t2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The desktop's range input with `accent-color: #8a8a8a`: a rounded track, filled up to the
 * thumb, moved by tapping or dragging. Steps of `step` like the desktop's 1 s.
 */
@Composable
fun RwSlider(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    onChange: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val span = (max - min).coerceAtLeast(1e-9)
    fun valueAt(x: Float, width: Float, thumb: Float): Double {
        val f = ((x - thumb / 2) / (width - thumb)).coerceIn(0f, 1f)
        val raw = min + f * span
        return (min + Math.round((raw - min) / step) * step).coerceIn(min, max)
    }
    Canvas(
        modifier
            .height(28.dp)
            .semantics { contentDescription = label; role = Role.Button }
            .testTag(label)
            .pointerInput(min, max) { detectTapGestures { onChange(valueAt(it.x, size.width.toFloat(), 16.dp.toPx())) } }
            .pointerInput(min, max) {
                detectDragGestures { change, _ -> onChange(valueAt(change.position.x, size.width.toFloat(), 16.dp.toPx())) }
            },
    ) {
        val thumb = 16.dp.toPx()
        val trackH = 8.dp.toPx()
        val f = ((value - min) / span).toFloat().coerceIn(0f, 1f)
        val cx = thumb / 2 + f * (size.width - thumb)
        val cy = size.height / 2
        val r = CornerRadius(trackH / 2)
        drawRoundRect(Rw.t1, Offset(0f, cy - trackH / 2), Size(size.width, trackH), r)
        drawRoundRect(Rw.t2, Offset(0f, cy - trackH / 2), Size(cx, trackH), r)
        drawCircle(Rw.t2, thumb / 2, Offset(cx, cy))
    }
}
