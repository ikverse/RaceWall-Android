package com.racewall.bus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Ported from the desktop's packages/bus/test/bus.test.ts. */
class MergeFeedTest {
    private fun j(text: String) = FeedJson.parse(text)

    @Test
    fun `merges objects key by key`() {
        val s = j("""{"a":1,"b":{"c":2,"d":3}}""")
        assertEquals(j("""{"a":1,"b":{"c":2,"d":4},"e":5}"""), mergeFeed(s, j("""{"b":{"d":4},"e":5}""")))
    }

    @Test
    fun `patches an array through index keys`() {
        @Suppress("UNCHECKED_CAST")
        val s = j("""{"Sectors":[{"Value":""},{"Value":""},{"Value":""}]}""") as Map<String, Any?>
        mergeFeed(s, j("""{"Sectors":{"1":{"Value":"29.123","PersonalFastest":true}}}"""))
        assertEquals(j("""[{"Value":""},{"Value":"29.123","PersonalFastest":true},{"Value":""}]"""), s["Sectors"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `replaces an array sent whole, without keeping the delta by reference`() {
        val delta = j("""{"Stints":[{"Compound":"SOFT"}]}""") as Map<String, Any?>
        val s = mergeFeed(LinkedHashMap<String, Any?>(), delta) as Map<String, Any?>
        ((s["Stints"] as List<Any?>)[0] as MutableMap<String, Any?>)["Compound"] = "HARD"
        assertEquals("SOFT", ((delta["Stints"] as List<Any?>)[0] as Map<String, Any?>)["Compound"])
    }

    @Test
    fun `removes keys listed in _deleted`() {
        assertEquals(j("""{"b":2}"""), mergeFeed(j("""{"a":1,"b":2}"""), j("""{"_deleted":["a"]}""")))
    }

    @Test
    fun `an index patch past the end grows the array`() {
        assertEquals(j("""[1,null,3]"""), mergeFeed(j("[1]"), j("""{"2":3}""")))
    }
}

class TimelineTest {
    private class Sum(var v: Int)

    private fun sum() = Timeline<Sum, Int>({ Sum(0) }, { s, d -> s.v += d; s }, 4, { Sum(it.v) })

    @Test
    fun `answers state at any time, before, between and after changes`() {
        val tl = sum()
        listOf(10.0, 20.0, 30.0).forEachIndexed { i, t -> tl.append(t, i + 1) }
        assertEquals(0, tl.stateAt(5.0).v)
        assertEquals(1, tl.stateAt(10.0).v)
        assertEquals(3, tl.stateAt(25.0).v)
        assertEquals(6, tl.stateAt(99.0).v)
    }

    @Test
    fun `gives the same answer whatever order the questions come in`() {
        val tl = sum()
        val values = mutableListOf<Int>()
        var total = 0
        for (i in 0 until 100) {
            val d = ((i * 7919) % 13) - 6
            tl.append(i * 10.0, d)
            total += d
            values.add(total)
        }
        for (t in listOf(55, 3, 990, 400, 401, 402, 10, 999, 0, 500, 120, 121)) assertEquals(values[t / 10], tl.stateAt(t.toDouble()).v)
    }

    @Test
    fun `refuses changes that go back in time`() {
        val tl = sum()
        tl.append(10.0, 1)
        assertFailsWith<IllegalArgumentException> { tl.append(5.0, 1) }
    }

    @Test
    fun `lists the change times between two moments`() {
        val tl = sum()
        listOf(10.0, 20.0, 30.0, 40.0).forEach { tl.append(it, 1) }
        assertEquals(listOf(20.0, 30.0), tl.timesBetween(10.0, 30.0))
    }
}

class BusTest {
    @Test
    fun `routes questions to the registered topic`() {
        val bus = Bus()
        bus.register("x") { t -> t * 2 }
        assertEquals(42.0, bus.stateAt("x", 21.0))
        assertNull(bus.stateAt("missing", 1.0))
        bus.clear()
        assertFalse(bus.has("x"))
    }

    @Test
    fun `asks sources in the chosen order and falls back when one has nothing`() {
        val bus = Bus()
        bus.provide("w", TopicProvider("openf1", { "openf1" }))
        bus.provide("w", TopicProvider("f1-archive", { t -> if (t < 100) "archive" else null }))
        bus.setOrder(listOf("f1-archive", "openf1"))
        assertEquals(Answer("archive", "f1-archive"), bus.answer("w", 50.0))
        assertEquals(Answer("openf1", "openf1"), bus.answer("w", 150.0))
        bus.setOrder(listOf("openf1", "f1-archive"))
        assertEquals("openf1", bus.stateAt("w", 50.0))
    }

    @Test
    fun `removes everything a source offers`() {
        val bus = Bus()
        bus.provide("a", TopicProvider("s1", { 1 }))
        bus.provide("b", TopicProvider("s1", { 2 }))
        bus.provide("b", TopicProvider("s2", { 3 }))
        bus.removeSource("s1")
        assertFalse(bus.has("a"))
        assertEquals(3, bus.stateAt("b", 0.0))
    }

    @Test
    fun `answers range queries from the first source that supports them`() {
        val bus = Bus()
        bus.provide("r", TopicProvider("s1", { 1 }))
        bus.provide("r", TopicProvider("s2", { 1 }, { from, to, _ -> listOf(from, to) }))
        assertEquals(listOf(1.0, 2.0), bus.range("r", 1.0, 2.0))
    }
}

class ValueStoreTest {
    @Test
    fun `keeps values in time order even when they arrive out of order`() {
        val s = ValueStore<String>()
        s.put(30.0, "c")
        s.put(10.0, "a")
        s.put(20.0, "b")
        s.put(20.0, "b2")
        assertEquals(3, s.size)
        assertEquals(listOf(null, "a", "a", "b2", "b2", "c"), listOf(5, 10, 15, 20, 25, 99).map { s.at(it.toDouble()) })
    }
}
