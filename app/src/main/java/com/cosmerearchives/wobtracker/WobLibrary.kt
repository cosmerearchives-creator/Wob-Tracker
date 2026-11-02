package com.cosmerearchives.wobtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SavedWob(val id: Int, val event: String, val date: String, val transcript: String, val sourceTags: String, val myTags: String, val myNotes: String, val needsReview: Boolean = false, val needsExport: Boolean = false, val cosmere: Boolean = false, val extendedWorks: Boolean = false)

class WobLibrary(context: Context) : SQLiteOpenHelper(context, "wob_library.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, event TEXT NOT NULL, date TEXT NOT NULL, transcript TEXT NOT NULL, source_tags TEXT NOT NULL, my_tags TEXT NOT NULL DEFAULT '', my_notes TEXT NOT NULL DEFAULT '', needs_review INTEGER NOT NULL DEFAULT 0, needs_export INTEGER NOT NULL DEFAULT 0, cosmere INTEGER NOT NULL DEFAULT 0, extended_works INTEGER NOT NULL DEFAULT 0)")
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
    }

    fun entries(query: String = "", filterBy: String = "All", category: String = "All works"): List<SavedWob> {
        val result = mutableListOf<SavedWob>()
        val filter = "%${query.trim()}%"
        val extra = when (filterBy) {
            "Needs review" -> " AND needs_review=1"
            "Needs export" -> " AND needs_export=1"
            "Both" -> " AND needs_review=1 AND needs_export=1"
            else -> ""
        }
        val categoryClause = when (category) {
            "Cosmere" -> " AND cosmere=1"
            "Extended Works" -> " AND extended_works=1"
            else -> ""
        }
        readableDatabase.rawQuery(
            "SELECT id,event,date,transcript,source_tags,my_tags,my_notes,needs_review,needs_export,cosmere,extended_works FROM entries WHERE (event LIKE ? OR transcript LIKE ? OR my_tags LIKE ? OR my_notes LIKE ?)$extra$categoryClause ORDER BY id DESC",
            arrayOf(filter, filter, filter, filter)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(SavedWob(cursor.getInt(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5),cursor.getString(6),cursor.getInt(7) != 0,cursor.getInt(8) != 0,cursor.getInt(9) != 0,cursor.getInt(10) != 0))
            }
        }
        return result
    }

    fun saveSource(entry: SavedWob) {
        val values = ContentValues().apply {
            put("id", entry.id); put("event", entry.event); put("date", entry.date)
            put("transcript", entry.transcript); put("source_tags", entry.sourceTags)
        }
        // CONFLICT_IGNORE guarantees a re-import never replaces personal edits.
        writableDatabase.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun savePersonal(id: Int, tags: String, notes: String): Boolean {
        val values = ContentValues().apply { put("my_tags", tags); put("my_notes", notes) }
        return writableDatabase.update("entries", values, "id=?", arrayOf(id.toString())) == 1
    }

    fun saveFlags(id: Int, needsReview: Boolean, needsExport: Boolean): Boolean {
        val values = ContentValues().apply {
            put("needs_review", if (needsReview) 1 else 0)
            put("needs_export", if (needsExport) 1 else 0)
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
                saveSource(SavedWob(id,json.optString("event_name"),json.optString("event_date"),transcript,sourceTags,"",""))
                imported++
            } finally { connection.disconnect() }
        }
        return imported
    }
}
