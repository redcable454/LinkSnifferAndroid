package com.jaenmix.linksniffer

import android.content.Context

object CaptureStore {
    private const val PREF = "capture_store"
    private const val KEY = "items_v2"
    private const val LEGACY_KEY = "items"
    private const val MAX_ITEMS = 500

    @Synchronized
    fun add(context: Context, value: String) {
        val items = readCounts(context).toMutableList()
        val index = items.indexOfFirst { it.first == value }
        if (index >= 0) {
            val old = items.removeAt(index)
            items.add(0, old.first to (old.second + 1))
        } else {
            items.add(0, value to 1)
        }
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        write(context, items)
    }

    fun read(context: Context): List<String> = readCounts(context).map { it.first }

    fun readCounts(context: Context): List<Pair<String, Int>> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isNotBlank()) {
            return raw.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.lastIndex) return@mapNotNull null
                val count = line.substring(0, tab).toIntOrNull() ?: 1
                val value = line.substring(tab + 1).trim()
                if (value.isBlank()) null else value to count.coerceAtLeast(1)
            }.toList()
        }

        // Migra silenciosamente datos de versiones anteriores.
        val legacy = prefs.getString(LEGACY_KEY, "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { it to 1 }.toList()
        if (legacy.isNotEmpty()) write(context, legacy)
        return legacy
    }

    private fun write(context: Context, items: List<Pair<String, Int>>) {
        val encoded = items.joinToString("\n") { (value, count) ->
            "$count\t${value.replace("\n", " ").replace("\t", " ")}"
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, encoded).remove(LEGACY_KEY).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
