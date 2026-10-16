package com.racewall.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.racewall.android.BuildConfig
import com.racewall.android.Frame
import com.racewall.android.Mode
import com.racewall.android.UiState
import com.racewall.android.account.AccountStatus
import com.racewall.android.state.Grid
import com.racewall.android.state.LayoutOps
import com.racewall.android.state.Layouts
import com.racewall.android.state.Panel
import com.racewall.android.state.Settings
import com.racewall.android.state.VIEWING
import com.racewall.android.state.defaultLayouts
import com.racewall.android.widgets.WIDGETS
import com.racewall.android.widgets.widget
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Burn-in protection timings, as the desktop's App.svelte. */
const val FADE_AFTER_MS = 3000L
const val IDLE_AFTER_MS = 5 * 60_000L
const val SHIFT_EVERY_MS = 3 * 60_000L

/**
 * Whether the chrome fades (`quiet`) and the stage dims (`idle`), from how long it has been since the
 * last touch or key. Neither happens while a drawer, edit mode, the command bar or the picker is open.
 */
fun burnIn(quietForMs: Long, busy: Boolean, playing: Boolean, settings: Settings): Pair<Boolean, Boolean> =
    (!busy && quietForMs > FADE_AFTER_MS) to (settings.idleDim && !busy && !playing && quietForMs > IDLE_AFTER_MS)

/** What the window can ask of the app. */
interface AppActions : TransportActions {
    /** Checks and keeps a token found after signing in on formula1.com. */
    suspend fun signedIn(token: String)
    fun signOut()
    fun seekBy(ms: Double) {}
    fun focus(number: String?) {}
    fun setLayouts(layouts: Layouts) {}
    fun setSettings(settings: Settings) {}
    fun showLastResult() {}
    fun range(topic: String, from: Double, to: Double, filter: List<String>?): Any? = null
    fun toggleFullscreen() {}
    fun leaveLive() {}
}

/** Everything the window draws from. */
data class Screen(
    val frame: Frame = Frame(),
    val ui: UiState = UiState(),
    val mode: Mode = Mode.IDLE,
    val settings: Settings = Settings(),
    val layouts: Layouts = defaultLayouts(),
    val account: AccountStatus = AccountStatus(),
)

/** Adds a widget to the active layout in the first free spot. Returns a problem, or "" when added. */
fun addWidget(layouts: Layouts, id: String): Pair<Layouts, String> {
    val spec = widget(id) ?: return layouts to "That widget is not available."
    val layout = layouts.current()
    val spot = Grid.findSpot(layout.panels, listOf(spec.size, spec.min)) ?: return layouts to "No room. Shrink or remove a panel first."
    val base = id.substringAfterLast('.')
    var n = 1
    while (layout.panels.any { it.id == "$base-$n" }) n++
    return layouts.withPanels { it + Panel("$base-$n", id, spot.x, spot.y, spot.w, spot.h) } to ""
}

private fun switchTo(layouts: Layouts, name: String, actions: AppActions) {
    if (layouts.list.any { it.name == name }) actions.setLayouts(layouts.copy(active = name))
}

/**
 * The whole window, as the desktop's App.svelte: title bar, the panel canvas, the transport bar,
 * and the drawers and command bar over them. Fades its chrome and dims when left alone, and moves
 * the canvas a pixel or two now and then, to spare OLED screens.
 */
