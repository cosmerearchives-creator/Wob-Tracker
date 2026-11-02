package com.cosmerearchives.wobtracker

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val ReviewPlum = Color(0xFF6B5C87)
private val ReviewInk = Color(0xFF37343C)

@Composable
fun ReviewMode(library: WobLibrary, libraryRevision: Int, onChanged: () -> Unit) {
    var localRefresh by remember { mutableIntStateOf(0) }
    val pending = remember(libraryRevision, localRefresh) { library.entries(filterBy = "Needs review").sortedBy { it.id } }
    var activeId by remember { mutableStateOf<Int?>(null) }
    val current = pending.firstOrNull { it.id == activeId } ?: pending.firstOrNull()
    var tags by remember(current?.id, localRefresh) { mutableStateOf(current?.myTags.orEmpty()) }
    var notes by remember(current?.id, localRefresh) { mutableStateOf(current?.myNotes.orEmpty()) }
    var cosmere by remember(current?.id, localRefresh) { mutableStateOf(current?.cosmere ?: false) }
    var extended by remember(current?.id, localRefresh) { mutableStateOf(current?.extendedWorks ?: false) }
    var export by remember(current?.id, localRefresh) { mutableStateOf(current?.needsExport ?: false) }
    var message by remember { mutableStateOf("") }
    var dirty by remember(current?.id, localRefresh) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review Mode", style = MaterialTheme.typography.titleLarge, color = ReviewInk, fontWeight = FontWeight.Bold)
        Text("${pending.size} WoBs need review", color = ReviewPlum)
        if (current == null) {
            Text("You're caught up! Import more entries to continue reviewing.", color = ReviewInk)
        } else {
            Text("WoB #${current.id} · ${current.event}", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            if (current.date.isNotBlank()) Text(current.date, color = ReviewInk)
            Text(Html.fromHtml(current.transcript, Html.FROM_HTML_MODE_COMPACT).toString(), color = ReviewInk)
            if (current.sourceTags.isNotBlank()) Text("Source tags: ${current.sourceTags}", color = ReviewPlum)
            HorizontalDivider()
            OutlinedTextField(value = tags, onValueChange = { tags = it; dirty = true; message = "" }, label = { Text("My tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it; dirty = true; message = "" }, label = { Text("My notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text("Work classification", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = cosmere, onCheckedChange = { cosmere = it; dirty = true; message = "" })
                Text("Cosmere", color = ReviewInk)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = extended, onCheckedChange = { extended = it; dirty = true; message = "" })
                Text("Extended Works", color = ReviewInk)
            }
            Text("Workflow", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = export, onCheckedChange = { export = it; dirty = true; message = "" })
                Text("Needs export", color = ReviewInk)
            }
            Button(onClick = {
                try {
                    if (library.completeReview(current.id, tags, notes, export, cosmere, extended)) {
                        activeId = null
                        message = "✓ Reviewed and saved. Moving to next WoB."
                        localRefresh++
                        onChanged()
                    } else message = "Could not save review. This entry remains pending."
                } catch (e: Exception) { message = "Save failed. This entry remains pending." }
            }, colors = ButtonDefaults.buttonColors(containerColor = ReviewPlum)) {
                Text("Mark reviewed & next")
            }
            OutlinedButton(onClick = {
                try {
                    if (library.saveReviewDraft(current.id, tags, notes, export, cosmere, extended)) {
                        message = "✓ Draft saved. Entry still needs review."
                        localRefresh++
                        onChanged()
                    } else message = "Could not save draft."
                } catch (e: Exception) { message = "Could not save draft." }
            }) { Text("Save without reviewing") }
            if (message.isNotBlank()) Text(message, color = if (message.startsWith("✓")) Color(0xFF386D52) else Color(0xFFBC3E42))
            Text("Only saved WoBs appear here. Finish one to advance; reviewed entries remain in your library.", style = MaterialTheme.typography.bodySmall, color = ReviewInk)
        }
    }
}
