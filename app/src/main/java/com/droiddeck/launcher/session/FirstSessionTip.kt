package com.droiddeck.launcher.session

import android.content.Context

/** The one-time "Back opens the menu" toast in a player's first session. */
object FirstSessionTip {
    private fun prefs(context: Context) = context.getSharedPreferences("first_session_tip", Context.MODE_PRIVATE)

    /** True the first time it is asked, and marks the tip as shown. */
    fun due(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean("shown", false)) return false
        p.edit().putBoolean("shown", true).apply()
        return true
    }
}
