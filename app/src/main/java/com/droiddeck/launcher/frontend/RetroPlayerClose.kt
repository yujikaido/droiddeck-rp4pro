package com.droiddeck.launcher.frontend

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Closes the emulator a Retro game ran in once the player is back in DroidDeck (any of its screens: Home goes to
 * the Steam screen while a session runs). Left open, it kept the game paused in the background, held its memory
 * while Steam games ran, and came back to the front whenever DroidDeck closed (an update). The emulator has written
 * its save when it went to the background (Android may close a background app at any time, so emulators save on
 * pause); [DELAY_MS] is margin on top. Back in the emulator before then, it stays open. The package is kept in prefs
 * "retro_player" so a DroidDeck restart still closes it.
 */
object RetroPlayerClose {
    private const val TAG = "Retro"
    private const val PREFS = "retro_player"
    private const val PLAYING = "playing"
    const val DELAY_MS = 5000L

    private val ui = Handler(Looper.getMainLooper())
    private var resumed = 0

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A Retro game just started in [pkg]. */
    fun started(context: Context, pkg: String) {
        prefs(context).edit().putString(PLAYING, pkg).apply()
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
                ui.removeCallbacksAndMessages(this@RetroPlayerClose)
                ui.postAtTime({ closeIfBack(app) }, this@RetroPlayerClose, android.os.SystemClock.uptimeMillis() + DELAY_MS)
            }

            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun closeIfBack(context: Context) {
        if (resumed == 0) return
        val pkg = prefs(context).getString(PLAYING, null) ?: return
        prefs(context).edit().remove(PLAYING).apply()
        runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
            .onSuccess { Log.i(TAG, "back in DroidDeck: closed $pkg") }
            .onFailure { Log.w(TAG, "could not close $pkg", it) }
    }
}
