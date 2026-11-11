package com.cosmerearchives.wobtracker

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArcanumSharingTest {
    @Test fun officialArchivesColdStartRoundTrip() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("archivesIntegration") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("am force-stop com.cosmerearchives.silverlight").close()
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val library = WobLibrary(context)
        library.saveSource(SavedWob(987603, "Round trip event", "2026-01-01", "Offline source transcript", "Cosmere", "", ""))
        library.savePersonal(987603, "Round trip", "Personal notes must survive")
        library.saveFlags(987603, true, true)
        library.addHighlight(987603, 0, 7, "Offline")
        val intent = ArcanumSharing.prepare(context, library).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val deadline = System.currentTimeMillis() + 90000
        var saved = false
        while (System.currentTimeMillis() < deadline) {
            val row = library.entries(archiveView = "All").first { it.id == 987603 }
            if (row.archived && !row.needsExport) { saved = true; break }
            Thread.sleep(200)
        }
        assertTrue("Official Archives must confirm a durable offline cold-start save", saved)
        val row = library.entries(archiveView = "All").first { it.id == 987603 }
        assertTrue(row.needsReview); assertEquals("Personal notes must survive", row.myNotes)
        assertEquals(1, library.highlights(987603).size)
        library.close()
    }

    @Test fun cancelPendingShareKeepsAllQueuedDataAndInvalidatesOldAcknowledgment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = WobLibrary(context)
        val id = 989903
        library.saveSource(SavedWob(id, "Cancelled share test", "2026-11-10", "Source transcript", "Cosmere", "", ""))
        library.savePersonal(id, "Tag", "Important note")
        library.saveFlags(id, true, true)
        library.addHighlight(id, 0, 6, "Source")
        val receiptToken = java.util.UUID.randomUUID().toString()
        val receipt = File(context.filesDir, "arcanum-$receiptToken.json")
        receipt.writeText(LibraryTransfer.recordsForIds(library, setOf(id)).toString())
        val prefs = ArcanumSharing.preferences(context)
        prefs.edit().putBoolean("pending", true).putString("state", "Waiting").commit()
        ArcanumSharing.cancelPendingShare(context)
        assertFalse(receipt.exists())
        assertFalse(prefs.getBoolean("pending", true))
        val oldCallback = PendingIntent.getBroadcast(
            context, 22, Intent(context, ArcanumSaveReceiver::class.java)
                .setData(Uri.parse("arcanum-save:$receiptToken")),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        oldCallback.send(context, Activity.RESULT_OK, null)
        Thread.sleep(400)
        val stored = library.entries(archiveView = "All").first { it.id == id }
        assertFalse(stored.archived)
        assertTrue(stored.needsExport)
        assertEquals("Important note", stored.myNotes)
        assertEquals(1, library.highlights(id).size)
        library.close()
    }

    @Test fun acknowledgmentKeepsEditsAndReviewDataAndIgnoresCanceledTransfers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = WobLibrary(context)
        val first = SavedWob(987601, "Test event", "2026-01-01", "Transcript", "Cosmere", "Personal", "My notes", needsReview = true, needsExport = true)
        for (id in listOf(987601, 987602)) {
            library.saveSource(first.copy(id = id)); library.savePersonal(id, "Personal", "My notes"); library.saveFlags(id, true, true)
            library.addHighlight(id, 0, 4, "Tran")
        }
        val records = LibraryTransfer.exportRecords(library)
        val token = java.util.UUID.randomUUID().toString()
        val pending = File(context.filesDir, "arcanum-$token.json").apply { writeText(records.toString()) }
        val callback = PendingIntent.getBroadcast(context, 20, Intent(context, ArcanumSaveReceiver::class.java).setData(Uri.parse("arcanum-save:$token")), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        library.savePersonal(987602, "Personal", "Edited while transfer was pending")
        // Merely constructing the export/callback has no effect.
        assertFalse(library.entries(archiveView = "All").first { it.id == 987601 }.archived)
        callback.send(context, Activity.RESULT_OK, null)
        val deadline = System.currentTimeMillis() + 10000
        while (pending.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(pending.exists())
        val rows = library.entries(archiveView = "All").associateBy { it.id }
        assertTrue(rows.getValue(987601).archived); assertFalse(rows.getValue(987601).needsExport)
        assertTrue(rows.getValue(987601).needsReview); assertEquals("My notes", rows.getValue(987601).myNotes)
        assertEquals(1, library.highlights(987601).size)
        assertFalse(rows.getValue(987602).archived); assertTrue(rows.getValue(987602).needsExport)
        assertEquals("Edited while transfer was pending", rows.getValue(987602).myNotes)
        val canceled = java.util.UUID.randomUUID().toString()
        val canceledFile = File(context.filesDir, "arcanum-$canceled.json").apply { writeText(LibraryTransfer.exportRecords(library).toString()) }
        PendingIntent.getBroadcast(context, 21, Intent(context, ArcanumSaveReceiver::class.java).setData(Uri.parse("arcanum-save:$canceled")), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).send(context, Activity.RESULT_CANCELED, null)
        Thread.sleep(300); assertTrue(canceledFile.exists()); assertTrue(library.entries(archiveView = "All").first { it.id == 987602 }.needsExport)
        library.close()
    }
}
