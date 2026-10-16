package com.racewall.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.racewall.android.Frame
import com.racewall.android.R
import com.racewall.android.account.AccountStatus
import com.racewall.android.Mode
import com.racewall.android.UiState

/** The desktop's grid: 48 columns by 30 rows. */
const val GRID_COLS = 48
const val GRID_ROWS = 30

/** A panel's place on the grid, in cells. */
data class Cells(val x: Int, val y: Int, val w: Int, val h: Int)

/** The timing tower's place in the desktop's Race layout. */
val TIMING_CELLS = Cells(0, 0, 13, 30)

/** What the window can ask of the core beyond the transport bar. */
interface AppActions : TransportActions {
    /** Checks and keeps a token found after signing in on formula1.com. */
    suspend fun signedIn(token: String)
    fun signOut()
}

/** The whole window: title bar, the panel canvas, the transport bar, and the drawers over them. */
@Composable
fun RaceWallApp(
    frame: Frame,
    ui: UiState,
    mode: Mode,
    settings: TimingSettings,
    onFocus: (String) -> Unit,
    actions: AppActions,
    account: AccountStatus = AccountStatus(),
) {
    var drawer by rememberSaveable { mutableStateOf<String?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Rw.bg)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar {
                RwButton(onClick = { drawer = if (drawer == "settings") null else "settings" }, on = drawer == "settings", label = "Settings", horizontalPadding = 8f) { c ->
                    Icon(R.drawable.ic_settings, c)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Canvas {
                    Panel("Timing", timingMeta(frame, mode)) {
                        TimingTower(frame, settings, ui.focusDriver, onFocus)
                    }
                }
            }
            Transport(frame, ui, actions)
        }
        if (drawer == "settings") {
            Box(Modifier.fillMaxSize().padding(top = 38.dp), contentAlignment = Alignment.TopEnd) {
                Drawer("Settings", onClose = { drawer = null }) {
                    AccountSection(account, busy = signingIn, onSignIn = { signingIn = true }, onSignOut = {
                        clearSignIn()
                        actions.signOut()
                    })
                }
            }
        }
        if (signingIn) SignInDialog(onToken = { actions.signedIn(it) }, onClose = { signingIn = false })
    }
}

/** The title bar: 38 high, the logo on the left and the window's buttons on the right, a line below. */
@Composable
fun TitleBar(right: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .drawBehind { drawLine(Rw.border, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Logo()
        Spacer(Modifier.weight(1f))
        right()
    }
}

/** "Race" in the light grey and "Wall" in the brand red, in Russo One. */
@Composable
fun Logo(size: Float = 16f) {
    BasicText(
        buildAnnotatedString {
            append("Race")
            withStyle(SpanStyle(color = Rw.brand)) { append("Wall") }
        },
        style = Rw.text(size, Rw.t1, family = Rw.logo),
        modifier = Modifier.padding(end = 14.dp).testTag("logo"),
    )
}

/** The panel canvas, 6 in from the edges, with the timing tower at its grid place. */
@Composable
private fun Canvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
        val cw = maxWidth / GRID_COLS
        val ch = maxHeight / GRID_ROWS
        val c = TIMING_CELLS
        Box(Modifier.offset(cw * c.x, ch * c.y).size(cw * c.w, ch * c.h).padding(3.dp)) { content() }
    }
}

/** A panel: a thin border, 6 round, a 26 high header with its title and meta text. */
@Composable
fun Panel(title: String, meta: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxSize().clip(shape).border(1.dp, Rw.border, shape)) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(title, style = Rw.text(12f, Rw.t2), maxLines = 1)
            BasicText(meta, style = Rw.text(12f, Rw.t3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("meta"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
