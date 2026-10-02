package com.example.trashcompass

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
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
    data class TagHit(val key: String, val value: String) // value may be empty (key-only hit)

    /**
     * Plain-language search: matches [query] against the words of the
     * OSM wiki documentation pages of keys and tags (taginfo endpoint
     * /api/4/search/by_keyword, verified against taginfo source:
     * queries the wiki.words table). This is what lets someone type
     * "trash can" and find amenity=waste_basket without knowing any
     * OSM notation. Returns deduplicated key/value hits.
     */
    suspend fun searchByKeyword(query: String): List<TagHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/by_keyword?query=$q&page=1&rp=60"
        val json = getJson(url)
        val data = json.optJSONArray("data") ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<TagHit>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val key = item.optString("key")
            // Android's org.json coerces a JSON null to the string
            // "null" in optString, so filter that out defensively.
            var value = item.optString("value")
            if (value == "null") value = ""
            if (key.isEmpty() || key == "null") continue
            if (seen.add("$key=$value")) out.add(TagHit(key, value))
        }
        return out
    }

    private const val BASE_URL = "https://taginfo.openstreetmap.org/api/4"
    private const val USER_AGENT = "TrashCompass/3.2 (https://github.com/Unpiloted0852/TrashCompass)"
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
