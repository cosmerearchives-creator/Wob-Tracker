package com.cosmerearchives.wobtracker

import android.os.Handler
import android.os.Looper
import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private val BrowserPlum = Color(0xFF6B5C87)
private val BrowserInk = Color(0xFF37343C)

data class BrowserPage(val total: Int, val page: Int, val entries: List<SavedWob>)
private val browserMain = Handler(Looper.getMainLooper())

private fun fetchArchivePage(page: Int): BrowserPage {
    require(page in 1..10000)
    val conn = URL("https://wob.coppermind.net/api/entry/?page=$page").openConnection() as HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 25000
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (user-initiated archive browser)")
    try {
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val results = json.getJSONArray("results")
        val entries = (0 until results.length()).map { n ->
            val obj = results.getJSONObject(n)
            val lines = obj.optJSONArray("lines")
            val transcript = if (lines == null) "" else (0 until lines.length()).joinToString("\n\n") { idx ->
                val line = lines.getJSONObject(idx)
                "${line.optString("speaker")}: ${line.optString("text")}"
            }
            val tags = obj.optJSONArray("tags")
            SavedWob(
                id = obj.getInt("id"),
                event = obj.optString("event_name"),
                date = obj.optString("event_date"),
                transcript = transcript,
                sourceTags = if (tags == null) "" else (0 until tags.length()).joinToString(", ") { tags.getString(it) },
                myTags = "", myNotes = ""
            )
        }
        return BrowserPage(json.optInt("count"), page, entries)
    } finally { conn.disconnect() }
}

