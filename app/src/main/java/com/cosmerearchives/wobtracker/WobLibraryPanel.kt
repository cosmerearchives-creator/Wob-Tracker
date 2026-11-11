package com.cosmerearchives.wobtracker

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LibraryPlum = Color(0xFF6B5C87)
private val LibraryInk = Color(0xFF37343C)

@Composable
fun WobLibraryPanel(library: WobLibrary, libraryRevision: Int = 0) {
    val uriHandler = LocalUriHandler.current
    var query by remember { mutableStateOf("") }
    var librarySection by remember { mutableStateOf("Needs Review") }
    var categoryFilter by remember { mutableStateOf("All works") }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveSucceeded by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, librarySection, categoryFilter) {
        pageIndex = 0
        selectedId = null
    }
    val totalCount = remember(query, categoryFilter, librarySection, refresh, libraryRevision) {
        library.countLibrarySection(query, categoryFilter, librarySection)
    }
    val lastPage = ((totalCount - 1).coerceAtLeast(0)) / 50
    LaunchedEffect(lastPage) { if (pageIndex > lastPage) pageIndex = lastPage }
    val items = remember(query, categoryFilter, librarySection, pageIndex, refresh, libraryRevision) {
        library.libraryPage(query, categoryFilter, librarySection, pageIndex)
    }
    val current = items.firstOrNull { it.id == selectedId }
    var tags by remember(current?.id, refresh) { mutableStateOf(current?.myTags ?: "") }
    var notes by remember(current?.id, refresh) { mutableStateOf(current?.myNotes ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("My WoB Library", color = LibraryInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Saved locally on this device. Original source and your notes are stored separately.", color = LibraryInk)
        TabRow(selectedTabIndex = listOf("Needs Review", "Reviewed", "Junk").indexOf(librarySection)) {
            listOf("Needs Review", "Reviewed", "Junk").forEach { section ->
                Tab(selected = librarySection == section, onClick = {
                    librarySection = section
                    selectedId = null
                    saveMessage = null
                }, text = { Text(section) })
            }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search offline library") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("All works", "Cosmere", "Extended Works", "Writing Excuses").forEach { category ->
                FilterChip(selected = categoryFilter == category, onClick = { categoryFilter = category }, label = { Text(category) })
            }
        }
        Text("$totalCount ${librarySection.lowercase()} WoBs · showing page ${pageIndex + 1} of ${lastPage + 1}",
            color = LibraryInk, style = MaterialTheme.typography.labelMedium)
        if (totalCount > 50) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(enabled = pageIndex > 0, onClick = { pageIndex--; selectedId = null }) { Text("Previous 50") }
                OutlinedButton(enabled = pageIndex < lastPage, onClick = { pageIndex++; selectedId = null }) { Text("Next 50") }
            }
        }
        items.forEach { item ->
            ElevatedCard(onClick = { selectedId = if (selectedId == item.id) null else item.id; saveMessage = null }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WoB #${item.id} · ${item.event}", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                    if (item.date.isNotBlank()) Text(item.date, style = MaterialTheme.typography.labelSmall)
                    if (item.myTags.isNotBlank()) Text("My tags: ${item.myTags}", style = MaterialTheme.typography.bodySmall)
                    if (item.cosmere || item.extendedWorks || item.writingExcuses) Text(
                        listOfNotNull(if (item.cosmere) "Cosmere" else null, if (item.extendedWorks) "Extended Works" else null, if (item.writingExcuses) "Writing Excuses" else null).joinToString(" · "),
                        color = LibraryPlum, style = MaterialTheme.typography.labelMedium
                    )
                    if (item.needsReview || item.needsExport || item.junk) Text(
                        listOfNotNull(if (item.needsReview) "Needs review" else null, if (item.needsExport) "Needs export" else null, if (item.junk) "Junk" else null).joinToString(" · "),
                        color = LibraryPlum, style = MaterialTheme.typography.labelMedium
                    )
                    if (selectedId == item.id) {
                        HorizontalDivider()
                        TextButton(onClick = {
                            uriHandler.openUri("https://wob.coppermind.net/entry/${item.id}/")
                        }) { Text("View on Arcanum ↗") }
                        OutlinedButton(onClick = {
                            if (library.setArchived(item.id, !item.archived)) {
                                selectedId = null
                                refresh++
                            }
                        }) { Text(if (item.archived) "Return to active library" else "Archive entry (manual confirmation)") }
                        if (item.eventId > 0) Text("Event ID: ${item.eventId}", color = LibraryPlum)
                        if (item.date.isNotBlank()) Text("Event date: ${item.date}", color = LibraryPlum)
                        if (item.entryDate.isNotBlank()) Text("Entry date: ${item.entryDate}", color = LibraryPlum)
                        WobHighlightEditor(library, item)
                        if (item.originalUrl.isNotBlank()) {
                            Text("Original source: ${item.originalUrl}", color = LibraryPlum)
                        }
                        SourceFootnote(library, item) { refresh++ }
                        WobImageEditor(library, item.id) { refresh++ }
                        SourceTagChips(item.sourceTags, tags) { tag -> tags = appendPersonalTag(tags, tag); saveMessage = null }
                        PersonalTagField(value = tags, onValueChange = { tags = it; saveMessage = null }, library = library, revision = libraryRevision + refresh, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = notes, onValueChange = { notes = it; saveMessage = null }, label = { Text("My notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Text("Work classification", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.cosmere, onCheckedChange = { checked ->
                                try {
                                    saveSucceeded = library.saveCategories(item.id, checked, item.extendedWorks, item.writingExcuses)
                                    saveMessage = if (saveSucceeded) "✓ Work classification saved." else "Select at least one work classification."
                                    if (saveSucceeded) refresh++
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save classification."
                                }
                            })
                            Text("Cosmere", color = LibraryInk)
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.extendedWorks, onCheckedChange = { checked ->
                                try {
                                    saveSucceeded = library.saveCategories(item.id, item.cosmere, checked, item.writingExcuses)
                                    saveMessage = if (saveSucceeded) "✓ Work classification saved." else "Could not save classification."
                                    if (saveSucceeded) refresh++
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save classification."
                                }
                            })
                            Text("Extended Works", color = LibraryInk)
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.writingExcuses, onCheckedChange = { checked ->
                                try {
                                    saveSucceeded = library.saveCategories(item.id, item.cosmere, item.extendedWorks, checked)
                                    saveMessage = if (saveSucceeded) "✓ Work classification saved." else "Select at least one work classification."
                                    if (saveSucceeded) refresh++
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save classification."
                                }
                            })
                            Text("Writing Excuses", color = LibraryInk)
                        }
                        Text("My workflow", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(enabled = !item.junk, checked = item.needsReview, onCheckedChange = { checked ->
                                try {
                                    if (library.saveFlags(item.id, checked, item.needsExport)) {
                                        refresh++
                                        saveSucceeded = true
                                        saveMessage = "✓ Workflow status saved."
                                    } else {
                                        saveSucceeded = false
                                        saveMessage = "Could not save workflow status."
                                    }
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save workflow status."
                                }
                            })
                            Text("Needs review", color = LibraryInk)
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(enabled = !item.junk, checked = item.needsExport, onCheckedChange = { checked ->
                                try {
                                    if (library.saveFlags(item.id, item.needsReview, checked)) {
                                        refresh++
                                        saveSucceeded = true
                                        saveMessage = "✓ Workflow status saved."
                                    } else {
                                        saveSucceeded = false
                                        saveMessage = "Could not save workflow status."
                                    }
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save workflow status."
                                }
                            })
                            Text("Needs export", color = LibraryInk)
                        }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.junk, onCheckedChange = { checked ->
                                try {
                                    if (library.setJunk(item.id, checked)) {
                                        refresh++
                                        saveSucceeded = true
                                        saveMessage = if (checked) "✓ Moved to Junk." else "✓ Restored to review."
                                    } else {
                                        saveSucceeded = false
                                        saveMessage = "Could not change Junk status."
                                    }
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not change Junk status."
                                }
                            })
                            Text("Junk", color = LibraryInk)
                        }
                        Button(onClick = {
                            try {
                                if (library.savePersonal(item.id, tags, notes)) {
                                    refresh++
                                    saveSucceeded = true
                                    saveMessage = "✓ Notes and tags saved successfully."
                                } else {
                                    saveSucceeded = false
                                    saveMessage = "Save failed: this entry was not found."
                                }
                            } catch (e: Exception) {
                                saveSucceeded = false
                                saveMessage = "Save failed. Your changes have not been confirmed."
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = LibraryPlum)) { Text("Save my changes") }
                        if (saveMessage != null) {
                            Text(
                                saveMessage!!,
                                color = if (saveSucceeded) Color(0xFF386D52) else Color(0xFFBC3E42),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        if (totalCount > 50) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = pageIndex > 0, onClick = { pageIndex--; selectedId = null }) { Text("Previous 50") }
                OutlinedButton(enabled = pageIndex < lastPage, onClick = { pageIndex++; selectedId = null }) { Text("Next 50") }
            }
        }
    }
}
