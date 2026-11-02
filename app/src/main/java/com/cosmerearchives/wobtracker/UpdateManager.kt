package com.cosmerearchives.wobtracker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class UpdateState(val message: String = "Checking for updates…", val downloadUrl: String? = null)

class UpdateManager(private val activity: Activity) {
    val state = MutableStateFlow(UpdateState())
    private val main = Handler(Looper.getMainLooper())
    private val releasesApi = "https://api.github.com/repos/cosmerearchives-creator/Wob-Tracker/releases?per_page=20"

    fun check() {
        state.value = UpdateState("Checking for updates…")
        thread {
            try {
                val releases = JSONArray(readText(releasesApi))
                var selected: Triple<List<Int>, String, String>? = null
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    if (release.optBoolean("draft")) continue
                    val tag = release.optString("tag_name").removePrefix("v")
                    val version = parseVersion(tag) ?: continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (a in 0 until assets.length()) {
                        val asset = assets.getJSONObject(a)
                        val name = asset.optString("name")
                        if (!name.endsWith(".apk", true)) continue
                        val url = asset.optString("browser_download_url")
                        if (url.isBlank() || !url.startsWith("https://github.com/cosmerearchives-creator/Wob-Tracker/releases/download/")) continue
                        if (selected == null || compare(version, selected!!.first) > 0) selected = Triple(version, tag, url)
                    }
                }
                val available = selected?.takeIf { compare(it.first, parseVersion(BuildConfig.VERSION_NAME) ?: listOf(0, 0, 0)) > 0 }
                main.post {
                    state.value = if (available == null) UpdateState("You're up to date.")
                    else UpdateState("Update available: v${available.second}", available.third)
                }
            } catch (e: Exception) {
                main.post { state.value = UpdateState("Couldn't check for updates. Please try again.") }
            }
        }
    }

    fun installLatest() {
        val url = state.value.downloadUrl ?: return
        state.value = state.value.copy(message = "Downloading update…", downloadUrl = null)
        thread {
            try {
                val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(directory, "wob-tracker-update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    conn.inputStream.use { source -> apk.outputStream().use { source.copyTo(it) } }
                } finally { conn.disconnect() }
                main.post {
                    if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                        state.value = UpdateState("Allow installs from this app, then tap Install update again.", url)
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                        state.value = UpdateState("Follow Android's installation prompt.")
                    }
                }
            } catch (e: Exception) {
                main.post { state.value = UpdateState("Download failed. Check your connection and try again.", url) }
            }
        }
    }

    private fun readText(address: String): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "WoB-Tracker-Android")
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun parseVersion(value: String): List<Int>? {
        val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(value) ?: return null
        return m.groupValues.drop(1).map { it.toInt() }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
}
