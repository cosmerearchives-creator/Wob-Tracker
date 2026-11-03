package com.cosmerearchives.wobtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SavedWob(val id: Int, val event: String, val date: String, val transcript: String, val sourceTags: String, val myTags: String, val myNotes: String, val needsReview: Boolean = true, val needsExport: Boolean = false, val cosmere: Boolean = false, val extendedWorks: Boolean = false, val eventId: Int = 0, val entryDate: String = "", val modifiedDate: String = "", val originalUrl: String = "", val archived: Boolean = false, val junk: Boolean = false, val sourceNote: String = "")

class WobLibrary(context: Context) : SQLiteOpenHelper(context, "wob_library.db", null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, event TEXT NOT NULL, date TEXT NOT NULL, transcript TEXT NOT NULL, source_tags TEXT NOT NULL, my_tags TEXT NOT NULL DEFAULT '', my_notes TEXT NOT NULL DEFAULT '', needs_review INTEGER NOT NULL DEFAULT 1, needs_export INTEGER NOT NULL DEFAULT 0, cosmere INTEGER NOT NULL DEFAULT 0, extended_works INTEGER NOT NULL DEFAULT 0, event_id INTEGER NOT NULL DEFAULT 0, entry_date TEXT NOT NULL DEFAULT '', modified_date TEXT NOT NULL DEFAULT '', original_url TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, junk INTEGER NOT NULL DEFAULT 0, source_note TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_id INTEGER NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, quote TEXT NOT NULL, UNIQUE(entry_id,start_offset,end_offset))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN needs_export INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN cosmere INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN extended_works INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("UPDATE entries SET needs_review=1")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE entries ADD COLUMN event_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN entry_date TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN modified_date TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN original_url TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE entries ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 7) db.execSQL("ALTER TABLE entries ADD COLUMN junk INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 8) db.execSQL("ALTER TABLE entries ADD COLUMN source_note TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_id INTEGER NOT NULL, start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, quote TEXT NOT NULL, UNIQUE(entry_id,start_offset,end_offset))")
    }

    fun entries(query: String = "", filterBy: String = "All", category: String = "All works", archiveView: String = "Active"): List<SavedWob> {
        val result = mutableListOf<SavedWob>()
        val filter = "%${query.trim()}%"
        val extra = when (filterBy) {
            "Needs review" -> " AND needs_review=1 AND junk=0"
            "Needs export" -> " AND needs_export=1 AND junk=0"
            "Both" -> " AND needs_review=1 AND needs_export=1 AND junk=0"
            else -> ""
        }
        val categoryClause = when (category) {
            "Cosmere" -> " AND cosmere=1"
            "Extended Works" -> " AND extended_works=1"
            else -> ""
        }
        val archiveClause = when (archiveView) {
            "Archived" -> " AND archived=1 AND junk=0"
            "Junk" -> " AND junk=1"
            "All" -> ""
            else -> " AND archived=0 AND junk=0"
        }
        readableDatabase.rawQuery(
            "SELECT id,event,date,transcript,source_tags,my_tags,my_notes,needs_review,needs_export,cosmere,extended_works,event_id,entry_date,modified_date,original_url,archived,junk,source_note FROM entries WHERE (event LIKE ? OR transcript LIKE ? OR my_tags LIKE ? OR my_notes LIKE ?)$extra$categoryClause$archiveClause ORDER BY id DESC",
            arrayOf(filter, filter, filter, filter)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(SavedWob(cursor.getInt(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5),cursor.getString(6),cursor.getInt(7) != 0,cursor.getInt(8) != 0,cursor.getInt(9) != 0,cursor.getInt(10) != 0,cursor.getInt(11),cursor.getString(12),cursor.getString(13),cursor.getString(14),cursor.getInt(15) != 0,cursor.getInt(16) != 0,cursor.getString(17)))
            }
        }
        return result
    }

    fun saveSource(entry: SavedWob) {
        val values = ContentValues().apply {
            put("id", entry.id); put("event", entry.event); put("date", entry.date)
            put("transcript", entry.transcript); put("source_tags", entry.sourceTags)
            put("needs_review", 1)
            put("event_id", entry.eventId)
            put("entry_date", entry.entryDate)
            put("modified_date", entry.modifiedDate)
            put("original_url", entry.originalUrl)
            put("source_note", entry.sourceNote)
        }
        // CONFLICT_IGNORE guarantees a re-import never replaces personal edits.
        writableDatabase.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    data class Highlight(val id: Long, val start: Int, val end: Int, val quote: String)

    fun highlights(entryId: Int): List<Highlight> {
        val result = mutableListOf<Highlight>()
        readableDatabase.rawQuery("SELECT id,start_offset,end_offset,quote FROM highlights WHERE entry_id=? ORDER BY start_offset", arrayOf(entryId.toString())).use { cursor ->
            while (cursor.moveToNext()) result.add(Highlight(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2), cursor.getString(3)))
        }
        return result
    }

    fun addHighlight(entryId: Int, start: Int, end: Int, quote: String): Boolean {
        if (start < 0 || end <= start || quote.isEmpty()) return false
        return writableDatabase.insertWithOnConflict("highlights", null, ContentValues().apply {
            put("entry_id", entryId); put("start_offset", start); put("end_offset", end); put("quote", quote)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun deleteHighlight(id: Long): Boolean =
        writableDatabase.delete("highlights", "id=?", arrayOf(id.toString())) == 1

    /** Change only the original Arcanum footnote; keep personal data and review flags intact. */
    fun saveSourceNote(id: Int, note: String): Boolean =
        writableDatabase.update("entries", ContentValues().apply { put("source_note", note) },
            "id=?", arrayOf(id.toString())) == 1

    fun saveOriginalUrl(id: Int, url: String): Boolean {
        val normalized = url.trim()
        if (normalized.isNotEmpty() && !(normalized.startsWith("https://") || normalized.startsWith("http://"))) return false
        return writableDatabase.update("entries", ContentValues().apply { put("original_url", normalized) }, "id=?", arrayOf(id.toString())) == 1
    }

    /** Personal tags from all saved entries, including archived entries. */
    fun knownPersonalTags(): List<String> {
        val unique = linkedMapOf<String, String>()
        readableDatabase.rawQuery("SELECT my_tags FROM entries WHERE my_tags != ''", null).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0).split(',').forEach { raw ->
                    val tag = raw.trim()
                    if (tag.isNotEmpty()) unique.putIfAbsent(tag.lowercase(java.util.Locale.ROOT), tag)
                }
            }
        }
        return unique.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Most frequently assigned personal tags (counted once per WoB, case-insensitively). */
    fun frequentPersonalTags(limit: Int = 5): List<String> {
        val counts = mutableMapOf<String, Int>()
        val spellings = mutableMapOf<String, String>()
        readableDatabase.rawQuery("SELECT my_tags FROM entries WHERE my_tags != ''", null).use { cursor ->
            while (cursor.moveToNext()) {
                val uniqueForEntry = mutableSetOf<String>()
                cursor.getString(0).split(',').forEach { raw ->
                    val tag = raw.trim()
                    val key = tag.lowercase(java.util.Locale.ROOT)
                    if (key.isNotEmpty() && uniqueForEntry.add(key)) {
                        counts[key] = (counts[key] ?: 0) + 1
                        spellings.putIfAbsent(key, tag)
                    }
                }
            }
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        ).take(limit.coerceIn(0, 5)).mapNotNull { spellings[it.key] }
    }

    fun savePersonal(id: Int, tags: String, notes: String): Boolean {
        val values = ContentValues().apply { put("my_tags", tags); put("my_notes", notes) }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    fun saveFlags(id: Int, needsReview: Boolean, needsExport: Boolean): Boolean {
        val values = ContentValues().apply {
            put("needs_review", if (needsReview) 1 else 0)
            put("needs_export", if (needsExport) 1 else 0)
            if (needsExport) put("junk", 0)
        }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    fun saveCategories(id: Int, cosmere: Boolean, extendedWorks: Boolean): Boolean {
        val values = ContentValues().apply {
            put("cosmere", if (cosmere) 1 else 0)
            put("extended_works", if (extendedWorks) 1 else 0)
        }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    /** Junk remains local and recoverable, but cannot be exported or reviewed. */
    fun setJunk(id: Int, junk: Boolean): Boolean {
        val values = ContentValues().apply {
            put("junk", if (junk) 1 else 0)
            if (junk) {
                put("needs_review", 0)
                put("needs_export", 0)
            } else {
                put("needs_review", 1)
            }
        }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    /** Atomically save annotations and finish review, so a failed save never advances the queue. */
    fun completeReview(id: Int, tags: String, notes: String, needsExport: Boolean, cosmere: Boolean, extendedWorks: Boolean, junk: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("my_tags", tags)
            put("my_notes", notes)
            put("needs_review", 0)
            put("needs_export", if (needsExport && !junk) 1 else 0)
            put("junk", if (junk) 1 else 0)
            put("cosmere", if (cosmere) 1 else 0)
            put("extended_works", if (extendedWorks) 1 else 0)
        }
        return writableDatabase.update("entries", values, "id=? AND needs_review=1", arrayOf(id.toString())) == 1
    }

    fun saveReviewDraft(id: Int, tags: String, notes: String, needsExport: Boolean, cosmere: Boolean, extendedWorks: Boolean, junk: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("my_tags", tags)
            put("my_notes", notes)
            put("needs_export", if (needsExport && !junk) 1 else 0)
            put("junk", if (junk) 1 else 0)
            if (junk) put("needs_review", 0)
            put("cosmere", if (cosmere) 1 else 0)
            put("extended_works", if (extendedWorks) 1 else 0)
        }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    fun setArchived(id: Int, archived: Boolean): Boolean =
        writableDatabase.update("entries", ContentValues().apply { put("archived", if (archived) 1 else 0) }, "id=?", arrayOf(id.toString())) == 1

    fun savedEntryCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    fun hasEntry(id: Int): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM entries WHERE id=? LIMIT 1", arrayOf(id.toString())).use { it.moveToFirst() }

    /** Small bounded sample, fetched only when explicitly requested. Skips locally stored IDs before HTTP. */
    fun importSamples(): Int {
        var imported = 0
        for (id in listOf(1356,1290,1393)) {
            if (hasEntry(id)) continue
            val connection = URL("https://wob.coppermind.net/api/entry/$id/").openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WoB-Tracker/0.1 (bounded research sample)")
            try {
                if (connection.responseCode != 200) error("API HTTP ${connection.responseCode}")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val lines = json.getJSONArray("lines")
                val transcript = (0 until lines.length()).joinToString("\n\n") { i ->
                    val line = lines.getJSONObject(i)
                    "${line.optString("speaker")}: ${line.optString("text")}"
                }
                val tags = json.optJSONArray("tags")
                val sourceTags = if (tags == null) "" else (0 until tags.length()).joinToString(", ") { tags.getString(it) }
                saveSource(SavedWob(id,json.optString("event_name"),json.optString("event_date"),transcript,sourceTags,"","",eventId=json.optInt("event"),entryDate=json.optString("date").takeUnless { it == "null" }.orEmpty(),modifiedDate=json.optString("modified_date"),sourceNote=json.optString("note").takeUnless { it == "null" }.orEmpty()))
                imported++
            } finally { connection.disconnect() }
        }
        return imported
    }
}
