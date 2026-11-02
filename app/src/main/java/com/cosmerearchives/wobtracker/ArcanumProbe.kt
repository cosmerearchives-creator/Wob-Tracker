package com.cosmerearchives.wobtracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.os.Handler
import android.os.Looper

data class ArchiveProbeResult(
    val status: String,
    val total: Int? = null,
    val examples: List<String> = emptyList()
)

/** Read-only, single-page API feasibility probe. Does not persist or bulk-download transcripts. */
class ArcanumProbe {
    private val main = Handler(Looper.getMainLooper())
    fun run(onResult: (ArchiveProbeResult) -> Unit) {
        onResult(ArchiveProbeResult("Connecting to Arcanum…"))
        thread {
            try {
                val connection = URL("https://wob.coppermind.net/api/entry/").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (Android feasibility probe)")
                try {
                    if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                    val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val entries = result.getJSONArray("results")
                    val samples = (0 until minOf(entries.length(), 3)).map { i ->
                        val entry = entries.getJSONObject(i)
                        val id = entry.getInt("id")
                        val title = entry.optString("event_name", "Unknown event")
                        "#$id — $title"
                    }
                    main.post { onResult(ArchiveProbeResult("Connected. One page retrieved successfully.", result.getInt("count"), samples)) }
                } finally { connection.disconnect() }
            } catch (e: Exception) {
                main.post { onResult(ArchiveProbeResult("API test failed: ${e.javaClass.simpleName}. Try again later.")) }
            }
        }
    }
}
