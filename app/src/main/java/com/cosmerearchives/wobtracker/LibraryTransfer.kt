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
    private val columns = arrayOf("id","event","date","transcript","source_tags","my_tags","my_notes","needs_review","needs_export","cosmere","extended_works","event_id","entry_date","modified_date","original_url","archived")
    private val personal = setOf("my_tags","my_notes","needs_review","needs_export","cosmere","extended_works","original_url","archived")
    private val numbers = setOf("id","needs_review","needs_export","cosmere","extended_works","event_id","archived")
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun records(db: SQLiteDatabase, ids: Set<Int>? = null): JSONArray {
        val result = JSONArray()
        db.rawQuery("SELECT ${columns.joinToString(",")} FROM entries ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                if (ids != null && c.getInt(0) !in ids) continue
                val obj = JSONObject()
                columns.forEachIndexed { n, col ->
                    if (numbers.contains(col)) obj.put(col,c.getInt(n))
                    else obj.put(col,c.getString(n))
                }
                val marks = JSONArray()
                db.rawQuery("SELECT start_offset,end_offset,quote FROM highlights WHERE entry_id=?", arrayOf(c.getInt(0).toString())).use { h ->
                    while (h.moveToNext()) marks.put(JSONObject().put("start",h.getInt(0)).put("end",h.getInt(1)).put("quote",h.getString(2)))
                }
                obj.put("highlights", marks)
                result.put(obj)
            }
        }
        return result
    }
    fun backup(library: WobLibrary, output: OutputStream): Int {
        val entries = records(library.readableDatabase)
        val bytes = JSONObject().put("format","wob-tracker-backup").put("version",1).put("entries",entries).toString().toByteArray(Charsets.UTF_8)
        val meta = JSONObject().put("format","wob-tracker-backup").put("version",1).put("sha256",digest(bytes)).put("count",entries.length())
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(meta.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("library.json")); zip.write(bytes); zip.closeEntry()
        }
        return entries.length()
    }
    private fun validated(input: InputStream): JSONArray {
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
                    }
                    if (entry.name == "manifest.json") manifest = JSONObject(out.toString("UTF-8"))
                    else data = out.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
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
            for (col in columns) require(item.has(col)) { "Missing field $col" }
            require(item.getInt("id") > 0 && item.has("highlights")) { "Invalid record" }
        }
        return arr
    }
    fun verify(input: InputStream): Int = validated(input).length()
    /** Merge adds absent records and their highlights, leaving ALL existing records untouched. */
    fun restoreMerge(library: WobLibrary, input: InputStream): Pair<Int,Int> {
        val arr = validated(input)
        val db = library.writableDatabase
        var added = 0
        var skipped = 0
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
                    if (numbers.contains(col)) values.put(col,obj.getInt(col))
                    else values.put(col,obj.getString(col))
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
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added to skipped
    }
    /** Export is a distinct format; creating it does not change export or archive status. */
    fun exportArchives(library: WobLibrary, output: OutputStream): Int {
        val db = library.readableDatabase
        val ids = mutableSetOf<Int>()
        db.rawQuery("SELECT id FROM entries WHERE needs_export=1 AND archived=0",null).use { c ->
            while (c.moveToNext()) ids.add(c.getInt(0))
        }
        val payload = JSONObject().put("format","archives-of-arcanum-wob-transfer").put("version",1)
            .put("origin","WoB Tracker").put("entries",records(db,ids))
        output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
        output.flush()
        return ids.size
    }
}
