package com.cosmerearchives.wobtracker

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FootnotePlum = Color(0xFF6B5C87)

/**
 * Arcanum sends 'note' as HTML. Keep it verbatim in the database and only
 * transform it for display, so the original href targets remain available.
 */
@Composable
fun SourceFootnote(library: WobLibrary, entry: SavedWob, onChanged: () -> Unit) {
    var note by remember(entry.id, entry.sourceNote) { mutableStateOf(entry.sourceNote) }
    var checked by remember(entry.id) { mutableStateOf(false) }
    var loading by remember(entry.id) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // Existing WoBs were imported before source footnotes were stored. Fetch only
    // when a particular WoB is opened, never in a bulk loop or on the UI thread.
    LaunchedEffect(entry.id, entry.sourceNote) {
        if (note.isNotBlank() || checked) return@LaunchedEffect
        checked = true
        loading = true
        val retrieved = withContext(Dispatchers.IO) {
            try {
                val connection = URL("https://wob.coppermind.net/api/entry/${entry.id}/").openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (automatic source footnote lookup)")
                try {
                    if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val footnote = json.optString("note").takeUnless { it == "null" }.orEmpty()
                    // Only the source note column changes; user tags, reviews, and highlights stay intact.
                    if (footnote.isNotBlank()) library.saveSourceNote(entry.id, footnote)
                    footnote
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }
        loading = false
        if (!retrieved.isNullOrBlank()) {
            note = retrieved
            onChanged()
        }
    }

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
    } else if (loading) {
        Text("Checking for source footnote…", style = MaterialTheme.typography.bodySmall, color = FootnotePlum)
    }
}
