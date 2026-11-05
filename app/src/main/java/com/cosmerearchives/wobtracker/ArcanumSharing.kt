package com.cosmerearchives.wobtracker

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ContentValues
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sender retains a durable snapshot; delivery and Share-sheet completion never confirm a save. */
object ArcanumSharing {
    const val TARGET = "com.cosmerearchives.silverlight"
    const val MIME = "application/vnd.arcanum.package+zip"
    fun preferences(context: Context) = context.getSharedPreferences("arcanum-transfers", Context.MODE_PRIVATE)
    fun manifest(records: JSONArray, token: String): JSONObject {
        val items = JSONArray()
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val tags = (record.getString("source_tags") + "," + record.getString("my_tags")).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val body = record.getString("transcript") + "\n\nSource note: " + record.optString("source_note") + "\n\nMy notes: " + record.getString("my_notes")
            items.put(JSONObject().put("sourceId", "wob:" + record.getInt("id"))
                .put("title", "WoB #${record.getInt("id")} · ${record.getString("event")}")
                .put("body", body).put("tags", JSONArray(tags))
                .put("sourceUrl", record.optString("original_url").ifBlank { "https://wob.coppermind.net/entry/${record.getInt("id")}/" })
                .put("metadata", record).put("assets", JSONArray()))
        }
        return JSONObject().put("format", "arcanum-package").put("version", 1).put("packageId", token).put("origin", "com.cosmerearchives.wobtracker").put("items", items)
    }
    fun write(records: JSONArray, token: String, output: java.io.OutputStream) {
        require(records.length() in 1..1000) { "Mark 1–1000 entries for each transfer." }
        val bytes = manifest(records, token).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4*1024*1024) { "Transfer text exceeds 4 MiB. Export fewer entries." }
        ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry() }
    }
    fun prepare(context: Context, library: WobLibrary): Intent {
        // Pin the official Archives certificate before handing it the acknowledgment capability.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(TARGET, if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!
        require(signatures.size == 1 && MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray()).joinToString("") { "%02x".format(it) } == "9f2f5c1e80a857999725e4f980e525a8d76e11e1") { "Install the official Archives of Arcanum Android release first." }
        require((if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()) >= 1400001) { "Update Archives of Arcanum to 0.14.1 or newer first." }
        val records = LibraryTransfer.exportRecords(library)
        val token = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "arcanum").apply { mkdirs() }
        // Expire only old transfer cache files, never library data or backup files.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L*86400000 }?.forEach { it.delete() }
        val file = File(dir, "$token.arcanum")
        write(records, token, file.outputStream())
        val pending = File(context.filesDir, "arcanum-$token.json")
        pending.outputStream().use { out -> out.write(records.toString().toByteArray()); out.flush(); out.fd.sync() }
        val callback = PendingIntent.getBroadcast(context, 0, Intent(context, ArcanumSaveReceiver::class.java).setData(android.net.Uri.parse("arcanum-save:$token")), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).setPackage(TARGET).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("org.arcanum.extra.SAVE_ACK", callback).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("Archives package", uri)
        return intent
    }
}

class ArcanumSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (resultCode != Activity.RESULT_OK) return
        val token = intent.data?.schemeSpecificPart ?: return
        if (!Regex("[a-f0-9-]{36}").matches(token)) return
        val pending = File(context.filesDir, "arcanum-$token.json")
        if (!pending.isFile) return
        val completion = goAsync()
        kotlin.concurrent.thread {
            try {
                val sent = JSONArray(pending.readText())
                val library = WobLibrary(context)
                val db = library.writableDatabase
                var archived = 0
                db.beginTransaction()
                try {
                    val current = LibraryTransfer.recordsForIds(library, (0 until sent.length()).map { sent.getJSONObject(it).getInt("id") }.toSet())
                    val unchanged = (0 until current.length()).associate { val r = current.getJSONObject(it); r.getInt("id") to r.toString() }
                    for (i in 0 until sent.length()) {
                        val r = sent.getJSONObject(i)
                        if (unchanged[r.getInt("id")] == r.toString()) archived += db.update("entries", ContentValues().apply { put("needs_export", 0); put("archived", 1) }, "id=? AND needs_export=1 AND archived=0 AND junk=0", arrayOf(r.getInt("id").toString()))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction(); library.close() }
                pending.delete()
                ArcanumSharing.preferences(context).edit().putString("status", "Archives confirmed the save. $archived unchanged entries archived; entries edited during transfer stay queued. Review notes and highlights are preserved.").commit()
            } catch (e: Exception) {
                ArcanumSharing.preferences(context).edit().putString("status", "Could not apply save acknowledgment. Entries were kept safe: ${e.message}").commit()
            } finally { completion.finish() }
        }
    }
}
