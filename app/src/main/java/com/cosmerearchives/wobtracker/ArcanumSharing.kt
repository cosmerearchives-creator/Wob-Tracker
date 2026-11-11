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
data class TransferProgress(val stage: String, val completed: Long = 0, val total: Long = 0, val unit: String = "items")

object ArcanumSharing {
    const val TARGET = "com.cosmerearchives.silverlight"
    const val MIME = "application/vnd.arcanum.package+zip"
    fun preferences(context: Context) = context.getSharedPreferences("arcanum-transfers", Context.MODE_PRIVATE)
    /**
     * Abandon all old share receipts and transient packages without touching the
     * database, queued WoBs, notes, highlights, or saved attachments. An obsolete
     * Archives acknowledgment must find no receipt and therefore cannot archive.
     */
    fun cancelPendingShare(context: Context): Int {
        val receipts = context.filesDir.listFiles()?.filter {
            it.isFile && Regex("arcanum-[a-f0-9-]{36}\\.json").matches(it.name)
        }.orEmpty()
        for (receipt in receipts) {
            require(receipt.delete() || !receipt.exists()) { "Could not invalidate an old transfer receipt" }
        }
        // Removing transient ZIPs makes any future send regenerate its manifest
        // and image assets from the current WoB library.
        val cache = File(context.cacheDir, "arcanum")
        cache.listFiles()?.filter { it.isFile && it.extension == "arcanum" }?.forEach { it.delete() }
        val saved = preferences(context).edit()
            .putBoolean("pending", false)
            .putString("state", "Cancelled")
            .putString("status", "Pending transfer cancelled. All WoBs remain queued. Send again to create a new package.")
            .commit()
        require(saved) { "Could not save cancelled transfer state" }
        return receipts.size
    }

