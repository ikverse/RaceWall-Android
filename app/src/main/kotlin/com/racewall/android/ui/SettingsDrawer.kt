package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.racewall.android.R
import com.racewall.android.account.AccountStatus
import java.text.DateFormat
import java.util.Date

/** The right-hand drawer's frame, as the desktop's: 380 wide, black, a line on its left, a 44 high head. */
@Composable
fun Drawer(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(Rw.bg)
            .drawBehind { drawLine(Rw.border, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) }
            // Taps on the drawer never reach the panels behind it.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = title }
            .testTag("drawer-$title"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(title, style = Rw.text(14f), modifier = Modifier.weight(1f))
            RwButton(onClick = onClose, label = "Close ${title.lowercase()}", horizontalPadding = 8f) { c -> Icon(R.drawable.ic_dismiss, c) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), content = content)
    }
}

/** A section heading: small and dim. */
@Composable
fun Section(text: String) {
    BasicText(text, style = Rw.text(11f, Rw.t3), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
}

/** One setting: its label and note on the left, its control on the right. */
@Composable
fun Option(label: String, note: String, warn: String = "", control: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = Rw.body)
            if (note.isNotEmpty()) BasicText(note, style = Rw.text(11f, Rw.t3), modifier = Modifier.padding(top = 1.dp))
            if (warn.isNotEmpty()) BasicText(warn, style = Rw.text(11f, Rw.red), modifier = Modifier.padding(top = 1.dp))
        }
        control()
    }
}

/** The desktop's switch: a 34 x 18 outline with a dot that moves right and brightens when on. */
@Composable
fun Switch(on: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(34.dp, 18.dp)
            .border(1.dp, if (on) Rw.t2 else Rw.border2, RoundedCornerShape(9.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(!on) }
            .semantics {
                contentDescription = label
                stateDescription = if (on) "On" else "Off"
            }
            .testTag("switch-$label"),
    ) {
        Box(Modifier.offset(x = if (on) 19.dp else 3.dp, y = 3.dp).size(10.dp).background(if (on) Rw.t1 else Rw.t3, CircleShape))
    }
}

/** The F1 account section, as the desktop Settings drawer's. */
@Composable
fun AccountSection(account: AccountStatus, busy: Boolean, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    Section("F1 account")
    if (account.signedIn) {
        val until = account.expires?.let { "Valid until ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))}. " } ?: ""
        Option(
            "Signed in${if (account.product.isNotEmpty()) " · ${account.product}" else ""}",
            "${until}Used for the timing archive, live timing and F1 TV video.",
            account.problem,
        ) { RwButton(onClick = onSignOut, label = "Sign out") { c -> BasicText("Sign out", style = Rw.text(13f, c)) } }
    } else {
        Option(
            "Not signed in",
            "F1's timing archive needs an F1 TV subscription, and the full live feed too. You sign in on formula1.com's own page.",
            account.problem,
        ) {
            RwButton(onClick = onSignIn, on = true, enabled = !busy, label = "Sign in") { c ->
                BasicText(if (busy) "Waiting…" else "Sign in", style = Rw.text(13f, c))
            }
        }
    }
}
