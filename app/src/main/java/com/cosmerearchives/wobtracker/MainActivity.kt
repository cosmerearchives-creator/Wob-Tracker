package com.cosmerearchives.wobtracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Plum = Color(0xFF6B5C87)
private val Lavender = Color(0xFFECE2F8)
private val Background = Color(0xFFF6F4F0)
private val Ink = Color(0xFF37343C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val updates = UpdateManager(this)
        val probe = ArcanumProbe()
        val previewLoader = WobPreviewLoader()
        val library = WobLibrary(applicationContext)
        updates.check()
        setContent {
            val updateState by updates.state.collectAsState()
            var archiveResult by remember { mutableStateOf(ArchiveProbeResult("Not tested yet")) }
            var wobPreview by remember { mutableStateOf(WobPreviewState()) }
            var showWob by remember { mutableStateOf(false) }
            var libraryRevision by remember { mutableIntStateOf(0) }
            var selectedTab by remember { mutableStateOf("Library") }

            MaterialTheme(colorScheme = lightColorScheme(primary = Plum, background = Background, surface = Color.White, onBackground = Ink)) {
                Column(Modifier.fillMaxSize().background(Background)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("WoB Tracker", fontSize = 29.sp, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedTab = if (selectedTab == "Tools") "Library" else "Tools" }) {
                            Text(if (selectedTab == "Tools") "Back" else "Tools", color = Plum)
                        }
                    }
                    if (updateState.downloadUrl != null) {
                        Surface(color = Lavender, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(updateState.message, color = Ink, modifier = Modifier.weight(1f))
                                TextButton(onClick = { selectedTab = "Tools" }) { Text("View", color = Plum) }
                            }
                        }
                    }
                    PrimaryTabRow(selectedTabIndex = when (selectedTab) { "Library" -> 0; "Review" -> 1; else -> 2 }) {
                        listOf("Library", "Review", "Tools").forEach { tab ->
                            Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(tab) })
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (selectedTab) {
                            "Library" -> {
                                Text("Your personal Word of Brandon archive", color = Plum)
                                Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        ArchiveBrowser(library) { libraryRevision++ }
                                    }
                                }
                                Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        WobLibraryPanel(library, libraryRevision)
                                    }
                                }
                            }
                            "Review" -> {
                                Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        ReviewMode(library, libraryRevision) { libraryRevision++ }
                                    }
                                }
                            }
                            else -> {
                                Text("App tools & diagnostics", color = Plum, style = MaterialTheme.typography.titleMedium)
                                Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(updateState.message, color = Ink)
                                        if (updateState.downloadUrl != null) {
                                            Button(onClick = { updates.installLatest() }) { Text("Install update") }
                                        }
                                        OutlinedButton(onClick = { updates.check() }) { Text("Check for updates") }
                                    }
                                }
                                Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Update test", color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text("If you can read this message, the earlier updater test succeeded.", color = Ink)
                                    }
                                }
                                Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Arcanum connection test", color = Ink, fontWeight = FontWeight.SemiBold)
                                        Text(archiveResult.status, color = Ink)
                                        archiveResult.total?.let { Text("Archive reports $it entries", color = Plum) }
                                        archiveResult.examples.forEach { Text(it, color = Ink) }
                                        Button(onClick = { probe.run { archiveResult = it } }) { Text("Test Arcanum API") }
                                    }
                                }
                                Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Single WoB preview", color = Ink, fontWeight = FontWeight.SemiBold)
                                        Button(onClick = {
                                            showWob = true
                                            previewLoader.load { wobPreview = it }
                                        }) { Text(if (showWob) "Reload WoB #1356" else "Open sample WoB") }
                                        if (showWob) {
                                            if (wobPreview.loading) CircularProgressIndicator(color = Plum)
                                            wobPreview.error?.let { Text(it, color = Color(0xFFBC3E42)) }
                                            wobPreview.entry?.let { entry ->
                                                Text("WoB #${entry.id} · ${entry.event}", color = Plum, fontWeight = FontWeight.Bold)
                                                if (entry.eventDate.isNotBlank()) Text(entry.eventDate, color = Plum)
                                                entry.lines.forEach { line ->
                                                    Text(line.speaker, color = Plum, fontWeight = FontWeight.SemiBold)
                                                    Text(Html.fromHtml(line.text, Html.FROM_HTML_MODE_COMPACT).toString(), color = Ink)
                                                }
                                                if (entry.note.isNotBlank()) Text("Source note: " + Html.fromHtml(entry.note, Html.FROM_HTML_MODE_COMPACT).toString(), color = Ink)
                                                if (entry.tags.isNotEmpty()) Text("Source tags: " + entry.tags.joinToString(" · "), color = Plum)
                                                OutlinedButton(onClick = {
                                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wob.coppermind.net/entry/${entry.id}/")))
                                                }) { Text("View original on Arcanum") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text("Version " + BuildConfig.VERSION_NAME, color = Plum, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
