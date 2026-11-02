package com.cosmerearchives.wobtracker

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

@Composable
fun BackupTools(library: WobLibrary, onRestored: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("Choose a location outside your phone for an extra copy.") }
    var verified by remember { mutableStateOf<Int?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf("") }

    fun begin(label: String) {
        busy = true; stage = label; progressDone = 0; progressTotal = 0
        status = "$label…"
    }
    fun update(done: Int, total: Int) {
        main.post { progressDone = done; progressTotal = total }
    }
    fun finish(message: String) {
        main.post { busy = false; stage = ""; status = message }
    }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !busy) {
            begin("Creating backup")
            thread {
                try {
                    val n = context.contentResolver.openOutputStream(uri)?.use { LibraryTransfer.backup(library, it, ::update) }
                        ?: error("Cannot write backup")
                    finish("Backup created for $n entries. Keep this file safe.")
                } catch (e: Exception) { finish("Backup failed: ${e.message}") }
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) {
            begin("Exporting to Archives")
            thread {
                try {
                    val n = context.contentResolver.openOutputStream(uri)?.use { LibraryTransfer.exportArchives(library,it) }
                        ?: error("Cannot write export")
                    finish("Exported $n entries. Nothing was marked as transferred.")
                } catch (e: Exception) { finish("Export failed: ${e.message}") }
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = null; verified = null
        if (uri != null && !busy) {
            begin("Loading and verifying backup")
            thread {
                try {
                    val count = context.contentResolver.openInputStream(uri)?.use { LibraryTransfer.verify(it) }
                        ?: error("Cannot read backup")
                    main.post {
                        busy = false; stage = ""; verified = count; restoreUri = uri
                        status = "Verified $count entries. Merge adds missing entries and highlights without replacing your notes. Confirm below."
                    }
                } catch (e: Exception) { finish("Verification failed: ${e.message}") }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Backup & Restore", style = MaterialTheme.typography.titleLarge)
        Text(status)
        if (busy) {
            Text(if (progressTotal > 0) "$stage: $progressDone / $progressTotal entries" else "$stage…")
            if (progressTotal > 0) {
                LinearProgressIndicator(progress = { progressDone.toFloat() / progressTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Button(enabled = !busy, onClick = { backup.launch("wob-tracker-backup.zip") }) { Text("Create complete backup ZIP") }
        OutlinedButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/zip","application/octet-stream","*/*")) }) { Text("Verify or restore backup") }
        if (!busy && restoreUri != null && verified != null) {
            Button(onClick = {
                val selected = restoreUri ?: return@Button
                begin("Restoring backup")
                thread {
                    try {
                        val counts = context.contentResolver.openInputStream(selected)?.use {
                            LibraryTransfer.restoreMerge(library,it,::update)
                        } ?: error("Could not read backup")
                        main.post {
                            restoreUri = null; verified = null
                            onRestored()
                        }
                        finish("Restore complete: ${counts.first} added, ${counts.second} already present. Missing highlights merged.")
                    } catch (e: Exception) { finish("Restore failed: ${e.message}") }
                }
            }) { Text("Confirm safe merge restore") }
            TextButton(onClick = { restoreUri = null; verified = null }) { Text("Cancel restore") }
        }
        HorizontalDivider()
        Text("Transfer to Archives of Arcanum", style = MaterialTheme.typography.titleMedium)
        Text("Export only entries marked Needs export. This does not archive or confirm delivery.")
        OutlinedButton(enabled = !busy, onClick = { export.launch("wob-archives-transfer.json") }) { Text("Create Archives export file") }
        Text("Archives import is not yet available. Save this file until the receiving app supports it.", style = MaterialTheme.typography.bodySmall)
    }
}
