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
            ElevatedCard(onClick = { selectedId = if (selectedId == item.id) null else item.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WoB #${item.id} · ${item.event}", color = LibraryPlum, fontWeight = FontWeight.SemiBold)
                    if (item.date.isNotBlank()) Text(item.date, style = MaterialTheme.typography.labelSmall)
                    if (item.myTags.isNotBlank()) Text("My tags: ${item.myTags}", style = MaterialTheme.typography.bodySmall)
                    if (selectedId == item.id) {
                        HorizontalDivider()
                        Text(Html.fromHtml(item.transcript, Html.FROM_HTML_MODE_COMPACT).toString(), color = LibraryInk)
                        if (item.sourceTags.isNotBlank()) Text("Source tags: ${item.sourceTags}", color = LibraryPlum)
                        OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("My tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("My notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(onClick = {
                            library.savePersonal(item.id,tags,notes)
                            status = "Saved personal tags and notes for #${item.id}."
                            refresh++
                        }, colors = ButtonDefaults.buttonColors(containerColor = LibraryPlum)) { Text("Save my changes") }
                    }
                }
            }
        }
    }
}
