package com.example.trashcompass

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to the Overpass API.
 *
 * Speed fixes vs. the old implementation:
 *  - POST instead of GET. GET URLs have length limits and some proxies
 *    mangle them; POST is the documented way to send larger queries.
 *  - "[timeout:25]" in every query. Without it the server default is
 *    180 seconds, so a struggling mirror could hold the app hostage for
 *    3 minutes before we ever tried the next one.
 *  - "nwr" (node+way+relation) clauses instead of separate node/way
 *    clauses. One clause instead of two-per-filter is less work for the
 *    server AND finally includes relations (multipolygon parks,
 *    hospital grounds, etc.) which the old app silently missed.
 *  - Shorter connect timeout (8s) and an overall call timeout, so a
 *    dead mirror fails fast and we move on.
 *  - The main instance is asked first; the mirrors are backups, shuffled
 *    per fetch to spread load, and every server gets one retry, because
 *    the public instances regularly answer "too busy" for a moment.
 */
object OverpassClient {

    // The main instance's load balancer first, then its two servers directly
    // (the balancer answers "504" whenever the server it picked is busy),
    // then independent mirrors.
    private val mainServers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter"
    )

    private val mirrors = listOf(
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private const val USER_AGENT = "TrashCompass/3.6 (https://github.com/Unpiloted0852/TrashCompass)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(32, TimeUnit.SECONDS)
        .build()

    /**
     * Builds an Overpass QL query for [target] around a point.
     *
     * [target] resolution order:
     *  1. A known entry in [TagRepository.mapping]. The mapped value may
     *     contain several clauses joined by " OR " (each searched
     *     independently), and a clause may contain several filters
     *     joined by "&" (all must match, e.g.
     *     "amenity=recycling&recycling_type=centre").
     *     A filter is "key=value" for exact match or "key~regex".
     *  2. A tag filter in the same syntax ("key=value", "&", " OR "), as
     *     produced by the preset index, the tag browser, or typed by hand.
     *     "key=*" means the key with any value.
     *  3. Free text: matched against "name" (case-insensitive) plus a
     *     snake_case guess against common feature keys.
     */
    fun buildQuery(target: String, lat: Double, lon: Double, radiusMeters: Int): String {
        val around = String.format(Locale.US, "(around:%d,%.7f,%.7f)", radiusMeters, lat, lon)
        val body = StringBuilder()

        val spec = TagRepository.mapping[target]
        when {
            spec != null -> {
                for (clause in spec.split(" OR ")) appendClause(body, clause.trim(), around)
            }
            target.contains("=") -> {
                for (clause in target.split(" OR ")) appendClause(body, clause.trim(), around)
            }
            else -> {
                val nameRegex = escapeOverpassRegex(target)
                val snake = target.lowercase(Locale.US).trim().replace(Regex("\\s+"), "_")
                    .replace("\"", "").replace("\\", "")
                body.append("nwr[\"name\"~\"").append(nameRegex).append("\",i]").append(around).append(';')
                val fallbackKeys = listOf(
                    "amenity", "shop", "leisure", "tourism", "natural", "historic",
                    "highway", "emergency", "man_made", "craft", "office", "sport",
                    "building", "healthcare"
                )
                if (snake.isNotEmpty()) {
                    for (k in fallbackKeys) {
                        body.append("nwr[\"").append(k).append("\"=\"").append(snake).append("\"]")
                            .append(around).append(';')
                    }
                }
            }
        }
        return "[out:json][timeout:25];(${body});out center qt;"
    }

    /**
     * Everything that is a "thing" on the map close to a point, for the
     * "What's around me?" list. Buildings, land use and plain roads are left
     * out on purpose: they are everywhere and not what people look for.
     */
    fun buildNearbyQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        val around = String.format(Locale.US, "(around:%d,%.7f,%.7f)", radiusMeters, lat, lon)
        val anyKeys = NEARBY_KEYS.joinToString("|")
        val nodeKeys = NEARBY_NODE_KEYS.joinToString("|")
        return "[out:json][timeout:25];(" +
                "nwr$around[~\"^($anyKeys)\$\"~\".\"];" +
                "node$around[~\"^($nodeKeys)\$\"~\".\"];" +
                ");out center qt 1500;"
    }

    /** Keys that make an element a feature in its own right. */
    val NEARBY_KEYS = listOf(
        "amenity", "shop", "leisure", "tourism", "historic", "emergency", "man_made",
        "craft", "office", "healthcare", "natural", "public_transport", "aeroway",
        "playground", "advertising", "information", "club", "sport"
    )

    /** Keys that are only interesting as single points (crossings, stops, gates...). */
    val NEARBY_NODE_KEYS = listOf("highway", "railway", "barrier", "traffic_calming")

    private fun appendClause(sb: StringBuilder, clause: String, around: String) {
        if (clause.isEmpty()) return
        val filters = StringBuilder()
        for (part in clause.split("&")) {
            val p = part.trim()
            when {
                p.contains("~") -> {
                    val key = sanitizeKey(p.substringBefore("~"))
                    val regex = p.substringAfter("~").replace("\"", "")
                    filters.append("[\"").append(key).append("\"~\"").append(regex).append("\"]")
                }
                p.contains("=") -> {
                    val key = sanitizeKey(p.substringBefore("="))
                    val value = p.substringAfter("=").trim().replace("\"", "").replace("\\", "")
                    if (value == "*" || value.isEmpty()) {
                        filters.append("[\"").append(key).append("\"]")
                    } else {
                        filters.append("[\"").append(key).append("\"=\"").append(value).append("\"]")
                    }
                }
            }
        }
        if (filters.isNotEmpty()) sb.append("nwr").append(filters).append(around).append(';')
    }

    private fun sanitizeKey(raw: String): String =
        raw.trim().replace("\"", "").replace("\\", "")

    /**
     * Escapes user text so it is safe inside an Overpass regex literal.
     * Without this, typing something like "fish (chips)" or a stray
     * quote produced a malformed query and a confusing "Connection
     * Failed" error.
     */
    private fun escapeOverpassRegex(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            when (c) {
                '\\', '.', '[', ']', '{', '}', '(', ')', '*', '+', '?', '^', '$', '|' -> {
                    sb.append('\\').append('\\').append(c) // \\ in QL string -> \ in regex
                }
                '"' -> { /* drop quotes entirely */ }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Fetches amenities using "hedged" requests to bound tail latency:
     * the main server is queried immediately, and if it hasn't answered
     * within [HEDGE_DELAY_MS], the next mirror is started in parallel, and
     * so on. A server that fails is tried once more after a short pause
     * (the public instances often return "429 Too Many Requests" or
     * "504 Gateway Timeout" when briefly overloaded). The first successful
     * response wins and all other in-flight requests are cancelled.
     *
     * Compared to trying mirrors one at a time, this means one slow or
     * dead mirror costs you ~2.5 s instead of its full timeout, while a
     * healthy first mirror results in exactly one request (so we are
     * not hammering the public servers).
     *
     * Suspending and main-safe: network I/O happens on OkHttp's own
     * threads via enqueue(), and cancellation propagates to the
     * underlying calls.
     *
     * @throws IOException if every mirror fails.
     */
    suspend fun fetch(query: String): List<Amenity> = coroutineScope {
        val order = mainServers + mirrors.shuffled()
        val results = Channel<Result<List<Amenity>>>(capacity = order.size)
        val jobs = order.mapIndexed { index, server ->
            launch {
                delay(index * HEDGE_DELAY_MS)
                var attempt = runCatching { fetchFrom(server, query) }
                // Retry "busy" answers only; a server that timed out is not worth a second wait.
                if (attempt.exceptionOrNull()?.message?.startsWith("HTTP ") == true) {
                    delay(RETRY_DELAY_MS)
                    attempt = runCatching { fetchFrom(server, query) }
                }
                attempt.exceptionOrNull()?.let {
                    if (it !is CancellationException) Log.w("TrashCompass", "Overpass: $server failed: $it")
                }
                results.send(attempt)
            }
        }
        var received = 0
        var answer: List<Amenity>? = null
        var lastError: Throwable? = null
        while (received < order.size && answer == null) {
            val r = results.receive()
            received++
            r.fold(
                onSuccess = { answer = it },
                onFailure = { lastError = it }
            )
        }
        for (job in jobs) job.cancel()
        answer ?: throw IOException("All Overpass mirrors failed", lastError)
    }

    private const val HEDGE_DELAY_MS = 2500L
    private const val RETRY_DELAY_MS = 2000L

    /** One request to one mirror, cancellable via coroutine cancellation. */
    private suspend fun fetchFrom(server: String, query: String): List<Amenity> =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder()
                .url(server)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("data", query).build())
                .build()
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code} from $server")
                            val body = it.body?.string() ?: throw IOException("Empty body from $server")
                            val parsed = parseElements(body)
                            if (cont.isActive) cont.resume(parsed)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    private fun parseElements(jsonString: String): List<Amenity> {
        val result = ArrayList<Amenity>()
        val root = JSONObject(jsonString)
        val elements = root.optJSONArray("elements") ?: return result
        for (i in 0 until elements.length()) {
            val item = elements.getJSONObject(i)
            val type = item.optString("type", "node")
            val id = item.optLong("id", -1L)
            val tags = if (item.has("tags")) item.getJSONObject("tags") else null

            val lat: Double
            val lon: Double
            if (item.has("lat") && item.has("lon")) {
                lat = item.getDouble("lat")
                lon = item.getDouble("lon")
            } else if (item.has("center")) {
                val center = item.getJSONObject("center")
                lat = center.getDouble("lat")
                lon = center.getDouble("lon")
            } else continue

            val loc = Location("osm")
            loc.latitude = lat
            loc.longitude = lon
            result.add(Amenity("$type/$id", loc, tags))
        }
        return result
    }
}
