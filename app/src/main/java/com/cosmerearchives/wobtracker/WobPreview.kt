package com.cosmerearchives.wobtracker

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class WobLine(val speaker: String, val text: String)
data class WobEntry(
    val id: Int,
    val event: String,
    val eventDate: String,
    val tags: List<String>,
    val lines: List<WobLine>,
    val note: String,
    val paraphrased: Boolean
)
data class WobPreviewState(val loading: Boolean = false, val entry: WobEntry? = null, val error: String? = null)

/** Read-only preview; no bulk downloads and no writes to Arcanum. */
class WobPreviewLoader {
    private val main = Handler(Looper.getMainLooper())

    fun load(entryId: Int = 1356, onResult: (WobPreviewState) -> Unit) {
        onResult(WobPreviewState(loading = true))
        thread {
            try {
                val connection = URL("https://wob.coppermind.net/api/entry/$entryId/").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (Android single-entry preview)")
                try {
                    if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val rawLines = json.getJSONArray("lines")
                    val lines = (0 until rawLines.length()).map { index ->
                        val line = rawLines.getJSONObject(index)
                        WobLine(line.optString("speaker", "Unknown"), line.optString("text", ""))
                    }
                    val rawTags = json.optJSONArray("tags")
                    val tags = if (rawTags == null) emptyList() else (0 until rawTags.length()).map { rawTags.getString(it) }
                    val entry = WobEntry(
                        id = json.getInt("id"),
                        event = json.optString("event_name", "Unknown event"),
                        eventDate = json.optString("event_date", ""),
                        tags = tags,
                        lines = lines,
                        note = json.optString("note", ""),
                        paraphrased = json.optBoolean("paraphrased")
                    )
                    main.post { onResult(WobPreviewState(entry = entry)) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                main.post { onResult(WobPreviewState(error = "Unable to load this WoB: ${e.javaClass.simpleName}")) }
            }
        }
    }
}
