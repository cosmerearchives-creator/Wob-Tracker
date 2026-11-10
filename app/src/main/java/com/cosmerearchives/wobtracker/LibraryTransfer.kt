package com.cosmerearchives.wobtracker

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, portable ZIP containing JSON, not the live SQLite WAL database. */
object LibraryTransfer {
    private val columns = arrayOf("id","event","date","transcript","source_tags","my_tags","my_notes","needs_review","needs_export","cosmere","extended_works","event_id","entry_date","modified_date","original_url","archived","junk","source_note")
    private val personal = setOf("my_tags","my_notes","needs_review","needs_export","cosmere","extended_works","original_url","archived","junk")
    private val numbers = setOf("id","needs_review","needs_export","cosmere","extended_works","event_id","archived","junk")
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun records(db: SQLiteDatabase, ids: Set<Int>? = null, progress: (Int, Int) -> Unit = { _, _ -> }): JSONArray {
        val result = JSONArray()
        if (ids != null && ids.isEmpty()) { progress(0, 0); return result }
        val selected = ids?.let { " WHERE id IN (${it.joinToString(",")})" } ?: ""
        db.rawQuery("SELECT ${columns.joinToString(",")} FROM entries$selected ORDER BY id", null).use { c ->
            val total = ids?.size ?: c.count
            var completed = 0
            progress(0, total)
            while (c.moveToNext()) {
                if (ids != null && c.getInt(0) !in ids) continue
                val obj = JSONObject()
                columns.forEachIndexed { n, col ->
                    if (numbers.contains(col)) obj.put(col,c.getInt(n))
                    else obj.put(col,c.getString(n))
                }
                val marks = JSONArray()
                db.rawQuery("SELECT start_offset,end_offset,quote FROM highlights WHERE entry_id=? ORDER BY start_offset,end_offset", arrayOf(c.getInt(0).toString())).use { h ->
                    while (h.moveToNext()) marks.put(JSONObject().put("start",h.getInt(0)).put("end",h.getInt(1)).put("quote",h.getString(2)))
                }
                obj.put("highlights", marks)
                result.put(obj)
                completed++
                if (completed % 10 == 0 || completed == total) progress(completed, total)
            }
        }
        return result
    }
    /** Version 2 uses streaming JSON lines; v1 backups remain restorable. */
    fun backup(library: WobLibrary, output: OutputStream, progress: (Int, Int) -> Unit = { _, _ -> }, stage: (TransferProgress) -> Unit = {}): Int =
        StreamingBackup.backup(library, output, progress, stage)

    /** Read the first ZIP entry name without consuming the stream for the real parser. */
    private fun backupVersionStream(input: InputStream): Pair<java.io.BufferedInputStream, Boolean> {
        val buffered = java.io.BufferedInputStream(input)
        buffered.mark(65536)
        val nonClosing = object : java.io.FilterInputStream(buffered) {
            override fun close() {}
        }
        val firstEntry = ZipInputStream(nonClosing).use { it.nextEntry?.name }
        buffered.reset()
        require(firstEntry == "records.ndjson" || firstEntry == "manifest.json") { "Unrecognized backup layout" }
        return buffered to (firstEntry == "records.ndjson")
    }