    fun manifest(records: JSONArray, token: String, progress: (TransferProgress) -> Unit = {}, images: Map<Int, WobLibrary.WobImage> = emptyMap()): JSONObject {
        val items = JSONArray()
        val relationships = JSONArray()
        for (i in 0 until records.length()) {
            progress(TransferProgress("Preparing Stickies and relationships", i.toLong(), records.length().toLong()))
            val record = records.getJSONObject(i)
            val tags = (record.getString("source_tags") + "," + record.getString("my_tags")).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val body = record.getString("transcript") + "\n\nSource note: " + record.optString("source_note")
            val wobId = record.getInt("id")
            val photo = images[wobId]
            val assetPath = "assets/wob-$wobId.jpg"
            val imageAsset = if (photo == null) JSONArray() else JSONArray().put(
                JSONObject().put("id", "wob:$wobId:image")
                    .put("path", assetPath)
                    .put("mimeType", "image/jpeg")
                    .put("name", "WoB #$wobId image.jpg")
            )
            items.put(JSONObject().put("sourceId", "wob:" + record.getInt("id"))
                .put("itemId", "wob:" + record.getInt("id"))
                .put("appearance", JSONObject().put("background", "#cfe8ff"))
                .put("title", "WoB #${record.getInt("id")} · ${record.getString("event")}")
                .put("body", body).put("tags", JSONArray(tags))
                .put("sourceUrl", record.optString("original_url").ifBlank { "https://wob.coppermind.net/entry/${record.getInt("id")}/" })
                .put("metadata", record).put("assets", JSONArray()))
            if (photo != null) {
                val sourceId = "wob:$wobId"
                val photoId = "$sourceId:attached-image"
                items.put(JSONObject()
                    .put("sourceId", photoId).put("itemId", photoId)
                    .put("title", "Attached image · WoB #$wobId")
                    .put("body", "").put("tags", JSONArray(tags))
                    .put("sourceUrl", record.optString("original_url").ifBlank { "https://wob.coppermind.net/entry/$wobId/" })
                    .put("metadata", JSONObject().put("wobId", wobId).put("role", "attached-image"))
                    .put("appearance", JSONObject().put("background", "#ffffff"))
                    .put("assets", imageAsset))
                relationships.put(JSONObject().put("id", "$sourceId:attached-image-link")
                    .put("from", sourceId).put("to", photoId)
                    .put("label", "Attached image").put("moveTogether", true))
            }
            if (record.getString("my_notes").isNotBlank()) {
                val sourceId = "wob:" + record.getInt("id")
                val noteId = "$sourceId:personal-note"
                items.put(JSONObject().put("sourceId", noteId).put("itemId", noteId)
                    .put("title", "Personal note · WoB #" + record.getInt("id"))
                    .put("body", record.getString("my_notes")).put("tags", JSONArray(tags))
                    .put("sourceUrl", record.optString("original_url").ifBlank { "https://wob.coppermind.net/entry/" + record.getInt("id") + "/" })
                    .put("metadata", JSONObject().put("wobId", record.getInt("id")).put("role", "personal-note"))
                    .put("appearance", JSONObject().put("background", "#ffffff")).put("assets", JSONArray()))
                relationships.put(JSONObject().put("id", "$sourceId:personal-note-link").put("from", sourceId).put("to", noteId).put("label", "Personal note").put("moveTogether", true))
            }
        }
        return JSONObject().put("format", "arcanum-package").put("version", 1).put("packageId", token).put("origin", "com.cosmerearchives.wobtracker").put("items", items).put("relationships", relationships)
    }
    fun write(records: JSONArray, token: String, output: java.io.OutputStream, progress: (TransferProgress) -> Unit = {}, library: WobLibrary? = null) {
        require(records.length() in 1..1000) { "Mark 1–1000 entries for each transfer." }
        val images = mutableMapOf<Int, WobLibrary.WobImage>()
        if (library != null) {
            for (i in 0 until records.length()) {
                val id = records.getJSONObject(i).getInt("id")
                library.imageFor(id)?.let { images[id] = it }
            }
        }
        val manifest = manifest(records, token, progress, images)
        // Each package asset must have exactly one owner and one ZIP entry.
        // The linked image sticky owns the image; the parent WoB references it
        // through the move-together relationship instead of a second descriptor.
        val seenAssetPaths = mutableSetOf<String>()
        val seenAssetIds = mutableSetOf<String>()
        val manifestItems = manifest.getJSONArray("items")
        for (i in 0 until manifestItems.length()) {
            val assets = manifestItems.getJSONObject(i).optJSONArray("assets") ?: JSONArray()
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                require(seenAssetIds.add(asset.getString("id"))) { "Duplicate asset ID in package" }
                require(seenAssetPaths.add(asset.getString("path"))) { "Duplicate asset path in package" }
            }
        }
        require(seenAssetPaths.size == images.size) { "Image manifest does not match attachments" }
        require(manifest.getJSONArray("items").length() <= 1000) { "A package supports up to 1000 Stickies, including personal notes. Export fewer WoBs." }
        progress(TransferProgress("Serializing package manifest"))
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4*1024*1024) { "Transfer text exceeds 4 MiB. Export fewer entries." }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            for (at in bytes.indices step 65536) {
                val count = minOf(65536, bytes.size-at)
                progress(TransferProgress("Compressing package", at.toLong(), bytes.size.toLong(), "bytes"))
                zip.write(bytes, at, count)
            }
            zip.closeEntry()
            for ((id, image) in images) {
                require(image.mimeType == "image/jpeg" && image.bytes.size in 1..2_000_000) { "Invalid image for WoB #$id" }
                zip.putNextEntry(ZipEntry("assets/wob-$id.jpg"))
                zip.write(image.bytes)
                zip.closeEntry()
            }
        }
    }
    fun prepare(context: Context, library: WobLibrary, progress: (TransferProgress) -> Unit = {}): Intent {
        progress(TransferProgress("Checking Archives receiver"))
        // Pin the official Archives certificate before handing it the acknowledgment capability.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(TARGET, if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!
        require(signatures.size == 1 && MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray()).joinToString("") { "%02x".format(it) } == "9f2f5c1e80a857999725e4f980e525a8d76e11e1") { "Install the official Archives of Arcanum Android release first." }
        require((if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()) >= 1400002) { "Update Archives of Arcanum to 0.14.2 or newer first." }
        val records = LibraryTransfer.exportRecords(library, progress)
        val token = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "arcanum").apply { mkdirs() }
        // Expire only old transfer cache files, never library data or backup files.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L*86400000 }?.forEach { it.delete() }
        val file = File(dir, "$token.arcanum")
        write(records, token, file.outputStream(), progress, library)
        progress(TransferProgress("Verifying package"))
        java.util.zip.ZipFile(file).use { zip ->
            val metadata = zip.getEntry("manifest.json") ?: error("Missing package manifest")
            val savedManifest = JSONObject(zip.getInputStream(metadata).bufferedReader().use { it.readText() })
            val assets = mutableSetOf<String>()
            val items = savedManifest.getJSONArray("items")
            for (i in 0 until items.length()) {
                val list = items.getJSONObject(i).optJSONArray("assets") ?: JSONArray()
                for (j in 0 until list.length()) {
                    val descriptor = list.getJSONObject(j)
                    val path = descriptor.getString("path")
                    require(assets.add(path)) { "Duplicate asset descriptor: $path" }
                    val entry = zip.getEntry(path) ?: error("Missing image file: $path")
                    require(entry.size in 1..2_000_000) { "Invalid image size: $path" }
                }
            }
            require(zip.entries().asSequence().filter { it.name.startsWith("assets/") }.count() == assets.size) {
                "Package asset count mismatch"
            }
        }
        progress(TransferProgress("Saving pending transfer receipt"))
        val pending = File(context.filesDir, "arcanum-$token.json")
        pending.outputStream().use { out -> out.write(records.toString().toByteArray()); out.flush(); out.fd.sync() }
        val callback = PendingIntent.getBroadcast(context, 0, Intent(context, ArcanumSaveReceiver::class.java).setData(android.net.Uri.parse("arcanum-save:$token")), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).setPackage(TARGET).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("org.arcanum.extra.TRANSFER_ID", token).putExtra("org.arcanum.extra.SAVE_ACK", callback).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                    ArcanumSharing.preferences(context).edit().putString("state", "Verifying").putString("status", "Verifying Archives save acknowledgment against the exported snapshot…").apply()
                    val current = LibraryTransfer.recordsForIds(library, (0 until sent.length()).map { sent.getJSONObject(it).getInt("id") }.toSet())
                    val unchanged = (0 until current.length()).associate { val r = current.getJSONObject(it); r.getInt("id") to r.toString() }
                    for (i in 0 until sent.length()) {
                        val r = sent.getJSONObject(i)
                        if (unchanged[r.getInt("id")] == r.toString()) archived += db.update("entries", ContentValues().apply { put("needs_export", 0); put("archived", 1) }, "id=? AND needs_export=1 AND archived=0 AND junk=0", arrayOf(r.getInt("id").toString()))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction(); library.close() }
                pending.delete()
                ArcanumSharing.preferences(context).edit().putBoolean("pending", false).putString("state", "Completed").putString("status", "Archives confirmed the save. $archived unchanged entries archived; entries edited during transfer stay queued. Review notes and highlights are preserved.").commit()
            } catch (e: Exception) {
                ArcanumSharing.preferences(context).edit().putBoolean("pending", false).putString("state", "Failed").putString("status", "Could not apply save acknowledgment. Entries were kept safe: ${e.message}").commit()
            } finally { completion.finish() }
        }
    }
}
