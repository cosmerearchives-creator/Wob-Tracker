package com.cosmerearchives.wobtracker

import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private val FootnotePlum = Color(0xFF6B5C87)

/**
 * Arcanum sends 'note' as HTML. Keep it verbatim in the database and only
 * transform it for display, so the original href targets remain available.
 */
@Composable
fun SourceFootnote(library: WobLibrary, entry: SavedWob, onChanged: () -> Unit) {
    var note by remember(entry.id, entry.sourceNote) { mutableStateOf(entry.sourceNote) }
    var loading by remember(entry.id) { mutableStateOf(false) }
    var status by remember(entry.id) { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val main = remember { Handler(Looper.getMainLooper()) }

    if (note.isNotBlank()) {
        val parsed = remember(note) { Html.fromHtml(note, Html.FROM_HTML_MODE_COMPACT) }
        val annotated = remember(note) {
            buildAnnotatedString {
                append(parsed.toString().trim())
                if (parsed is Spanned) {
                    parsed.getSpans(0, parsed.length, URLSpan::class.java).forEach { span ->
                        val start = parsed.getSpanStart(span)
                        val end = parsed.getSpanEnd(span).coerceAtMost(length)
                        val rawUrl = span.url.orEmpty()
                        val url = when {
                            rawUrl.startsWith("https://", true) || rawUrl.startsWith("http://", true) -> rawUrl
                            rawUrl.startsWith("/") -> "https://wob.coppermind.net$rawUrl"
                            else -> ""
                        }
                        if (url.isNotBlank() && start in 0 until end) {
                            addStyle(SpanStyle(color = FootnotePlum, textDecoration = TextDecoration.Underline), start, end)
                            addStringAnnotation("URL", url, start, end)
                        }
                    }
                }
            }
        }
        Text("Footnote", fontWeight = FontWeight.SemiBold, color = FootnotePlum)
        ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            onClick = { offset ->
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    uriHandler.openUri(it.item)
                }
            }
        )
    } else {
        TextButton(enabled = !loading, onClick = {
            loading = true
            status = ""
            val id = entry.id
            thread {
                var retrieved: String? = null
                val outcome = try {
                    val connection = URL("https://wob.coppermind.net/api/entry/$id/").openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (source footnote refresh)")
                    try {
                        if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        retrieved = json.optString("note").takeUnless { it == "null" }.orEmpty()
                        if (!library.saveSourceNote(id, retrieved.orEmpty())) error("Could not store footnote")
                        if (retrieved.isNullOrBlank()) "No footnote for this WoB on Arcanum." else "Footnote retrieved."
                    } finally { connection.disconnect() }
                } catch (e: Exception) { "Couldn't fetch footnote. Check your connection and retry." }
                main.post {
                    note = retrieved ?: note
                    status = outcome
                    loading = false
                    if (retrieved != null) onChanged()
                }
            }
        }) { Text(if (loading) "Checking Arcanum…" else "Check for Arcanum footnote") }
    }
    if (status.isNotBlank() && note.isBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = FootnotePlum)
}