@Composable
fun ArchiveBrowser(library: WobLibrary, onLibraryChanged: () -> Unit) {
    var page by remember { mutableStateOf<BrowserPage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Browse Arcanum. Bulk import runs in short, user-started batches.") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var savedIds by remember { mutableStateOf(setOf<Int>()) }
    val context = LocalContext.current
    val checkpoint = remember { context.getSharedPreferences("archive_import", android.content.Context.MODE_PRIVATE) }
    var nextImportPage by remember { mutableIntStateOf(checkpoint.getInt("next_page", 1).coerceAtLeast(1)) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(checkpoint.getString("last_summary", "") ?: "") }
    var lastFailure by remember { mutableStateOf(checkpoint.getString("last_failure", "") ?: "") }
    var savedCount by remember { mutableIntStateOf(library.savedEntryCount()) }
    var totalReported by remember { mutableIntStateOf(checkpoint.getInt("reported_total", 0)) }
    val cancelImport = remember { AtomicBoolean(false) }
    fun importBatch() {
        if (importing || busy) return
        importing = true
        cancelImport.set(false)
        val startingPage = nextImportPage
        thread {
            var imported = 0
            var skipped = 0
            var processed = 0
            var failure: String? = null
            for (pageNumber in startingPage until startingPage + 3) {
                if (cancelImport.get()) break
                try {
                    val fetched = fetchArchivePage(pageNumber)
                    if (fetched.entries.isEmpty()) break
                    for (entry in fetched.entries) {
                        if (cancelImport.get()) break
                        if (library.hasEntry(entry.id)) skipped++
                        else {
                            library.saveSource(entry)
                            if (!library.hasEntry(entry.id)) error("Could not save WoB #${entry.id}")
                            imported++
                        }
                        processed++
                    }
                    // Never advance beyond a page that was only partially saved.
                    if (cancelImport.get()) break
                    checkpoint.edit().putInt("next_page", pageNumber + 1)
                        .putInt("reported_total", fetched.total).commit()
                    browserMain.post {
                        totalReported = fetched.total
                        savedCount = library.savedEntryCount()
                        nextImportPage = pageNumber + 1
                        importProgress = "Saved $imported new · Skipped $skipped duplicates · Checked $processed entries"
                        onLibraryChanged()
                    }
                    if (fetched.entries.size < 50 || fetched.page * 50 >= fetched.total) break
                    Thread.sleep(1200L)
                } catch (e: Exception) {
                    failure = "Stopped on page $pageNumber: ${e.javaClass.simpleName}"
                    checkpoint.edit().putString("last_failure", failure).apply()
                    break
                }
            }
            browserMain.post {
                importing = false
                savedCount = library.savedEntryCount()
                lastFailure = failure ?: ""
                checkpoint.edit().putString("last_failure", lastFailure).apply()
                importProgress = listOfNotNull(
                    "Imported $imported · Skipped $skipped · Processed $processed",
                    failure ?: if (cancelImport.get()) "Paused; resume at page $nextImportPage" else null
                ).joinToString(" · ")
                checkpoint.edit().putString("last_summary", importProgress).apply()
                onLibraryChanged()
            }
        }
    }

    fun load(pageNumber: Int) {
        if (busy) return
        busy = true
        notice = "Loading page $pageNumber…"
        thread {
            try {
                val result = fetchArchivePage(pageNumber)
                val alreadySaved = result.entries.filter { library.hasEntry(it.id) }.map { it.id }.toSet()
                browserMain.post {
                    page = result
                    totalReported = result.total
                    checkpoint.edit().putInt("reported_total", result.total).apply()
                    savedIds = alreadySaved
                    selected = null
                    notice = "Page ${result.page} · ${result.total} entries reported by Arcanum"
                    busy = false
                }
            } catch (e: Exception) {
                browserMain.post {
                    notice = "Unable to load page. ${e.javaClass.simpleName}."
                    busy = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Browse Arcanum", style = MaterialTheme.typography.titleLarge, color = BrowserInk)
        Text("Uses the general entry listing, not search results. This helps include entries that are not searchable on Arcanum.", color = BrowserInk)
        if (page == null) Button(enabled = !busy, onClick = { load(1) }) { Text("Load archive entries") }
        Text(notice, style = MaterialTheme.typography.bodySmall, color = BrowserPlum)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Import dashboard", style = MaterialTheme.typography.titleMedium, color = BrowserInk)
                Text("Saved locally: $savedCount WoBs", color = BrowserInk)
                Text("Next import page: $nextImportPage", color = BrowserPlum)
                if (totalReported > 0) {
                    Text("Arcanum reports $totalReported entries · ${(savedCount * 100f / totalReported).coerceAtMost(100f).toInt()}% saved locally",
                        style = MaterialTheme.typography.bodySmall, color = BrowserInk)
                    LinearProgressIndicator(progress = { (savedCount.toFloat() / totalReported).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                }
                if (lastFailure.isNotBlank()) {
                    Text("Last import error: $lastFailure", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { savedCount = library.savedEntryCount() }) { Text("Refresh counts") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !importing && !busy, onClick = { importBatch() }) {
                Text("Import next 3 pages")
            }
            if (importing) OutlinedButton(onClick = { cancelImport.set(true) }) { Text("Pause") }
        }
        if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (importProgress.isNotBlank()) Text(importProgress, style = MaterialTheme.typography.bodySmall, color = BrowserInk)
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        page?.let { current ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && current.page > 1, onClick = { load(current.page - 1) }) { Text("Previous") }
                OutlinedButton(enabled = !busy && current.entries.isNotEmpty() && current.page * 50 < current.total, onClick = { load(current.page + 1) }) { Text("Next page") }
            }
            current.entries.forEach { entry ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("WoB #${entry.id} · ${entry.event}", color = BrowserPlum, style = MaterialTheme.typography.titleSmall)
                        if (entry.date.isNotEmpty()) Text(entry.date, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = if (selected == entry.id) null else entry.id }) {
                                Text(if (selected == entry.id) "Close" else "Read")
                            }
                            Button(enabled = !savedIds.contains(entry.id), onClick = {
                                try {
                                    if (!library.hasEntry(entry.id)) library.saveSource(entry)
                                    if (library.hasEntry(entry.id)) {
                                        savedIds = savedIds + entry.id
                                        notice = "✓ WoB #${entry.id} saved to offline library."
                                        savedCount = library.savedEntryCount()
                                        onLibraryChanged()
                                    } else notice = "Could not save entry."
                                } catch (e: Exception) { notice = "Save failed. Please retry." }
                            }) { Text(if (savedIds.contains(entry.id)) "Saved" else "Save offline") }
                        }
                        if (selected == entry.id) {
                            Text(Html.fromHtml(entry.transcript, Html.FROM_HTML_MODE_COMPACT).toString(), color = BrowserInk)
                            if (entry.sourceTags.isNotEmpty()) Text("Source tags: ${entry.sourceTags}", color = BrowserPlum)
                        }
                    }
                }
            }
        }
    }
}
