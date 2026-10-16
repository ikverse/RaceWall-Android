package com.racewall.bus

/** A topic's value at session time `t` (ms), or null when this source has none. */
typealias TopicView = (t: Double) -> Any?

/** Every value between two moments, optionally for some keys only, or null. */
typealias RangeView = (from: Double, to: Double, filter: List<String>?) -> Any?

class TopicProvider(
    /** The data source this comes from, such as "f1-archive". */
    val source: String,
    val at: TopicView,
    val range: RangeView? = null,
)

/** A value, and which source gave it. */
data class Answer(val value: Any, val source: String)

/**
 * The message bus: for each topic, the data sources that can provide it, in the user's order.
 *
 * Asking for a topic at time t goes to the first source, in order, that has a value. So if the
 * preferred source has nothing (it is down, or it does not carry that topic for this session), the
 * next one answers, and every widget keeps working. Widgets never see a source directly.
 */
class Bus {
    private val topics = LinkedHashMap<String, MutableList<TopicProvider>>()
    private var order: List<String> = emptyList()

    /** Adds or replaces what `provider.source` offers for `topic`. */
    @Synchronized
    fun provide(topic: String, provider: TopicProvider) {
        val list = (topics[topic] ?: mutableListOf()).filterTo(mutableListOf()) { it.source != provider.source }
        list.add(provider)
        topics[topic] = list
        sort(topic)
    }

    /** Shorthand for a provider without range queries. */
    fun register(topic: String, source: String = "default", view: TopicView) = provide(topic, TopicProvider(source, view))

    /** Removes everything a source provides. */
    @Synchronized
    fun removeSource(source: String) {
        val it = topics.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.removeAll { p -> p.source == source }
            if (e.value.isEmpty()) it.remove()
        }
    }

    @Synchronized
    fun unregister(topic: String) {
        topics.remove(topic)
    }

    @Synchronized
    fun clear() = topics.clear()

    /** Sources earlier in the list are asked first. Sources not listed come after, in the order added. */
    @Synchronized
    fun setOrder(sources: List<String>) {
        order = sources.toList()
        for (topic in topics.keys) sort(topic)
    }

    @Synchronized
    fun has(topic: String): Boolean = topic in topics

    @Synchronized
    fun names(): List<String> = topics.keys.toList()

    @Synchronized
    fun sourcesOf(topic: String): List<String> = topics[topic]?.map { it.source } ?: emptyList()

    fun stateAt(topic: String, t: Double): Any? = answer(topic, t)?.value

    @Suppress("UNCHECKED_CAST")
    fun <T> typed(topic: String, t: Double): T? = stateAt(topic, t) as T?

    fun answer(topic: String, t: Double): Answer? {
        val providers = synchronized(this) { topics[topic]?.toList() } ?: return null
        for (p in providers) {
            val value = p.at(t)
            if (value != null) return Answer(value, p.source)
        }
        return null
    }

    fun range(topic: String, from: Double, to: Double, filter: List<String>? = null): Any? {
        val providers = synchronized(this) { topics[topic]?.toList() } ?: return null
        for (p in providers) {
            val value = p.range?.invoke(from, to, filter)
            if (value != null) return value
        }
        return null
    }

    private fun sort(topic: String) {
        fun rank(s: String): Int = order.indexOf(s).let { if (it < 0) Int.MAX_VALUE else it }
        // A stable sort, like Array.prototype.sort, so unlisted sources keep the order they came in.
        topics[topic]?.sortBy { rank(it.source) }
    }
}
