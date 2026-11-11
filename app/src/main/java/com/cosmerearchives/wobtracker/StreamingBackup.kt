package com.cosmerearchives.wobtracker

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Version 2 backups store one JSON object per line. The manifest is written last so
 * large archives never require an in-memory JSON array or a giant byte array.
 * SHA-256 covers the exact uncompressed records.ndjson bytes.
 */
object StreamingBackup {
    private val columns = arrayOf("id","event","date","transcript","source_tags","my_tags","my_notes","needs_review","needs_export","cosmere","extended_works","event_id","entry_date","modified_date","original_url","archived","junk","source_note","writing_excuses")
    private val numbers = setOf("id","needs_review","needs_export","cosmere","extended_works","event_id","archived","junk","writing_excuses")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun record(db: SQLiteDatabase, c: android.database.Cursor): JSONObject {
        val item = JSONObject()
        columns.forEachIndexed { index, key ->
            if (key in numbers) item.put(key, c.getInt(index)) else item.put(key, c.getString(index))
        }
        val highlights = JSONArray()
        db.rawQuery("SELECT start_offset,end_offset,quote FROM highlights WHERE entry_id=? ORDER BY start_offset,end_offset",
            arrayOf(c.getInt(0).toString())).use { h ->
            while (h.moveToNext()) highlights.put(
                JSONObject().put("start", h.getInt(0)).put("end", h.getInt(1)).put("quote", h.getString(2))
            )
        }
        item.put("highlights", highlights)
        db.rawQuery("SELECT mime_type,image_data FROM wob_images WHERE entry_id=?", arrayOf(c.getInt(0).toString())).use { image ->
            if (image.moveToFirst()) {
                item.put("image", JSONObject()
                    .put("mimeType", image.getString(0))
                    .put("base64", android.util.Base64.encodeToString(image.getBlob(1), android.util.Base64.NO_WRAP)))
            }
        }
        return item
    }

