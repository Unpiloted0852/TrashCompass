package com.example.trashcompass

import android.location.Location

/**
 * "What's around me?" -- lists every kind of thing OpenStreetMap has mapped
 * within a short walk, by plain name, so people can discover what there is
 * to find without knowing what to type.
 */
object NearbyExplorer {

    const val RADIUS_METERS = 300

    /** One kind of feature found nearby, e.g. "Bench", 12 of them, nearest 40 m away. */
    data class Group(val label: String, val target: String, val count: Int, val nearestMeters: Float)

    /** Groups sorted nearest first. Throws IOException if every Overpass mirror fails. */
    suspend fun explore(here: Location): List<Group> {
        val query = OverpassClient.buildNearbyQuery(here.latitude, here.longitude, RADIUS_METERS)
        val elements = OverpassClient.fetch(query)

        class Acc(val label: String, val target: String) {
            var count = 0
            var nearest = Float.MAX_VALUE
        }
        val groups = LinkedHashMap<String, Acc>()
        for (element in elements) {
            val tags = element.tags ?: continue
            val preset = FeatureIndex.describe(tags)
            val label: String
            val target: String
            if (preset != null) {
                label = preset.label
                target = preset.target
            } else {
                // No specific preset: fall back to the element's main tag, prettified.
                val key = (OverpassClient.NEARBY_KEYS + OverpassClient.NEARBY_NODE_KEYS)
                    .firstOrNull { tags.has(it) } ?: continue
                val value = tags.optString(key)
                if (value.isEmpty() || value == "no") continue
                label = if (value == "yes") prettyTagName(key) else prettyTagName(value)
                target = "$key=$value"
            }
            val acc = groups.getOrPut(target) { Acc(label, target) }
            acc.count++
            val distance = here.distanceTo(element.location)
            if (distance < acc.nearest) acc.nearest = distance
        }
        return groups.values
            .map { Group(it.label, it.target, it.count, it.nearest) }
            .sortedBy { it.nearestMeters }
    }
}
