package com.cosmerearchives.wobtracker

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Highlight ranges are stored against this stable plain-text transcript representation. */
fun readableTranscript(raw: String): String = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()

@Composable
fun WobHighlightEditor(library: WobLibrary, entry: SavedWob) {
    var revision by remember(entry.id) { mutableIntStateOf(0) }
    val marks = remember(entry.id, revision) { library.highlights(entry.id) }
    val transcript = remember(entry.id, entry.transcript) { readableTranscript(entry.transcript) }
    var selection by remember(entry.id) { mutableStateOf(TextRange.Zero) }
    var message by remember(entry.id) { mutableStateOf("") }
    val styled = remember(transcript, marks) {
        buildAnnotatedString {
            append(transcript)
            marks.forEach { mark ->
                if (mark.start >= 0 && mark.end <= transcript.length && mark.end > mark.start &&
                    transcript.substring(mark.start, mark.end) == mark.quote
                ) addStyle(SpanStyle(background = Color(0xFFFFE39C)), mark.start, mark.end)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Transcript — select text to highlight", style = MaterialTheme.typography.titleSmall)
        BasicTextField(
            value = TextFieldValue(styled, selection),
            onValueChange = { selection = it.selection },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Color(0xFF37343C))
        )
        OutlinedButton(onClick = {
            val a = selection.min.coerceIn(0, transcript.length)
            val b = selection.max.coerceIn(0, transcript.length)
            if (a == b) {
                message = "Select a passage in the transcript first."
            } else {
                try {
                    if (library.addHighlight(entry.id, a, b, transcript.substring(a, b))) {
                        revision++
                        message = "✓ Highlight saved to this WoB."
                    } else message = "This passage may already be highlighted."
                } catch (e: Exception) { message = "Couldn't save highlight." }
            }
        }) { Text("Highlight selection") }
        if (message.isNotEmpty()) Text(message, color = Color(0xFF6B5C87))
        if (marks.isNotEmpty()) {
            Text("Saved highlights", style = MaterialTheme.typography.titleSmall)
            marks.forEach { mark ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“${mark.quote.take(140)}”", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (library.deleteHighlight(mark.id)) { revision++; message = "Highlight removed." }
                    }) { Text("Remove") }
                }
            }
        }
    }
}