    fun backup(library: WobLibrary, output: OutputStream, progress: (Int, Int) -> Unit, stage: (TransferProgress) -> Unit): Int {
        val db = library.readableDatabase
        val total = library.savedEntryCount()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        stage(TransferProgress("Writing entries to backup"))
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("records.ndjson"))
            db.rawQuery("SELECT ${columns.joinToString(",")} FROM entries ORDER BY id", null).use { c ->
                progress(0, total)
                while (c.moveToNext()) {
                    val line = (record(db, c).toString() + "\n").toByteArray(Charsets.UTF_8)
                    zip.write(line)
                    digest.update(line)
                    count++
                    if (count % 10 == 0 || count == total) progress(count, total)
                }
            }
            zip.closeEntry()
            val manifest = JSONObject().put("format", "wob-tracker-backup")
                .put("version", 2).put("count", count).put("sha256", hex(digest.digest()))
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return count
    }

    /**
     * Incrementally decode NDJSON records, bounded to 8 MB per record.
     * Strict manifest verification happens before restore ever opens a transaction.
     */
    private fun readVerified(input: InputStream, progress: (TransferProgress) -> Unit,
                             onRecord: (JSONObject) -> Unit): Int {
        var count = 0
        var manifest: JSONObject? = null
        val digest = MessageDigest.getInstance("SHA-256")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            require(zip.nextEntry?.name == "records.ndjson") { "Not a version 2 backup" }
            val pending = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var totalBytes = 0L
            while (true) {
                val len = zip.read(buf)
                if (len == -1) break
                totalBytes += len
                require(totalBytes <= 2_000_000_000L) { "Backup exceeds safety limit" }
                digest.update(buf, 0, len)
                for (i in 0 until len) {
                    if (buf[i] == 10.toByte()) {
                        val bytes = pending.toByteArray()
                        require(bytes.isNotEmpty()) { "Blank backup record" }
                        val obj = JSONObject(String(bytes, Charsets.UTF_8))
                        require(obj.optInt("id") > 0 && obj.has("highlights")) { "Invalid backup record" }
                        onRecord(obj)
                        count++
                        pending.reset()
                    } else {
                        require(pending.size() < 8_000_000) { "Record exceeds 8 MB" }
                        pending.write(buf[i].toInt())
                    }
                }
                if (count % 50 == 0) progress(TransferProgress("Checking backup records", count.toLong(), 0, "entries"))
            }
            require(pending.size() == 0) { "Incomplete last backup record" }
            zip.closeEntry()
            require(zip.nextEntry?.name == "manifest.json") { "Missing backup manifest" }
            val content = ByteArrayOutputStream()
            while (true) {
                val n = zip.read(buf)
                if (n == -1) break
                require(content.size() + n < 65536) { "Manifest too large" }
                content.write(buf, 0, n)
            }
            manifest = JSONObject(content.toString("UTF-8"))
            zip.closeEntry()
            require(zip.nextEntry == null) { "Unexpected extra backup content" }
        }
        val m = requireNotNull(manifest)
        require(m.optString("format") == "wob-tracker-backup" && m.optInt("version") == 2) { "Unsupported backup" }
        require(m.optInt("count") == count) { "Backup count does not match manifest" }
        require(m.optString("sha256") == hex(digest.digest())) { "Backup checksum mismatch" }
        return count
    }

    fun verify(input: InputStream, progress: (TransferProgress) -> Unit = {}): Int =
        readVerified(input, progress) { /* validation only, never writes */ }

    fun restoreMerge(library: WobLibrary, input: InputStream,
                     progress: (Int, Int) -> Unit, stage: (TransferProgress) -> Unit): Pair<Int, Int> {
        // Stage and validate the entire ZIP before the first database write.
        val temp = File.createTempFile("wob-restore-", ".zip", library.backupCacheDir)
        try {
            temp.outputStream().use { destination ->
                val buffer = ByteArray(65536)
                var size = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= 1_000_000_000L) { "Backup ZIP too large" }
                    destination.write(buffer, 0, n)
                }
            }
            val count = temp.inputStream().use { verify(it, stage) }
            val db = library.writableDatabase
            var added = 0
            var skipped = 0
            db.beginTransaction()
            try {
                temp.inputStream().use { source ->
                    readVerified(source, stage) { obj ->
                        val id = obj.getInt("id")
                        val exists = db.rawQuery("SELECT 1 FROM entries WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst() }
                        if (exists) skipped++ else {
                            val values = ContentValues()
                            for (col in columns) {
                                if (col in numbers) values.put(col, obj.optInt(col, 0))
                                else values.put(col, obj.optString(col, ""))
                            }
                            db.insertOrThrow("entries", null, values)
                            added++
                        }
                        val image = obj.optJSONObject("image")
                        if (image != null) {
                            val bytes = android.util.Base64.decode(image.getString("base64"), android.util.Base64.DEFAULT)
                            require(image.getString("mimeType") == "image/jpeg" && bytes.size in 1..2_000_000) { "Invalid attached image in backup" }
                            // Safe merge never overwrites an image already present.
                            db.insertWithOnConflict("wob_images", null, ContentValues().apply {
                                put("entry_id", id)
                                put("mime_type", "image/jpeg")
                                put("image_data", bytes)
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        val marks = obj.getJSONArray("highlights")
                        for (i in 0 until marks.length()) {
                            val mark = marks.getJSONObject(i)
                            db.insertWithOnConflict("highlights", null, ContentValues().apply {
                                put("entry_id", id)
                                put("start_offset", mark.getInt("start"))
                                put("end_offset", mark.getInt("end"))
                                put("quote", mark.getString("quote"))
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        if ((added + skipped) % 10 == 0) progress(added + skipped, count)
                    }
                }
                // Report progress before marking the transaction successful.
                // If the operation is cancelled or the callback fails, rollback
                // must leave previously saved entries untouched.
                progress(count, count)
                stage(TransferProgress("Committing restore"))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return added to skipped
        } finally {
            temp.delete()
        }
    }
}