    private fun validated(input: InputStream, progress: (TransferProgress) -> Unit = {}): JSONArray {
        var expanded = 0L
        progress(TransferProgress("Reading backup"))
        var manifest: JSONObject? = null
        var data: ByteArray? = null
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(entry.size <= 100_000_000) { "Backup too large" }
                if (entry.name == "manifest.json" || entry.name == "library.json") {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        require(out.size() + n <= 100_000_000) { "Backup too large" }
                        out.write(buf,0,n)
                        expanded += n; progress(TransferProgress("Expanding backup", expanded, 0, "bytes"))
                    }
                    if (entry.name == "manifest.json") manifest = JSONObject(out.toString("UTF-8"))
                    else data = out.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        progress(TransferProgress("Verifying backup checksums"))
        val m = requireNotNull(manifest) { "Missing manifest" }
        val b = requireNotNull(data) { "Missing library data" }
        require(m.optString("format") == "wob-tracker-backup" && m.optInt("version") == 1) { "Unsupported backup format" }
        require(digest(b) == m.optString("sha256")) { "Integrity check failed" }
        val obj = JSONObject(String(b,Charsets.UTF_8))
        require(obj.optString("format") == "wob-tracker-backup" && obj.optInt("version") == 1) { "Unsupported data format" }
        val arr = obj.getJSONArray("entries")
        require(arr.length() == m.getInt("count")) { "Entry count mismatch" }
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            for (col in columns.filterNot { it == "junk" || it == "source_note" }) require(item.has(col)) { "Missing field $col" }
            require(item.getInt("id") > 0 && item.has("highlights")) { "Invalid record" }
        }
        return arr
    }
    fun verify(input: InputStream, progress: (TransferProgress) -> Unit = {}): Int {
        val (stream, v2) = backupVersionStream(input)
        return if (v2) StreamingBackup.verify(stream, progress) else validated(stream, progress).length()
    }
    /** Merge adds absent records and their highlights, leaving ALL existing records untouched. */
    fun restoreMerge(library: WobLibrary, input: InputStream, progress: (Int, Int) -> Unit = { _, _ -> }, stage: (TransferProgress) -> Unit = {}): Pair<Int,Int> {
        val (stream, v2) = backupVersionStream(input)
        if (v2) return StreamingBackup.restoreMerge(library, stream, progress, stage)
        val arr = validated(stream, stage)
        val db = library.writableDatabase
        var added = 0
        var skipped = 0
        progress(0, arr.length())
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("id")
                val exists = db.rawQuery("SELECT 1 FROM entries WHERE id=?",arrayOf(id.toString())).use { it.moveToFirst() }
                if (exists) skipped++
                if (!exists) {
                val values = ContentValues()
                columns.forEach { col ->
                    if (numbers.contains(col)) values.put(col,if (col == "junk") obj.optInt(col,0) else obj.getInt(col))
                    else values.put(col,if (col == "source_note") obj.optString(col,"") else obj.getString(col))
                }
                require(db.insertOrThrow("entries",null,values) != -1L)
                added++
                }
                val marks = obj.getJSONArray("highlights")
                for (j in 0 until marks.length()) {
                    val mark = marks.getJSONObject(j)
                    db.insertWithOnConflict("highlights",null,ContentValues().apply {
                        put("entry_id",id); put("start_offset",mark.getInt("start")); put("end_offset",mark.getInt("end")); put("quote",mark.getString("quote"))
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                if ((i + 1) % 10 == 0 || i + 1 == arr.length()) progress(i + 1, arr.length())
            }
            stage(TransferProgress("Committing restore"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added to skipped
    }
    internal fun recordsForIds(library: WobLibrary, ids: Set<Int>): JSONArray = records(library.readableDatabase, ids)
    fun exportRecords(library: WobLibrary, progress: (TransferProgress) -> Unit = {}): JSONArray {
        progress(TransferProgress("Reading queued WoBs"))
        val db = library.readableDatabase
        val ids = mutableSetOf<Int>()
        db.rawQuery("SELECT id FROM entries WHERE needs_export=1 AND archived=0 AND junk=0", null).use { c -> while (c.moveToNext()) ids.add(c.getInt(0)) }
        require(ids.size in 1..1000) { "Mark 1–1000 entries for each transfer." }
        return records(db, ids) { done, total -> progress(TransferProgress("Reading queued WoBs", done.toLong(), total.toLong())) }
    }
    fun exportPackage(library: WobLibrary, output: OutputStream, progress: (TransferProgress) -> Unit = {}): Int {
        val records = exportRecords(library, progress)
        ArcanumSharing.write(records, java.util.UUID.randomUUID().toString(), output, progress)
        return records.length()
    }
    /** Export is a distinct format; creating it does not change export or archive status. */
    fun exportArchives(library: WobLibrary, output: OutputStream): Int {
        val db = library.readableDatabase
        val ids = mutableSetOf<Int>()
        db.rawQuery("SELECT id FROM entries WHERE needs_export=1 AND archived=0 AND junk=0",null).use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        val payload = JSONObject().put("format","archives-of-arcanum-wob-transfer").put("version",1)
            .put("origin","WoB Tracker").put("entries",records(db,ids))
        output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
        output.flush()
        return ids.size
    }
}
