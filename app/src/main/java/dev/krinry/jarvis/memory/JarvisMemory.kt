package dev.krinry.jarvis.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent on-device memory for task outcomes and stable user/project facts. */
class JarvisMemory(context: Context) : SQLiteOpenHelper(context, "jarvis_memory.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, command TEXT NOT NULL, outcome TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX memories_created_at_idx ON memories(created_at DESC)")
        createFactsTable(db)
    }

    private fun createFactsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_facts (fact_key TEXT PRIMARY KEY, fact_value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFactsTable(db)
    }

    @Synchronized
    fun rememberFact(key: String, value: String) {
        val safeKey = key.trim().take(80)
        val safeValue = value.trim().take(1000)
        if (safeKey.isEmpty() || safeValue.isEmpty()) return
        val values = ContentValues().apply {
            put("fact_key", safeKey)
            put("fact_value", safeValue)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("user_facts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun loadFacts(limit: Int = 20): List<String> {
        val facts = mutableListOf<String>()
        readableDatabase.query("user_facts", arrayOf("fact_key", "fact_value"), null, null, null, null, "updated_at DESC", limit.coerceIn(1, 50).toString()).use { cursor ->
            while (cursor.moveToNext()) facts += "- ${cursor.getString(0)}: ${cursor.getString(1)}"
        }
        return facts
    }

    @Synchronized
    fun saveCommand(command: String) {
        val normalized = command.trim().take(1000)
        if (normalized.isEmpty()) return
        val values = ContentValues().apply {
            put("command", normalized)
            put("outcome", "")
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("memories", null, values)
    }

    @Synchronized
    fun finishLatest(command: String, outcome: String) {
        val normalized = command.trim().take(1000)
        if (normalized.isEmpty()) return
        writableDatabase.execSQL("UPDATE memories SET outcome=? WHERE id=(SELECT id FROM memories WHERE command=? ORDER BY id DESC LIMIT 1)", arrayOf(outcome.take(1000), normalized))
    }

    @Synchronized
    fun search(query: String, limit: Int = 5): List<String> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.length >= 3 }.distinct().take(6)
        if (terms.isEmpty()) return emptyList()
        val clauses = terms.joinToString(" OR ") { "(command LIKE ? OR outcome LIKE ?)" }
        val args = terms.flatMap { listOf("%$it%", "%$it%") }.toTypedArray()
        val results = mutableListOf<String>()
        readableDatabase.query("memories", arrayOf("command", "outcome"), clauses, args, null, null, "created_at DESC", limit.coerceIn(1, 10).toString()).use { cursor ->
            while (cursor.moveToNext()) {
                val command = cursor.getString(0)
                val outcome = cursor.getString(1)
                results += if (outcome.isNullOrBlank()) "- Past task: $command" else "- Past task: $command; result: $outcome"
            }
        }
        return results
    }
}
