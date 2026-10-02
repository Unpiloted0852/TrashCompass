package com.example.trashcompass

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Taginfo API (https://taginfo.openstreetmap.org), the
 * OSM project's own index of EVERY key and value that actually exists
 * in the OSM database -- including undocumented ones. OSM tagging is
 * free-form (any key=value is allowed), so no built-in catalog can be
 * complete; this is how the app offers literally all of it.
 *
 * Endpoints and parameters below match the taginfo source
 * (web/lib/api/v4/keys.rb and key.rb in github.com/taginfo/taginfo):
 *   /api/4/keys/all    params: query, page, rp, sortname (count_all...)
 *   /api/4/key/values  params: key, query, page, rp, sortname (count_all...)
 *                      result fields: value, count, fraction, description
 *
 * Per the Taginfo API usage policy (OSM wiki, Taginfo/API): the API is
 * for OpenStreetMap community use, requires a sensible User-Agent, and
 * should not be hammered. This client only fires on explicit user
 * actions in the tag browser, one small page at a time.
 */
object TaginfoClient {

    data class KeyInfo(val key: String, val count: Long)
    data class ValueInfo(val value: String, val count: Long, val description: String)
    data class ValuePage(val values: List<ValueInfo>, val total: Int, val page: Int)
    data class TagHit(val key: String, val value: String, val count: Long)

    /**
     * Live search of the whole tag database for tags whose VALUE contains the
     * words typed (taginfo endpoint /api/4/search/by_value), most-used first.
     * This is the long tail behind the offline feature list: any tag mappers
     * have actually used, documented or not, e.g. "lighthouse" finds
     * man_made=lighthouse, building=lighthouse and historic=lighthouse.
     *
     * Only real feature tags are kept: free-text values (names, streets,
     * notes), lifecycle-prefixed keys ("disused:amenity") and one-off typos
     * are filtered out. Returns an empty list when offline.
     */
    suspend fun searchTags(query: String): List<TagHit> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val phrase = words.joinToString("_")
        val attempts = LinkedHashSet<String>()
        attempts.add(phrase)
        // Naive singulars, so "benches" and "toilets" still hit.
        if (phrase.length > 4 && phrase.endsWith("es")) attempts.add(phrase.dropLast(2))
        if (phrase.length > 3 && phrase.endsWith("s")) attempts.add(phrase.dropLast(1))

        val out = LinkedHashMap<String, TagHit>()
        for (attempt in attempts) {
            val json = try {
                getJson(
                    "$BASE_URL/search/by_value?query=${URLEncoder.encode(attempt, "UTF-8")}" +
                            "&page=1&rp=60&sortname=count_all&sortorder=desc"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val data = json.optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val key = item.optString("key")
                val value = item.optString("value")
                val count = item.optLong("count_all", 0L)
                if (count < MIN_TAG_USES || !TAG_WORD.matches(key) || !TAG_WORD.matches(value)) continue
                if (value == "yes" || value == "no") continue
                out.putIfAbsent("$key=$value", TagHit(key, value, count))
            }
            if (out.isNotEmpty()) break
        }
        return out.values.sortedByDescending { it.count }
    }

    /** Plain lowercase tag words only: no spaces, colons, semicolons or capitals. */
    private val TAG_WORD = Regex("^[a-z][a-z0-9_]*[a-z]$")
    private const val MIN_TAG_USES = 50L

    private const val BASE_URL = "https://taginfo.openstreetmap.org/api/4"
    private const val USER_AGENT = "TrashCompass/3.6 (https://github.com/Unpiloted0852/TrashCompass)"
    const val PAGE_SIZE = 100

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Top 50 keys matching [query] (substring), most-used first. */
    suspend fun searchKeys(query: String): List<KeyInfo> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/keys/all?query=$q&page=1&rp=50" +
                "&sortname=count_all&sortorder=desc"
        val json = getJson(url)
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<KeyInfo>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val key = item.optString("key")
            if (key.isNotEmpty()) out.add(KeyInfo(key, item.optLong("count_all", 0L)))
        }
        return out
    }

    /**
     * One page of values for [key], most-used first, optionally
     * filtered by [filterQuery] (substring). [ValuePage.total] is the
     * total number of matching values, so callers can page through
     * every single one.
     */
    suspend fun valuesForKey(key: String, filterQuery: String?, page: Int): ValuePage {
        val k = URLEncoder.encode(key, "UTF-8")
        var url = "$BASE_URL/key/values?key=$k&page=$page&rp=$PAGE_SIZE" +
                "&sortname=count_all&sortorder=desc"
        if (!filterQuery.isNullOrBlank()) {
            url += "&query=" + URLEncoder.encode(filterQuery, "UTF-8")
        }
        val json = getJson(url)
        val total = json.optInt("total", 0)
        val data = json.optJSONArray("data")
        val out = ArrayList<ValueInfo>()
        if (data != null) {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val value = item.optString("value")
                if (value.isNotEmpty()) {
                    out.add(
                        ValueInfo(
                            value,
                            item.optLong("count", 0L),
                            item.optString("description")
                        )
                    )
                }
            }
        }
        return ValuePage(out, total, page)
    }

    private suspend fun getJson(url: String): JSONObject =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT).build()
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code} from taginfo")
                            val body = it.body?.string()
                                ?: throw IOException("Empty body from taginfo")
                            val parsed = JSONObject(body)
                            if (cont.isActive) cont.resume(parsed)
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }
}