@Composable
fun RaceWallApp(screen: Screen, actions: AppActions, video: @Composable (Panel, (String) -> Unit) -> Unit = { _, _ -> }) {
    var edit by rememberSaveable { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf<String?>(null) }
    var commandBar by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val settings = screen.settings
    val layouts by rememberUpdatedState(screen.layouts)

    // Burn-in protection.
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var quiet by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(0 to 0) }
    fun input() {
        lastInput = System.currentTimeMillis()
        quiet = false
        idle = false
    }
    val busyNow by rememberUpdatedState(drawer != null || edit || commandBar || picker)
    val playingNow by rememberUpdatedState(screen.frame.clock.playing)
    val settingsNow by rememberUpdatedState(settings)
    LaunchedEffect(Unit) {
        while (true) {
            val (q, i) = burnIn(System.currentTimeMillis() - lastInput, busyNow, playingNow, settingsNow)
            quiet = q
            idle = i
            delay(500)
        }
    }
    LaunchedEffect(settings.pixelShift) {
        if (!settings.pixelShift) {
            shift = 0 to 0
            return@LaunchedEffect
        }
        while (true) {
            delay(SHIFT_EVERY_MS)
            fun r() = Math.round(Random.nextDouble() * 4 - 2).toInt()
            shift = r() to r()
        }
    }
    val chromeAlpha by animateFloatAsState(if (quiet && settings.fadeChrome) 0.22f else 1f, tween(600), label = "chrome")
    val stageAlpha by animateFloatAsState(if (idle) 0.35f else 1f, tween(1500), label = "idle")
    val shiftX by animateFloatAsState(shift.first.toFloat(), tween(1200), label = "shiftX")
    val shiftY by animateFloatAsState(shift.second.toFloat(), tween(1200), label = "shiftY")

    // Race layout while live, Between sessions after, when the user wants that.
    var lastMode by remember { mutableStateOf<Mode?>(null) }
    LaunchedEffect(screen.mode) {
        val previous = lastMode
        lastMode = screen.mode
        if (!settings.autoLayout || previous == null || previous == screen.mode) return@LaunchedEffect
        if (screen.mode == Mode.LIVE) switchTo(layouts, "Race", actions)
        else if (screen.mode == Mode.IDLE && previous == Mode.LIVE) switchTo(layouts, "Between sessions", actions)
    }

    val titleActions = TitleActions(
        switchLayout = { switchTo(layouts, it, actions) },
        newLayout = { LayoutOps.newLayout(layouts).let { (l, name) -> actions.setLayouts(l); name } },
        renameLayout = { from, to -> LayoutOps.rename(layouts, from, to).let { (l, problem) -> if (problem.isEmpty()) actions.setLayouts(l); problem } },
        moveLayout = { a, b -> actions.setLayouts(LayoutOps.move(layouts, a, b)) },
        deleteLayout = { actions.setLayouts(LayoutOps.delete(layouts, it)) },
        addWidget = { id -> addWidget(layouts, id).let { (l, problem) -> if (problem.isEmpty()) actions.setLayouts(l); problem } },
        resetLayout = { actions.setLayouts(LayoutOps.reset(layouts, it)) },
        toggleEdit = { edit = !edit },
        openCommandBar = { commandBar = true },
        toggleSettings = { drawer = if (drawer == "settings") null else "settings" },
    )

    val keys = remember { FocusRequester() }
    LaunchedEffect(commandBar) { if (!commandBar) runCatching { keys.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Rw.bg)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        input()
                    }
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                input()
                when {
                    e.isCtrlPressed && e.key == Key.K -> { commandBar = !commandBar; true }
                    commandBar -> false
                    e.key == Key.Escape -> { drawer = null; edit = false; picker = false; true }
                    e.key == Key.Spacebar -> { actions.toggle(); true }
                    e.key == Key.DirectionRight -> { actions.seekBy(if (e.isShiftPressed) 30_000.0 else 5_000.0); true }
                    e.key == Key.DirectionLeft -> { actions.seekBy(if (e.isShiftPressed) -30_000.0 else -5_000.0); true }
                    e.key == Key.E && !e.isCtrlPressed -> { edit = !edit; true }
                    e.key == Key.F11 -> { actions.toggleFullscreen(); true }
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable()
            .testTag("app"),
    ) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(layouts, edit, drawer == "settings", WIDGETS, chromeAlpha, titleActions)
            Box(Modifier.weight(1f).fillMaxWidth().alpha(stageAlpha).offset(shiftX.dp, shiftY.dp)) {
                PanelCanvas(
                    layouts = layouts,
                    frame = screen.frame,
                    mode = screen.mode,
                    focus = screen.ui.focusDriver,
                    edit = edit,
                    chromeAlpha = chromeAlpha,
                    dimPanels = settings.dimPanels,
                    actions = CanvasActions(actions::setLayouts, { edit = it }, { n -> actions.focus(if (screen.ui.focusDriver == n) null else n) }, actions::range, video),
                )
            }
            Transport(screen.frame, screen.ui, actions, picker, { picker = it }, chromeAlpha)
        }
        if (drawer == "settings") {
            Box(Modifier.fillMaxSize().padding(top = 38.dp), contentAlignment = Alignment.TopEnd) {
                Drawer("Settings", onClose = { drawer = null }) {
                    SettingsSections(screen, actions, signingIn, onSignIn = { signingIn = true })
                }
            }
        }
        if (commandBar) {
            val commands = commands(screen, actions, layouts, edit, setEdit = { edit = it }, openSettings = { drawer = "settings" }, openPicker = { picker = true })
            CommandBar(commands, onClose = { commandBar = false })
        }
        if (signingIn) SignInDialog(onToken = { actions.signedIn(it) }, onClose = { signingIn = false })
    }
}

