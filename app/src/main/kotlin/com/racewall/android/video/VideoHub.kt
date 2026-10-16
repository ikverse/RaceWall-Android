package com.racewall.android.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the hub needs of one F1 TV player. */
interface PlayerHandle {
    val paused: Boolean
    val ended: Boolean
    /** Video time, seconds. */
    var currentTime: Double
    fun play()
    fun pause()
    val contentId: Long?
    /** Session time for the video's current time, when known (synced replay, or live). */
    fun sessionTime(): Double?
    /** Moves the video to a session time, when the mapping is known. */
    fun seekSession(t: Double): Boolean
    /** The part of the video that can be played, in video seconds; for live, the rewindable window. */
    fun seekRange(): ClosedFloatingPointRange<Double>
    val live: Boolean
    /** True once a stream has loaded: a player that is still loading has nothing to carry on from. */
    val loaded: Boolean
}

/**
 * What the players that left were doing, for the players of the next layout to carry on from.
 * Switching layouts destroys the panels of the old one before the new ones load, and the session
 * clock keeps running in between: a replay picks up where the clock has got to, a live player the
 * same distance behind live, playing or paused as before, and the sound moves with it.
 */
data class Handoff(
    /** The session the players were watching; a handoff for another session is dropped. */
    val session: Int?,
    /** Seconds behind the live edge, when the leader was live. */
    val behind: Double?,
    val playing: Boolean,
    /** Whether a player that left had the sound. */
    var audible: Boolean,
    /** The loaded players still registered when it was recorded: while any remains, it is theirs to continue. */
    val others: List<String>,
)

/**
 * Coordinates the F1 TV players, as the desktop's video.svelte.ts. Each one follows the timing
 * until the user moves it with its own controls; then it plays on its own until the bottom bar's
 * play or pause brings everyone back. The leader (the main player, or the first one still
 * following) drives the session clock and the others follow it. Only one player has sound.
 */
class VideoHub(
    /** The loaded session's key, so a handoff is not carried into another session. */
    private val sessionKey: () -> Int?,
    /** Where the bottom bar's clock is, for a player that rejoins the timing. */
    private val clockTime: () -> Double = { 0.0 },
) {
    /** Panel id of the player the clock follows while it follows the timing. */
    var main by mutableStateOf<String?>(null)

    /** Panel id of the player with sound; null while every player is muted, as they start. */
    var audible by mutableStateOf<String?>(null)

    /** True while a player drives the session clock. */
    var driving by mutableStateOf(false)

    /** Panel ids of the players on their own, moved with their own controls. */
    var alone by mutableStateOf<List<String>>(emptyList())

    /** Each player's header text. */
    val metas = mutableStateMapOf<String, String>()

    private val players = LinkedHashMap<String, PlayerHandle>()
    private var handoff: Handoff? = null

    fun register(panelId: String, handle: PlayerHandle) {
        players[panelId] = handle
        if (main == null) main = panelId
    }

    fun unregister(panelId: String) {
        val handle = players[panelId]
        val leading = leaderId() == panelId
        val sound = audible == panelId
        if (handle != null && (leading || sound)) {
            val kept = handoff?.takeIf { it.session == sessionKey() }
            val others = players.filter { (id, p) -> id != panelId && p.loaded }.keys.toList()
            handoff = Handoff(
                session = sessionKey(),
                behind = if (leading) (if (handle.live) maxOf(0.0, handle.seekRange().endInclusive - handle.currentTime) else null) else kept?.behind,
                playing = if (leading) !handle.paused && !handle.ended else kept?.playing ?: false,
                audible = sound || (kept?.audible ?: false),
                others = others,
            )
        }
        players.remove(panelId)
        metas.remove(panelId)
        alone = alone.filter { it != panelId }
        if (main == panelId) main = players.keys.firstOrNull()
        if (audible == panelId) audible = null
        if (leaderId() == null) driving = false
    }

    /**
     * The handoff for a player about to load its stream, or null when there is none for it: none was
     * recorded, it was for another session, or a player from before is still here to carry on itself.
     * The leader takes it; a follower only reads it. The sound goes to the main player, once.
     */
    fun takeHandoff(panelId: String): Handoff? {
        val h = handoff ?: return null
        if (h.session != sessionKey() || h.others.any { it in players }) {
            handoff = null
            return null
        }
        if (h.audible && audible == null) audible = main ?: panelId
        h.audible = false
        if (leaderId() == panelId) handoff = null
        return h.copy()
    }

    /** For tests: forgets any handoff. */
    fun clearHandoff() {
        handoff = null
    }

    /** Panel id of the player the clock and the other followers follow, if any still follows. */
    fun leaderId(): String? {
        val m = main
        if (m != null && m in players && m !in alone) return m
        return players.keys.firstOrNull { it !in alone }
    }

    fun leader(): PlayerHandle? = leaderId()?.let { players[it] }

    /** The players following the leader. */
    fun followers(): List<PlayerHandle> {
        val lead = leaderId()
        return players.filter { (id, _) -> id != lead && id !in alone }.values.toList()
    }

    /** This player follows the timing again (the main player, when it was paused to mark the start). */
    fun rejoin(panelId: String) {
        alone = alone.filter { it != panelId }
    }

    /** The user moved this player with its own controls: it stops following the timing. */
    fun goAlone(panelId: String) {
        if (panelId in alone) return
        alone = alone + panelId
        if (leaderId() == null) driving = false
    }

    /**
     * Every player follows the timing again. With no player left following, the main player first
     * moves to where the bottom bar is (the live moment, when live).
     */
    private fun rejoinAll(): PlayerHandle? {
        val hadLeader = leader()
        alone = emptyList()
        val lead = leader()
        if (lead != null && hadLeader == null) {
            if (lead.live) lead.currentTime = lead.seekRange().endInclusive else lead.seekSession(clockTime())
        }
        return lead
    }

    sealed class Command {
        object Toggle : Command()
        class SeekBy(val ms: Double) : Command()
        class Seek(val time: Double) : Command()
    }

    /**
     * The bottom bar's controls. Play and pause bring every player back to the timing; seeking moves
     * the leader, and the players still following go with it. Returns false when no player is in
     * charge, so the clock should act instead.
     */
    fun control(cmd: Command): Boolean {
        if (players.isEmpty() || (!driving && alone.isEmpty())) return false
        if (cmd is Command.Toggle) {
            val playing = leader()?.paused == false
            val lead = rejoinAll() ?: return false
            if (playing) lead.pause() else lead.play()
            return true
        }
        val lead = leader() ?: return false
        return when (cmd) {
            is Command.SeekBy -> {
                lead.currentTime = maxOf(0.0, lead.currentTime + cmd.ms / 1000)
                true
            }
            is Command.Seek -> lead.seekSession(cmd.time)
            else -> false
        }
    }
}
