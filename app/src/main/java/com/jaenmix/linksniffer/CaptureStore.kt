package com.jaenmix.linksniffer

import android.content.Context

object CaptureStore {
    private const val PREF = "capture_store"
    private const val KEY = "items"

    fun add(context: Context, value: String) {
        val items = read(context).toMutableList()
        if (value !in items) {
            items.add(0, value)
            while (items.size > 200) items.removeAt(items.lastIndex)
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, items.joinToString("\n")).apply()
        }
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "")!!
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
