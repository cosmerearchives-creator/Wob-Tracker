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
fun ReviewMode(library: WobLibrary, libraryRevision: Int, onChanged: () -> Unit, onNext: () -> Unit) {
    var localRefresh by remember { mutableIntStateOf(0) }
    val pending = remember(libraryRevision, localRefresh) { library.entries(filterBy = "Needs review").sortedBy { it.id } }
    var passedIds by remember { mutableStateOf(setOf<Int>()) }
    val current = pending.firstOrNull { it.id !in passedIds }
    var tags by remember(current?.id, localRefresh) { mutableStateOf(current?.myTags.orEmpty()) }
    var notes by remember(current?.id, localRefresh) { mutableStateOf(current?.myNotes.orEmpty()) }
    var cosmere by remember(current?.id, localRefresh) { mutableStateOf(current?.cosmere ?: false) }
    var extended by remember(current?.id, localRefresh) { mutableStateOf(current?.extendedWorks ?: false) }
    var export by remember(current?.id, localRefresh) { mutableStateOf(current?.needsExport ?: false) }
    var message by remember { mutableStateOf("") }
    var sourceLink by remember(current?.id) { mutableStateOf(current?.originalUrl.orEmpty()) }
    var dirty by remember(current?.id, localRefresh) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review Mode", style = MaterialTheme.typography.titleLarge, color = ReviewInk, fontWeight = FontWeight.Bold)
        Text("${pending.size} WoBs need review", color = ReviewPlum)
        if (current == null) {
            if (pending.isEmpty()) {
                Text("You're caught up! Import more entries to continue reviewing.", color = ReviewInk)
            } else {
                Text("End of this review pass. ${pending.size} WoBs still need review.", color = ReviewInk)
                OutlinedButton(onClick = { passedIds = emptySet(); message = "" }) { Text("Start another pass") }
            }
        } else {
            Text("WoB #${current.id} · ${current.event}", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            if (current.date.isNotBlank()) Text(current.date, color = ReviewInk)
            if (current.eventId > 0) Text("Event ID: ${current.eventId}", color = ReviewPlum)
            if (current.date.isNotBlank()) Text("Event date: ${current.date}", color = ReviewPlum)
            if (current.entryDate.isNotBlank()) Text("Entry date: ${current.entryDate}", color = ReviewPlum)
            if (current.modifiedDate.isNotBlank()) Text("Source modified: ${current.modifiedDate}", color = ReviewPlum)
            WobHighlightEditor(library, current)
            if (current.sourceTags.isNotBlank()) Text("Source tags: ${current.sourceTags}", color = ReviewPlum)
            OutlinedTextField(value = sourceLink, onValueChange = { sourceLink = it }, label = { Text("Original external source URL (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = {
                message = if (library.saveOriginalUrl(current.id, sourceLink)) "✓ Original source link saved." else "Enter a valid http(s) source URL."
            }) { Text("Save source link") }
            if (current.originalUrl.isBlank() && sourceLink.isBlank()) Text("Arcanum's API does not supply the external source URL.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            PersonalTagField(value = tags, onValueChange = { tags = it; dirty = true; message = "" }, library = library, revision = libraryRevision + localRefresh, modifier = Modifier.fillMaxWidth())
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
                        passedIds = passedIds + current.id
                        message = "✓ Reviewed and saved."
                        localRefresh++
                        onChanged()
                        onNext()
                    } else message = "Could not save review. This entry remains pending."
                } catch (e: Exception) { message = "Save failed. This entry remains pending." }
            }, colors = ButtonDefaults.buttonColors(containerColor = ReviewPlum)) {
                Text("Mark reviewed & next")
            }
            OutlinedButton(onClick = {
                try {
                    if (library.saveReviewDraft(current.id, tags, notes, export, cosmere, extended)) {
                        passedIds = passedIds + current.id
                        message = "✓ Draft saved. Entry remains in the review queue."
                        localRefresh++
                        onChanged()
                        onNext()
                    } else message = "Could not save draft."
                } catch (e: Exception) { message = "Could not save draft." }
            }) { Text("Save without reviewing & next") }
            OutlinedButton(onClick = {
                passedIds = passedIds + current.id
                localRefresh++
                message = ""
                onNext()
            }) { Text("Skip without saving") }
            if (message.isNotBlank()) Text(message, color = if (message.startsWith("✓")) Color(0xFF386D52) else Color(0xFFBC3E42))
            Text("Mark reviewed removes an entry from the queue. Save & next or Skip moves forward for this pass but leaves it pending. Skipping discards unsaved notes, tags, classification and export changes.", style = MaterialTheme.typography.bodySmall, color = ReviewInk)
        }
    }
}
