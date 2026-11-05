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
    val transferPreferences = remember { ArcanumSharing.preferences(context) }
    var transferStatus by remember { mutableStateOf(transferPreferences.getString("status", "No transfer pending.") ?: "") }
    DisposableEffect(transferPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "status") main.post { transferStatus = transferPreferences.getString("status", "") ?: ""; onRestored() }
        }
        transferPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { transferPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArcanumSharing.MIME)) { uri ->
        if (uri != null && !busy) {
            begin("Exporting to Archives")
            thread {
                try {
                    val n = context.contentResolver.openOutputStream(uri)?.use { LibraryTransfer.exportPackage(library,it) }
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
        OutlinedButton(enabled = !busy, onClick = { export.launch("wob-archives-transfer.arcanum") }) { Text("Create Archives export file") }
        Button(enabled = !busy, onClick = {
            begin("Preparing Archives share")
            // SQLite and ZIP work run off the UI thread. Launching share returns to the main thread.
            thread {
                try {
                    val intent = ArcanumSharing.prepare(context, library)
                    main.post {
                        try {
                            transferPreferences.edit().putString("status", "Waiting for Archives to confirm a saved import. Entries remain queued until acknowledgment.").apply()
                            context.startActivity(intent)
                            finish("Shared with Archives. Waiting for a verified save.")
                        } catch (e: Exception) { finish("Share failed; entries remain queued: "+e.message) }
                    }
                } catch (e: Exception) { finish("Share failed; entries remain queued: "+e.message) }
            }
        }) { Text("Send to Archives of Arcanum") }
        Text(transferStatus, style = MaterialTheme.typography.bodySmall)
    }
}
