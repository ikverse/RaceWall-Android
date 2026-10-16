package com.racewall.bus

/**
 * Merges one change record from the F1 timing feed into the state built so far.
 *
 * The feed sends a full object once and then only what changed. Objects merge key by key. An
 * array is sent whole the first time; later changes to it arrive as an object keyed by index
 * ({"2": {...}}), which patches just those entries. A `_deleted` list removes keys.
 *
 * `target` is changed in place and returned. Nothing from `delta` is kept by reference, so the
 * same delta can be applied again later (the timeline replays deltas from keyframes).
 */
@Suppress("UNCHECKED_CAST")
fun mergeFeed(target: Any?, delta: Any?): Any? {
    if (delta !is Map<*, *> && delta !is List<*>) return delta
    if (delta is List<*>) return deepClone(delta)

    val patch = delta as Map<String, Any?>
    if (target is MutableList<*>) {
        val list = target as MutableList<Any?>
        for ((key, value) in patch) {
            val index = key.toIntOrNull() ?: continue
            if (index < 0) continue
            while (list.size <= index) list.add(null)
            list[index] = mergeFeed(list[index], value)
        }
        return list
    }

    val out = (target as? MutableMap<String, Any?>) ?: LinkedHashMap()
    for ((key, value) in patch) {
        if (key == "_deleted") {
            if (value is List<*>) for (k in value) out.remove(jsString(k))
            continue
        }
        out[key] = mergeFeed(out[key], value)
    }
    return out
}
