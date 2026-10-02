package com.example.trashcompass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves and downloads a photo for an amenity from one of four
 * sources, in priority order:
 *
 *  1. "image" tag       -- a direct http(s) URL, no API needed
 *  2. "mapillary" tag   -- image ID resolved via the Mapillary Graph API
 *  3. "wikimedia_commons" tag -- "File:..." resolved via the Commons API
 *  4. "wikidata" tag    -- QID; fetch the P18 (image) claim, then
 *                          resolve the filename via the Commons API
 *
 * Bug fixed vs. v2.x: the Wikidata path used optJSONObject("value"),
 * but for commonsMedia claims "datavalue.value" is a plain STRING in
 * the entity JSON, so the lookup always returned null and Wikidata
 * images never appeared. We now read it with optString.
 *
 * Also new: bitmaps are downsampled to at most [MAX_DIMENSION_PX] on
 * their longest side while decoding, instead of decoding full-size
 * photos into memory (a common source of jank and OutOfMemoryError on
 * older phones).
 */
object ImageResolver {

    // --- MAPILLARY TOKEN ---
    // Get one free at https://www.mapillary.com/dashboard/developers
    // If blank, mapillary tags are skipped but other sources still work.
    private const val MAPILLARY_ACCESS_TOKEN =
        "MLY|26782956327960665|7ea4bb0428dc48fe0089e13b8f2b0617"

    private const val USER_AGENT = "TrashCompass/3.2 (https://github.com/Unpiloted0852/TrashCompass)"
    private const val MAX_DIMENSION_PX = 1280

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Blocking. Call from Dispatchers.IO. Returns null if the amenity
     * has no usable image tags or anything fails along the way.
     */
    fun loadImageForTags(tags: JSONObject): Bitmap? {
        return try {
            val url = resolveUrl(tags) ?: return null
            downloadBitmap(url)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveUrl(tags: JSONObject): String? {
        val direct = tags.optString("image").trim()
        if (direct.startsWith("http")) return direct

        val mapillary = tags.optString("mapillary").trim()
        if (mapillary.isNotEmpty() && MAPILLARY_ACCESS_TOKEN.isNotEmpty()) {
            resolveMapillary(mapillary)?.let { return it }
        }

        val commons = tags.optString("wikimedia_commons").trim()
        if (commons.isNotEmpty()) {
            resolveWikimedia(commons)?.let { return it }
        }

        val wikidata = tags.optString("wikidata").trim()
        if (wikidata.isNotEmpty()) {
            resolveWikidata(wikidata)?.let { return it }
        }
        return null
    }

    private fun resolveMapillary(imageId: String): String? {
        val apiUrl = "https://graph.mapillary.com/$imageId" +
                "?fields=thumb_1024_url&access_token=$MAPILLARY_ACCESS_TOKEN"
        return http.newCall(Request.Builder().url(apiUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.optString("thumb_1024_url").takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Calls the Wikimedia Commons imageinfo API to get a scaled
     * thumbnail URL. [value] should be "File:Foo.jpg"; category values
     * are skipped (no single canonical image). Wikimedia policy
     * requires a descriptive User-Agent header.
     */
    private fun resolveWikimedia(value: String): String? {
        val normalized = when {
            value.startsWith("File:") -> value
            value.startsWith("file:") -> "File:" + value.removePrefix("file:")
            !value.contains(":") -> "File:$value"
            else -> return null // Category: or unknown namespace
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val apiUrl = "https://commons.wikimedia.org/w/api.php" +
                "?action=query&titles=$encoded&prop=imageinfo" +
                "&iiprop=url&iiurlwidth=1024&format=json"
        val request = Request.Builder().url(apiUrl)
            .header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string() ?: "{}")
            val pages = json.optJSONObject("query")?.optJSONObject("pages")
                ?: return@use null
            if (!pages.keys().hasNext()) return@use null
            val pageKey = pages.keys().next()
            if (pageKey == "-1") return@use null // not found
            val page = pages.optJSONObject(pageKey) ?: return@use null
            page.optJSONArray("imageinfo")
                ?.optJSONObject(0)
                ?.optString("thumburl")
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun resolveWikidata(qid: String): String? {
        val url = "https://www.wikidata.org/wiki/Special:EntityData/$qid.json"
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string() ?: "{}")
            val fileName = json
                .optJSONObject("entities")
                ?.optJSONObject(qid)
                ?.optJSONObject("claims")
                ?.optJSONArray("P18")
                ?.optJSONObject(0)
                ?.optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")
                ?.optString("value") // plain string for commonsMedia -- the v2.x bug
            if (!fileName.isNullOrEmpty()) resolveWikimedia("File:$fileName") else null
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null

            // Pass 1: read dimensions only
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null

            // Pass 2: decode with a power-of-two sample size so the
            // longest side ends up <= MAX_DIMENSION_PX
            var sample = 1
            var longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / 2 >= MAX_DIMENSION_PX) {
                sample *= 2
                longest /= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
