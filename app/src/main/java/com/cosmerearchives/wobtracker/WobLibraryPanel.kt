package com.cosmerearchives.wobtracker

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

private val LibraryPlum = Color(0xFF6B5C87)
private val LibraryInk = Color(0xFF37343C)

@Composable
fun WobLibraryPanel(library: WobLibrary) {
    var query by remember { mutableStateOf("") }
    var filterBy by remember { mutableStateOf("All") }
    var categoryFilter by remember { mutableStateOf("All works") }
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import three sample WoBs to begin your offline library.") }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveSucceeded by remember { mutableStateOf(false) }
    val items = remember(query,filterBy,categoryFilter,refresh) { library.entries(query, filterBy, categoryFilter) }
    val current = items.firstOrNull { it.id == selectedId }
    var tags by remember(current?.id, refresh) { mutableStateOf(current?.myTags ?: "") }
    var notes by remember(current?.id, refresh) { mutableStateOf(current?.myNotes ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("My WoB Library", color = LibraryInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Saved locally on this device. Original source and your notes are stored separately.", color = LibraryInk)
        Button(
            enabled = !importing,
            onClick = {
                importing = true
                status = "Importing three sample WoBs…"
                thread {
                    val outcome = try {
                        val n = library.importSamples()
                        "Sample import finished ($n entries checked). Available offline."
                    } catch (e: Exception) {
                        "Import stopped: ${e.javaClass.simpleName}. Already saved entries remain available."
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        status = outcome
                        refresh++
                        importing = false
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = LibraryPlum)
        ) { Text(if (importing) "Importing…" else "Import 3 sample WoBs") }
        Text(status, color = LibraryPlum, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search offline library") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
            listOf("All", "Needs review", "Needs export", "Both").forEach { filter ->
                FilterChip(selected = filterBy == filter, onClick = { filterBy = filter }, label = { Text(filter) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("All works", "Cosmere", "Extended Works").forEach { category ->
                FilterChip(selected = categoryFilter == category, onClick = { categoryFilter = category }, label = { Text(category) })
            }
        }
        Text("${items.size} saved WoBs shown", color = LibraryInk, style = MaterialTheme.typography.labelMedium)
        items.forEach { item ->
            ElevatedCard(onClick = { selectedId = if (selectedId == item.id) null else item.id; saveMessage = null }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WoB #${item.id} · ${item.event}", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                    if (item.date.isNotBlank()) Text(item.date, style = MaterialTheme.typography.labelSmall)
                    if (item.myTags.isNotBlank()) Text("My tags: ${item.myTags}", style = MaterialTheme.typography.bodySmall)
                    if (item.cosmere || item.extendedWorks) Text(
                        listOfNotNull(if (item.cosmere) "Cosmere" else null, if (item.extendedWorks) "Extended Works" else null).joinToString(" · "),
                        color = LibraryPlum, style = MaterialTheme.typography.labelMedium
                    )
                    if (item.needsReview || item.needsExport) Text(
                        listOfNotNull(if (item.needsReview) "Needs review" else null, if (item.needsExport) "Needs export" else null).joinToString(" · "),
                        color = LibraryPlum, style = MaterialTheme.typography.labelMedium
                    )
                    if (selectedId == item.id) {
                        HorizontalDivider()
                        Text(Html.fromHtml(item.transcript, Html.FROM_HTML_MODE_COMPACT).toString(), color = LibraryInk)
                        if (item.sourceTags.isNotBlank()) Text("Source tags: ${item.sourceTags}", color = LibraryPlum)
                        Text("Work classification", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.cosmere, onCheckedChange = { checked ->
                                try {
                                    saveSucceeded = library.saveCategories(item.id, checked, item.extendedWorks)
                                    saveMessage = if (saveSucceeded) "✓ Work classification saved." else "Could not save classification."
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
                                    saveSucceeded = library.saveCategories(item.id, item.cosmere, checked)
                                    saveMessage = if (saveSucceeded) "✓ Work classification saved." else "Could not save classification."
                                    if (saveSucceeded) refresh++
                                } catch (e: Exception) {
                                    saveSucceeded = false
                                    saveMessage = "Could not save classification."
                                }
                            })
                            Text("Extended Works", color = LibraryInk)
                        }
                        Text("My workflow", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = item.needsReview, onCheckedChange = { checked ->
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
                            Checkbox(checked = item.needsExport, onCheckedChange = { checked ->
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
                        OutlinedTextField(value = tags, onValueChange = { tags = it; saveMessage = null }, label = { Text("My tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = notes, onValueChange = { notes = it; saveMessage = null }, label = { Text("My notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
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
    }
}
