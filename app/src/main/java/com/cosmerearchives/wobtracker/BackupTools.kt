package com.cosmerearchives.wobtracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun BackupTools(library: WobLibrary, onRestored: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Choose a location outside your phone for an extra copy.") }
    var verified by remember { mutableStateOf<Int?>(null) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) try {
            val n = context.contentResolver.openOutputStream(uri)?.use { LibraryTransfer.backup(library,it) }
                ?: error("Cannot write backup")
            status = "Backup created for $n entries. Keep this file safe."
        } catch (e: Exception) { status = "Backup failed: ${e.message}" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val n = context.contentResolver.openOutputStream(uri)?.use { LibraryTransfer.exportArchives(library,it) }
                ?: error("Cannot write export")
            status = "Exported $n entries to file. Nothing was marked as transferred."
        } catch (e: Exception) { status = "Export failed: ${e.message}" }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = null; verified = null
        if (uri != null) try {
            verified = context.contentResolver.openInputStream(uri)?.use { LibraryTransfer.verify(it) }
                ?: error("Cannot read backup")
            restoreUri = uri
            status = "Verified $verified entries. Merge adds missing entries only; it will not overwrite any existing entries or notes. Confirm below."
        } catch (e: Exception) { status = "Verification failed: ${e.message}" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Backup & Restore", style = MaterialTheme.typography.titleLarge)
        Text(status)
        Button(onClick = { backup.launch("wob-tracker-backup.zip") }) { Text("Create complete backup ZIP") }
        OutlinedButton(onClick = { restore.launch(arrayOf("application/zip","application/octet-stream","*/*")) }) { Text("Verify or restore backup") }
        if (restoreUri != null && verified != null) {
            Button(onClick = {
                try {
                    val counts = context.contentResolver.openInputStream(restoreUri!!)?.use { LibraryTransfer.restoreMerge(library,it) }
                        ?: error("Could not read backup")
                    status = "Restore complete: ${counts.first} added, ${counts.second} already present."
                    onRestored()
                } catch (e: Exception) { status = "Restore failed: ${e.message}" }
                restoreUri = null; verified = null
            }) { Text("Confirm safe merge restore") }
            TextButton(onClick = { restoreUri = null; verified = null }) { Text("Cancel restore") }
        }
        HorizontalDivider()
        Text("Transfer to Archives of Arcanum", style = MaterialTheme.typography.titleMedium)
        Text("Export only entries marked Needs export. This does not archive or confirm delivery.")
        OutlinedButton(onClick = { export.launch("wob-archives-transfer.json") }) { Text("Create Archives export file") }
        Text("Archives import is not yet available. Save this file until the receiving app supports it.", style = MaterialTheme.typography.bodySmall)
    }
}
