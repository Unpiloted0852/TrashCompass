package com.example.trashcompass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves and downloads a photo for an amenity. Sources are tried in this
 * order until one yields a picture; everything except the last needs no
 * key or account:
 *
 *  1. "image" tag       -- a direct http(s) URL, or a Wikimedia Commons
 *                          file given as a page link or "File:..." name
 *  2. "panoramax" tag   -- picture ID on the open Panoramax network
 *  3. "wikimedia_commons" tag -- "File:..." resolved via the Commons API,
 *                          or the first file of a "Category:..."
 *  4. "wikidata" tag    -- QID; fetch the P18 (image) claim, then resolve
 *                          the filename via the Commons API
 *  5. "wikipedia" tag   -- the article's lead image ("de:Title")
 *  6. "mapillary" tag   -- image ID resolved via the Mapillary Graph API.
 *                          Last, because it is the only source that needs
 *                          an access token; skipped if the build has none.
 *
 * Tags may hold several values separated by ";" -- the first is used.
 *
 * Bitmaps are downsampled to at most [MAX_DIMENSION_PX] on their longest
 * side while decoding, instead of decoding full-size photos into memory
 * (a common source of jank and OutOfMemoryError on older phones).
 */
object ImageResolver {

    // Supplied at build time from the untracked secrets.properties
    // (mapillaryToken=...), never from source. Get one free at
    // https://www.mapillary.com/dashboard/developers
    private val MAPILLARY_ACCESS_TOKEN: String = BuildConfig.MAPILLARY_TOKEN

    private const val USER_AGENT = "TrashCompass/3.8 (https://github.com/Unpiloted0852/TrashCompass)"
    private const val MAX_DIMENSION_PX = 1280

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val PHOTO_TAGS = listOf("image", "panoramax", "wikimedia_commons", "wikidata", "wikipedia")

    /** True if [tags] has anything [loadImageForTags] could turn into a photo. */
    fun hasPhotoTags(tags: JSONObject): Boolean =
        PHOTO_TAGS.any { firstValue(tags, it).isNotEmpty() } ||
                (MAPILLARY_ACCESS_TOKEN.isNotEmpty() && firstValue(tags, "mapillary").isNotEmpty())

    /**
     * Blocking. Call from Dispatchers.IO. Returns null if the amenity
     * has no usable image tags or every source fails.
     */
    fun loadImageForTags(tags: JSONObject): Bitmap? {
        val sources = listOf<() -> String?>(
            { resolveImageTag(firstValue(tags, "image")) },
            { resolvePanoramax(firstValue(tags, "panoramax")) },
            { resolveWikimedia(firstValue(tags, "wikimedia_commons")) },
            { resolveWikidata(firstValue(tags, "wikidata")) },
            { resolveWikipedia(firstValue(tags, "wikipedia")) },
            { resolveMapillary(firstValue(tags, "mapillary")) }
        )
        for (source in sources) {
            val bitmap = try {
                source()?.let { downloadBitmap(it) }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun firstValue(tags: JSONObject, key: String): String =
        tags.optString(key).substringBefore(';').trim()

    /** A direct link is used as is; a link or name of a Commons file page is resolved to its image. */
    private fun resolveImageTag(value: String): String? {
        if (value.isEmpty()) return null
        val commonsPage = Regex("^https?://commons\\.(?:m\\.)?wikimedia\\.org/wiki/(File:.+)$", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)
        if (commonsPage != null) return resolveWikimedia(URLDecoder.decode(commonsPage, "UTF-8"))
        if (value.startsWith("File:", ignoreCase = true)) return resolveWikimedia(value)
        return if (value.startsWith("http")) value else null
    }

    /**
     * Panoramax picture IDs are UUIDs; api.panoramax.xyz is the network's
     * shared catalogue and redirects to whichever instance hosts the picture.
     */
    private fun resolvePanoramax(id: String): String? =
        if (PANORAMAX_ID.matches(id)) "https://api.panoramax.xyz/api/pictures/$id/sd.jpg" else null

    private val PANORAMAX_ID = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")

    /** "de:Berliner Dom" -> that article's lead image, if it has one. */
    private fun resolveWikipedia(value: String): String? {
        if (value.isEmpty()) return null
        val lang = value.substringBefore(':', "").lowercase()
        val title = value.substringAfter(':').trim()
        if (!Regex("^[a-z][a-z-]{1,11}$").matches(lang) || title.isEmpty()) return null
        val apiUrl = "https://$lang.wikipedia.org/w/api.php?action=query&prop=pageimages" +
                "&pithumbsize=1024&redirects=1&format=json&titles=" + URLEncoder.encode(title, "UTF-8")
        val request = Request.Builder().url(apiUrl).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val pages = JSONObject(response.body?.string() ?: "{}")
                .optJSONObject("query")?.optJSONObject("pages") ?: return@use null
            if (!pages.keys().hasNext()) return@use null
            pages.optJSONObject(pages.keys().next())
                ?.optJSONObject("thumbnail")
                ?.optString("source")
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun resolveMapillary(imageId: String): String? {
        if (imageId.isEmpty() || MAPILLARY_ACCESS_TOKEN.isEmpty()) return null
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
     * thumbnail URL. [value] is "File:Foo.jpg" or "Category:Foo"; for a
     * category (the more common form in OSM) the first file in it is
     * used. Wikimedia policy requires a descriptive User-Agent header.
     */
    private fun resolveWikimedia(value: String): String? {
        if (value.isEmpty()) return null
        val isCategory = value.startsWith("Category:", ignoreCase = true)
        val normalized = when {
            isCategory -> "Category:" + value.substringAfter(':')
            value.startsWith("File:", ignoreCase = true) -> "File:" + value.substringAfter(':')
            !value.contains(":") -> "File:$value"
            else -> return null // unknown namespace
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val selector =
            if (isCategory) "generator=categorymembers&gcmtitle=$encoded&gcmtype=file&gcmlimit=1"
            else "titles=$encoded"
        val apiUrl = "https://commons.wikimedia.org/w/api.php" +
                "?action=query&$selector&prop=imageinfo" +
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
        if (!Regex("^Q[0-9]+$").matches(qid)) return null
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
