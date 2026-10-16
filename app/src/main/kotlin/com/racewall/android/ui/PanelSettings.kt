package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.racewall.android.state.Panel
import com.racewall.android.widgets.Field
import com.racewall.android.widgets.Values
import com.racewall.android.widgets.WidgetSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The settings of one panel, shown under its gear, as the desktop's PanelSettings.svelte: 280 wide,
 * 28 below the panel's top, 4 in from its right. Values are saved with the layout.
 */
@Composable
fun PanelSettings(spec: WidgetSpec, panel: Panel, title: String, onSet: (String, JsonElement) -> Unit, onReset: () -> Unit, onClose: () -> Unit) {
    val values = Values(spec.fields, panel.settings)
    val density = androidx.compose.ui.platform.LocalDensity.current
    Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { IntOffset(-4.dp.roundToPx(), 28.dp.roundToPx()) },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(280.dp)
                .heightIn(max = 520.dp)
                .background(Rw.bg, RoundedCornerShape(6.dp))
                .border(1.dp, Rw.border2, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
                .semantics { contentDescription = "Settings for $title" }
                .testTag("settings-${panel.id}"),
        ) {
            for (f in spec.fields) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        BasicText(f.label, style = Rw.body)
                        if (f.note.isNotEmpty()) BasicText(f.note, style = Rw.text(11f, Rw.t3), modifier = Modifier.padding(top = 1.dp))
                    }
                    when (f) {
                        is Field.Toggle -> Switch(values.bool(f.key), f.label) { onSet(f.key, JsonPrimitive(it)) }
                        is Field.Select -> RwSelect(values.str(f.key), f.options, { onSet(f.key, JsonPrimitive(it)) }, f.label, fontSize = 13f, maxWidth = 150f, openUpwards = false)
                        is Field.Number -> NumberBox(values.json(f.key).jsonPrimitive.doubleOrNull ?: f.default, f, f.label) { onSet(f.key, JsonPrimitive(it)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                RwButton(onClick = onReset, label = "Reset") { c -> BasicText("Reset", style = Rw.text(13f, c)) }
            }
        }
    }
}

/** The desktop's `<input type="number">`: 64 wide; a value outside its range or not a number is ignored. */
@Composable
private fun NumberBox(value: Double, f: Field.Number, label: String, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
    BasicTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { onChange(it.coerceIn(f.min, f.max)) }
        },
        singleLine = true,
        textStyle = Rw.text(13f, Rw.t2),
        cursorBrush = SolidColor(Rw.t1),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(64.dp).semantics { contentDescription = label }.testTag(label),
        decorationBox = { inner ->
            Box(
                Modifier.background(Rw.bg, RoundedCornerShape(4.dp)).border(1.dp, Rw.border, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 5.dp),
            ) { inner() }
        },
    )
}