/** The command bar's commands, as the desktop's CommandBar.svelte. */
private fun commands(
    screen: Screen,
    actions: AppActions,
    layouts: Layouts,
    edit: Boolean,
    setEdit: (Boolean) -> Unit,
    openSettings: () -> Unit,
    openPicker: () -> Unit,
): List<Command> {
    val list = ArrayList<Command>()
    for (d in screen.frame.drivers.values.sortedBy { it.code }) list.add(Command("Focus ${d.code}", "${d.firstName} ${d.lastName}, ${d.team}") { actions.focus(d.number) })
    for (l in layouts.list) list.add(Command("Switch to ${l.name}", "Layout") { switchTo(layouts, l.name, actions) })
    for (w in WIDGETS) list.add(Command("Add ${w.title}", "Widget") { addWidget(layouts, w.id).let { (l, problem) -> if (problem.isEmpty()) actions.setLayouts(l) } })
    list += listOf(
        Command("Play or pause", "Space") { actions.toggle() },
        Command("Back 30 seconds", "Shift ←") { actions.seekBy(-30_000.0) },
        Command("Forward 30 seconds", "Shift →") { actions.seekBy(30_000.0) },
        Command(if (screen.mode == Mode.LIVE) "Go live" else "Connect to live timing", "Live") { actions.goLive() },
    )
    if (screen.mode == Mode.LIVE) list += listOf(
        Command("Sync the broadcast delay", "Press as the leader crosses the line") { actions.syncDelay() },
        Command("Leave live timing", "Live") { actions.leaveLive() },
    )
    list += listOf(
        Command("Sign in with your F1 account", "Account", openSettings),
        Command("Pick a session to replay", "Replay", openPicker),
        Command("Show the last result", "Replay") { actions.showLastResult() },
        Command(if (edit) "Done editing" else "Edit layout", "E") { setEdit(!edit) },
        Command("Open settings", "", openSettings),
        Command("Full screen", "F11") { actions.toggleFullscreen() },
    )
    return list
}

/** The Settings drawer's sections, as the desktop's, without the Mica theme and the desktop-only ones. */
@Composable
private fun SettingsSections(screen: Screen, actions: AppActions, signingIn: Boolean, onSignIn: () -> Unit) {
    val s = screen.settings
    fun set(next: Settings) = actions.setSettings(next)
    Section("Appearance")
    Option("Dim panels", "Faint panel fill, for LCD screens") { Switch(s.dimPanels, "Dim panels") { set(s.copy(dimPanels = it)) } }
    Section("Burn-in protection")
    Option("Pixel shift", "Moves the canvas 1 to 2 pixels every few minutes") { Switch(s.pixelShift, "Pixel shift") { set(s.copy(pixelShift = it)) } }
    Option("Fade toolbars and headers", "After 3 seconds without a touch") { Switch(s.fadeChrome, "Fade toolbars and headers") { set(s.copy(fadeChrome = it)) } }
    Option("Dim when idle", "After 5 minutes paused with no input") { Switch(s.idleDim, "Dim when idle") { set(s.copy(idleDim = it)) } }

    AccountSection(screen.account, busy = signingIn, onSignIn = onSignIn, onSignOut = {
        clearSignIn()
        actions.signOut()
    })

    Section("Live timing")
    Option("Connect when a session is on", "From 15 minutes before; never interrupts a replay you chose") { Switch(s.autoLive, "Connect when a session is on") { set(s.copy(autoLive = it)) } }
    Option("Switch layouts by themselves", "Race while live, Between sessions after") { Switch(s.autoLayout, "Switch layouts by themselves") { set(s.copy(autoLayout = it)) } }
    Option("How you watch", "Sets how far data is held back to match your picture. Starting points only: press Sync once to set yours.") {
        RwSelect(s.viewing, VIEWING, { set(s.copy(viewing = it)) }, "How you watch", fontSize = 13f, maxWidth = 170f, openUpwards = false)
    }
    Option("Delay ${Math.round(s.delay() / 1000.0)} s", "During a live session, press Sync as the leader crosses the line on your screen.") {
        RwButton(onClick = actions::syncDelay, enabled = screen.mode == Mode.LIVE, label = "Sync") { c -> BasicText("Sync", style = Rw.text(13f, c)) }
    }

    Section("Notifications")
    Option("A session starts soon", "15 minutes before") { Switch(s.notifySoon, "A session starts soon") { set(s.copy(notifySoon = it)) } }
    Option("Red flag", "While following a live session") { Switch(s.notifyRed, "Red flag") { set(s.copy(notifyRed = it)) } }
    Option("Safety car", "Safety car and virtual safety car") { Switch(s.notifySafetyCar, "Safety car") { set(s.copy(notifySafetyCar = it)) } }

    Section("About")
    Option("RaceWall ${BuildConfig.VERSION_NAME}", "Updates come from the RaceWall releases on GitHub.") {}
}
