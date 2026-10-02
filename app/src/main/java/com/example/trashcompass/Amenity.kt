package com.example.trashcompass

import android.location.Location
import org.json.JSONObject

/**
 * One OSM element returned by Overpass.
 *
 * [key] is "type/id" (e.g. "node/123", "way/123"). OSM node, way and
 * relation IDs are separate number spaces, so a bare numeric ID is NOT
 * unique -- a node and a way can share the same number. Using type+id
 * fixes a subtle bug where the app could confuse two different elements.
 */
data class Amenity(
    val key: String,
    val location: Location,
    val tags: JSONObject?
)
