package com.example.trashcompass

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 *  - The main instance is asked first and never raced against itself;
 *    independent mirrors are hedged backups. See [fetch].
 */
object OverpassClient {

    // The main instance: its load balancer, then its two servers directly (the
    // balancer answers "504" whenever the server it picked is busy). All three
    // count against the same per-address allowance.
    private val mainServers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter"
    )

    // Independent mirrors run by other operators.
    private val mirrors = listOf(
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private const val USER_AGENT = "TrashCompass/3.8 (https://github.com/Unpiloted0852/TrashCompass)"

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
     * Fetches amenities, being careful with the public servers' limits.
     *
     * The main instance allows each address only a few queries at a time and
     * answers "429 Too Many Requests" beyond that; its load balancer and its
     * two servers share that allowance. So those three are asked one after
     * another, never raced: the next is tried only when one is down or busy
     * ("504"), and after a 429 the client waits as long as the server asks
     * (Retry-After) and tries once more instead of sending more requests.
     *
     * The independent mirrors are different operators with their own limits,
     * so they are started in parallel as a hedge if the main instance has
     * not answered after [MIRROR_HEDGE_DELAY_MS]. The first success wins and
     * everything else is cancelled.
     *
     * Suspending and main-safe: network I/O happens on OkHttp's own threads
     * via enqueue(), and cancellation propagates to the underlying calls.
     *
     * @throws ServerBusyException if a server was reachable but too busy.
     * @throws IOException if nothing could be reached at all.
     */
    suspend fun fetch(query: String): List<Amenity> = coroutineScope {
        val hedges = mirrors.shuffled()
        val results = Channel<Result<List<Amenity>>>(capacity = 1 + hedges.size)
        val jobs = ArrayList<Job>()
        jobs += launch { results.send(runCatching { fetchFromMainInstance(query) }) }
        hedges.forEachIndexed { index, server ->
            jobs += launch {
                delay(MIRROR_HEDGE_DELAY_MS * (index + 1))
                results.send(runCatching { fetchFrom(server, query) }.onFailure { logFailure(server, it) })
            }
        }
        var received = 0
        var answer: List<Amenity>? = null
        var busy = false
        var lastError: Throwable? = null
        while (received < jobs.size && answer == null) {
            val r = results.receive()
            received++
            r.fold(
                onSuccess = { answer = it },
                onFailure = {
                    lastError = it
                    if (it is HttpStatusException && it.isBusy) busy = true
                }
            )
        }
        for (job in jobs) job.cancel()
        answer ?: if (busy) throw ServerBusyException("Overpass servers are busy", lastError)
        else throw IOException("All Overpass servers failed", lastError)
    }

    /** The main instance's balancer, then its servers, strictly one at a time. */
    private suspend fun fetchFromMainInstance(query: String): List<Amenity> {
        var last: Throwable = IOException("No main server configured")
        for (server in mainServers) {
            try {
                return fetchFrom(server, query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                logFailure(server, e)
                last = e
                if (e.code == 429) {
                    // Over our allowance, which the other servers share: wait, then ask once more.
                    delay((e.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_S).coerceIn(2L, MAX_RETRY_AFTER_S) * 1000L)
                    return fetchFrom(server, query)
                }
                // 504 and the like: this server is busy, the next one may not be.
            } catch (e: Exception) {
                logFailure(server, e)
                last = e
            }
        }
        throw last
    }

    private fun logFailure(server: String, error: Throwable) {
        if (error !is CancellationException) Log.w("TrashCompass", "Overpass: $server failed: $error")
    }

    /** The servers answered, but were rate limiting us or overloaded. Worth retrying shortly. */
    class ServerBusyException(message: String, cause: Throwable?) : IOException(message, cause)

    private class HttpStatusException(val code: Int, val retryAfterSeconds: Long?, server: String) :
        IOException("HTTP $code from $server") {
        val isBusy: Boolean get() = code == 429 || code == 503 || code == 504
    }

    private const val MIRROR_HEDGE_DELAY_MS = 4000L
    private const val DEFAULT_RETRY_AFTER_S = 8L
    private const val MAX_RETRY_AFTER_S = 15L

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
                            if (!it.isSuccessful) {
                                throw HttpStatusException(it.code, it.header("Retry-After")?.trim()?.toLongOrNull(), server)
                            }
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
