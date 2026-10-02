package com.example.trashcompass

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update from the project's GitHub Releases (free, keyless API).
 *
 * [checkForUpdate] reads the latest published release and compares its tag
 * ("v3.7") with the installed versionName. [downloadAndInstall] streams that
 * release's .apk asset straight into a PackageInstaller session, so nothing
 * is written to shared storage.
 *
 * Android still owns the final step: the first update shows the system
 * "update this app?" dialog (and, once, the "allow installs from this app"
 * setting). On Android 12+ later updates may install without that dialog.
 * An update only installs over a build signed with the same key as the
 * release APK, so a debug build cannot update itself this way.
 */
class AppUpdater(private val activity: Activity) {

    data class Release(val versionName: String, val apkUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The latest release if it is newer than the installed build, else null (also on any error). */
    suspend fun checkForUpdate(): Release? = withContext(Dispatchers.IO) {
        try {
            fetchLatest()?.takeIf { isNewer(it.versionName, installedVersion()) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads and installs [release]. [onProgress] gets the download
     * percentage on the main thread. Returns null on success (in practice the
     * process is killed by the update first), or a short reason if the update
     * did not happen ("cancelled" if the user backed out).
     */
    suspend fun downloadAndInstall(release: Release, onProgress: (Int) -> Unit): String? {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val result = CompletableDeferred<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                            ?.let { activity.startActivity(it) }
                    PackageInstaller.STATUS_SUCCESS -> result.complete(null)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> result.complete("cancelled")
                    else -> result.complete(
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                    )
                }
            }
        }
        val action = activity.packageName + ".UPDATE_STATUS"
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        var sessionId = -1
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val req = Request.Builder().url(release.apkUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("download failed (HTTP ${resp.code})")
                        val body = resp.body ?: throw IOException("download failed (empty response)")
                        val total = body.contentLength()
                        session.openWrite("update.apk", 0, total).use { out ->
                            val buf = ByteArray(64 * 1024)
                            val input = body.byteStream()
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    // Mutable so the installer can attach the status extras; package-scoped
                    // so it only ever reaches the receiver registered above.
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
                    val callback = PendingIntent.getBroadcast(
                        activity, sessionId, Intent(action).setPackage(activity.packageName), flags
                    )
                    session.commit(callback.intentSender)
                    committed = true
                }
            }
            return result.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message ?: "download failed"
        } finally {
            if (!committed && sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            activity.unregisterReceiver(receiver)
        }
    }

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(LATEST_RELEASE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            val version = o.optString("tag_name").trim().removePrefix("v")
            if (version.isEmpty()) return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (!a.optString("name").endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url")
                if (url.isNotEmpty()) return Release(version, url)
            }
            return null
        }
    }

    private fun installedVersion(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Unpiloted0852/TrashCompass/releases/latest"
        private const val USER_AGENT = "TrashCompass (https://github.com/Unpiloted0852/TrashCompass)"

        /** Numeric, dot-separated comparison: "3.7" > "3.6", "3.10" > "3.9", "4" > "3.99". */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = candidate.split('.').map { it.trim().toIntOrNull() ?: 0 }
            val b = installed.split('.').map { it.trim().toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
