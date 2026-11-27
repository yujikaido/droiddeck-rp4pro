package com.droiddeck.launcher.frontend

import android.content.Context

/** The Retro page's play counts, last-played times and favourites, by ROM path (prefs "retro_stats"). */
object RetroStats {
    private const val PREFS = "retro_stats"
    private const val FAVORITES = "favorites"

    class Stat(val count: Int, val last: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun played(context: Context, path: String) {
        val old = all(context)[path]
        prefs(context).edit().putString("p:$path", "${(old?.count ?: 0) + 1}|${System.currentTimeMillis()}").apply()
    }

    fun all(context: Context): Map<String, Stat> = prefs(context).all.mapNotNull { (k, v) ->
        if (!k.startsWith("p:") || v !is String) return@mapNotNull null
        val parts = v.split('|')
        val count = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        k.removePrefix("p:") to Stat(count, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.toMap()

    fun favorites(context: Context): Set<String> = prefs(context).getStringSet(FAVORITES, emptySet()).orEmpty().toSet()

    /** Adds or removes a favourite; true when it is one now. */
    fun toggleFavorite(context: Context, path: String): Boolean {
        val now = favorites(context).toMutableSet()
        val on = if (path in now) { now.remove(path); false } else { now.add(path); true }
        prefs(context).edit().putStringSet(FAVORITES, now).apply()
        return on
    }
}
