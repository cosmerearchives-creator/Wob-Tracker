package com.cosmerearchives.wobtracker

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
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
        updates.check()
        setContent {
            val state by updates.state.collectAsState()
            var archiveResult by remember { mutableStateOf(ArchiveProbeResult("Not tested yet")) }
            var wobPreview by remember { mutableStateOf(WobPreviewState()) }
            var showWob by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = lightColorScheme(primary = Plum, background = Background, surface = Color.White, onBackground = Ink)) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text("WoB Tracker", fontSize = 33.sp, color = Ink, fontWeight = FontWeight.Bold)
                    Text("Word of Brandon companion", color = Plum, fontSize = 16.sp)
                    Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.message, color = Ink)
                            if (state.downloadUrl != null) {
                                Button(onClick = { updates.installLatest() }, colors = ButtonDefaults.buttonColors(containerColor = Plum)) {
                                    Text("Install update")
                                }
                            }
                            OutlinedButton(onClick = { updates.check() }) { Text("Check for updates") }
                        }
                    }
                    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Text("Your collection starts here", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("Update test: if you can read this message, the new APK installed successfully!", color = Ink)
                        }
                    }
                    Surface(color = Lavender, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Arcanum connection test", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(archiveResult.status, color = Ink)
                            archiveResult.total?.let { Text("Archive reports $it entries", color = Plum) }
                            archiveResult.examples.forEach { Text(it, color = Ink, fontSize = 13.sp) }
                            Button(onClick = { probe.run { archiveResult = it } }, colors = ButtonDefaults.buttonColors(containerColor = Plum)) {
                                Text("Test Arcanum API")
                            }
                        }
                    }
                    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Single WoB preview", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                            Text("Read an actual entry from Arcanum. Personal tags and notes are placeholders for now.", color = Ink, fontSize = 14.sp)
                            Button(onClick = {
                                showWob = true
                                previewLoader.load { wobPreview = it }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Plum)) {
                                Text(if (showWob) "Reload WoB #1356" else "Open sample WoB")
                            }
                            if (showWob) {
                                if (wobPreview.loading) CircularProgressIndicator(color = Plum)
                                wobPreview.error?.let { Text(it, color = Color(0xFFBC3E42)) }
                                wobPreview.entry?.let { entry ->
                                    Text("WoB #${entry.id}", color = Plum, fontWeight = FontWeight.Bold)
                                    Text(entry.event, color = Ink, fontWeight = FontWeight.Medium)
                                    if (entry.eventDate.isNotBlank()) Text(entry.eventDate, color = Plum, fontSize = 13.sp)
                                    entry.lines.forEach { line ->
                                        Text(line.speaker, color = Plum, fontWeight = FontWeight.SemiBold)
                                        Text(Html.fromHtml(line.text, Html.FROM_HTML_MODE_COMPACT).toString(), color = Ink)
                                    }
                                    if (entry.note.isNotBlank()) Text("Source note: " + Html.fromHtml(entry.note, Html.FROM_HTML_MODE_COMPACT).toString(), color = Ink, fontSize = 13.sp)
                                    if (entry.tags.isNotEmpty()) Text("Arcanum tags: " + entry.tags.joinToString(" · "), color = Plum, fontSize = 13.sp)
                                    OutlinedButton(onClick = {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wob.coppermind.net/entry/${entry.id}/")))
                                    }) { Text("View original on Arcanum") }
                                    HorizontalDivider()
                                    Text("My tags — coming soon", color = Plum)
                                    Text("My notes — coming soon", color = Plum)
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
