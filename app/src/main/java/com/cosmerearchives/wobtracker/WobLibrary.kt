package com.cosmerearchives.wobtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SavedWob(val id: Int, val event: String, val date: String, val transcript: String, val sourceTags: String, val myTags: String, val myNotes: String)

class WobLibrary(context: Context) : SQLiteOpenHelper(context, "wob_library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, event TEXT NOT NULL, date TEXT NOT NULL, transcript TEXT NOT NULL, source_tags TEXT NOT NULL, my_tags TEXT NOT NULL DEFAULT '', my_notes TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun entries(query: String = ""): List<SavedWob> {
        val result = mutableListOf<SavedWob>()
        val filter = "%${query.trim()}%"
        readableDatabase.rawQuery(
            "SELECT id,event,date,transcript,source_tags,my_tags,my_notes FROM entries WHERE event LIKE ? OR transcript LIKE ? OR my_tags LIKE ? OR my_notes LIKE ? ORDER BY id DESC",
            arrayOf(filter, filter, filter, filter)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(SavedWob(cursor.getInt(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5),cursor.getString(6)))
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

    fun savePersonal(id: Int, tags: String, notes: String) {
        val values = ContentValues().apply { put("my_tags", tags); put("my_notes", notes) }
        writableDatabase.update("entries", values, "id=?", arrayOf(id.toString()))
    }

    /** Small bounded sample, fetched only when explicitly requested. */
    fun importSamples(): Int {
        var imported = 0
        for (id in listOf(1356,1290,1393)) {
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
