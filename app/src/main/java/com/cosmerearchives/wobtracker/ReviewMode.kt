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
private val ReviewDivider = Color(0xFF8872A8)

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
    var junk by remember(current?.id, localRefresh) { mutableStateOf(current?.junk ?: false) }
    var message by remember { mutableStateOf("") }
    var sourceLink by remember(current?.id) { mutableStateOf(current?.originalUrl.orEmpty()) }
    var dirty by remember(current?.id, localRefresh) { mutableStateOf(false) }
    var showSourceDetails by remember(current?.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Review Mode", style = MaterialTheme.typography.titleLarge, color = ReviewInk, fontWeight = FontWeight.Bold)
            Text("${pending.size} need review", color = ReviewPlum, style = MaterialTheme.typography.labelMedium)
        }
        if (current == null) {
            if (pending.isEmpty()) {
                Text("You're caught up! Import more entries to continue reviewing.", color = ReviewInk)
            } else {
                Text("End of this review pass. ${pending.size} WoBs still need review.", color = ReviewInk)
                OutlinedButton(onClick = { passedIds = emptySet(); message = "" }) { Text("Start another pass") }
            }
        } else {
            Text("WoB #${current.id} · ${current.event}", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (current.date.isNotBlank()) Text(current.date, color = ReviewInk)
                Box {
                    IconButton(onClick = { showSourceDetails = true }) {
                        Text("ⓘ", style = MaterialTheme.typography.titleMedium, color = ReviewPlum)
                    }
                    DropdownMenu(
                        expanded = showSourceDetails,
                        onDismissRequest = { showSourceDetails = false }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Source details", fontWeight = FontWeight.SemiBold, color = ReviewInk)
                            if (current.eventId > 0) Text("Event ID: ${current.eventId}", color = ReviewInk)
                            if (current.date.isNotBlank()) Text("Event date: ${current.date}", color = ReviewInk)
                            if (current.entryDate.isNotBlank()) Text("Entry date: ${current.entryDate}", color = ReviewInk)
                            if (current.modifiedDate.isNotBlank()) Text("Source modified: ${current.modifiedDate}", color = ReviewInk)
                        }
                    }
                }
            }
            HorizontalDivider(color = ReviewDivider, thickness = 1.dp)
            WobHighlightEditor(
                library = library,
                entry = current,
                sourceUrl = sourceLink,
                onSourceUrlChange = { sourceLink = it },
                onSaveSourceUrl = {
                    message = if (library.saveOriginalUrl(current.id, sourceLink)) "✓ Original source link saved." else "Enter a valid http(s) source URL."
                }
            )
            SourceTagChips(current.sourceTags, tags) { tag -> tags = appendPersonalTag(tags, tag); dirty = true; message = "" }
            if (current.originalUrl.isBlank() && sourceLink.isBlank()) Text("Arcanum's API does not supply the external source URL.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = ReviewDivider, thickness = 1.dp)
            PersonalTagField(value = tags, onValueChange = { tags = it; dirty = true; message = "" }, library = library, revision = libraryRevision + localRefresh, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = notes, onValueChange = { notes = it; dirty = true; message = "" }, label = { Text("My notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            HorizontalDivider(color = ReviewDivider, thickness = 1.dp)
            Text("Work classification", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = cosmere, onCheckedChange = { cosmere = it; dirty = true; message = "" })
                Text("Cosmere", color = ReviewInk)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = extended, onCheckedChange = { extended = it; dirty = true; message = "" })
                Text("Extended Works", color = ReviewInk)
            }
            HorizontalDivider(color = ReviewDivider, thickness = 1.dp)
            Text("Workflow", color = ReviewPlum, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = export, onCheckedChange = { export = it; if (it) junk = false; dirty = true; message = "" })
                    Text("Needs export", color = ReviewInk)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = junk, onCheckedChange = { junk = it; if (it) export = false; dirty = true; message = "" })
                    Text("Junk", color = ReviewInk)
                }
            }
            if (!export && !junk) Text("Select Needs export or Junk to save; you can always skip.", color = Color(0xFFBC3E42), style = MaterialTheme.typography.bodySmall)
            Button(enabled = export || junk, onClick = {
                try {
                    if (library.completeReview(current.id, tags, notes, export, cosmere, extended, junk)) {
                        passedIds = passedIds + current.id
                        message = if (junk) "✓ Moved to Junk." else "✓ Reviewed and saved."
                        localRefresh++
                        onChanged()
                        onNext()
                    } else message = "Could not save review. This entry remains pending."
                } catch (e: Exception) { message = "Save failed. This entry remains pending." }
            }, colors = ButtonDefaults.buttonColors(containerColor = ReviewPlum)) {
                Text("Mark reviewed & next")
            }
            OutlinedButton(enabled = export || junk, onClick = {
                try {
                    if (library.saveReviewDraft(current.id, tags, notes, export, cosmere, extended, junk)) {
                        passedIds = passedIds + current.id
                        message = if (junk) "✓ Moved to Junk." else "✓ Draft saved. Entry remains in the review queue."
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
            Text("Choose Needs export or Junk to save an entry, or skip without making a selection. Mark reviewed completes the entry; Junk goes to its own library section. Save & next keeps Needs export entries pending; Skip discards all unsaved changes and leaves the entry pending.", style = MaterialTheme.typography.bodySmall, color = ReviewInk)
        }
    }
}
