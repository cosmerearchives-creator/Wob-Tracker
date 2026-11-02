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
import kotlin.concurrent.thread

private val LibraryPlum = Color(0xFF6B5C87)
private val LibraryInk = Color(0xFF37343C)

@Composable
fun WobLibraryPanel(library: WobLibrary) {
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Import three sample WoBs to begin your offline library.") }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saveSucceeded by remember { mutableStateOf(false) }
    val items = remember(query,refresh) { library.entries(query) }
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
        Text("${items.size} saved WoBs shown", color = LibraryInk, style = MaterialTheme.typography.labelMedium)
        items.forEach { item ->
            ElevatedCard(onClick = { selectedId = if (selectedId == item.id) null else item.id; saveMessage = null }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WoB #${item.id} · ${item.event}", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                    if (item.date.isNotBlank()) Text(item.date, style = MaterialTheme.typography.labelSmall)
                    if (item.myTags.isNotBlank()) Text("My tags: ${item.myTags}", style = MaterialTheme.typography.bodySmall)
                    if (selectedId == item.id) {
                        HorizontalDivider()
                        Text(Html.fromHtml(item.transcript, Html.FROM_HTML_MODE_COMPACT).toString(), color = LibraryInk)
                        if (item.sourceTags.isNotBlank()) Text("Source tags: ${item.sourceTags}", color = LibraryPlum)
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
