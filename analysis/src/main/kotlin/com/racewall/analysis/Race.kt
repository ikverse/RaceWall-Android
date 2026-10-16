package com.racewall.analysis

import com.racewall.topics.LapHistory
import com.racewall.topics.Positions
import com.racewall.topics.RaceControlMessages
import com.racewall.topics.SessionInfo
import com.racewall.topics.SessionStatus
import com.racewall.topics.Stints

/** What the analyses read: the same topics the desktop's analysis plugins subscribe to. */
data class RaceTopics(
    val info: SessionInfo? = null,
    val status: SessionStatus? = null,
    val positions: Positions = emptyList(),
    val lapHistory: LapHistory? = null,
    val stints: Stints? = null,
    val messages: RaceControlMessages? = null,
)

/** Race arithmetic shared by the analyses, as the desktop's plugins/_shared/race.ts. */
object Race {
    private val GAP = Regex("""^\+?(\d+(?:\.\d+)?)$""")

    /** "+1.234" as seconds; null for "LAP 7", "1L", "" and anything else that is not a time gap. */
    fun parseGap(text: String?): Double? = GAP.matchEntire((text ?: "").trim())?.groupValues?.get(1)?.toDouble()

    private val SC_DEPLOYED = Regex("SAFETY CAR DEPLOYED")
    private val SC_ENDING = Regex("SAFETY CAR (IN THIS LAP|ENDING)")
    private val RED = Regex("^RED FLAG")

    /**
     * Laps run behind the safety car, under a virtual safety car, or around a red flag, from race
     * control. Lap numbers are the leader's, so one lap is added at each end for the cars behind.
     */
    fun neutralLaps(messages: RaceControlMessages?): Set<Int> {
        val laps = HashSet<Int>()
        var from: Int? = null
        fun mark(a: Int, b: Int) {
            for (l in maxOf(1, a - 1)..b + 1) laps.add(l)
        }
        for (m in messages ?: emptyList()) {
            val lap = m.lap ?: continue
            val text = m.message.uppercase()
            if (SC_DEPLOYED.containsMatchIn(text)) {
                if (from == null) from = lap
            } else if (SC_ENDING.containsMatchIn(text) && from != null) {
                mark(from, lap)
                from = null
            } else if (m.flag == "RED" || RED.containsMatchIn(text)) mark(lap, lap + 1)
        }
        // Still neutralised now: every lap from the start of it.
        from?.let { mark(it, it + 100) }
        return laps
    }

    /** One driver's lap times in ms, by lap number. */
    fun lapTimes(history: LapHistory?, number: String): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        for (l in history?.get(number) ?: emptyList()) if (l.ms != null) out[l.lap] = l.ms!!
        return out
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    fun isRace(info: SessionInfo?) = info?.name == "Race" || info?.name == "Sprint"
}
