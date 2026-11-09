package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.frontend.AutoComponents

/**
 * The session's start-failure guard and its default for new titles (steam-compatibility, compat-notice kind "install")
 * need a DroidDeck-Proton. When none is installed the guest asks here, and this decides with what only the app knows:
 *  - the player declined DroidDeck-Proton earlier: nothing is installed silently; a title that closed before showing
 *    anything ("failed") gets a one-tap prompt once, the default for new titles ("new") is not worth asking about;
 *  - otherwise the existing request file (ProtonExtras.enqueue, read by the session script at the next session start)
 *    is written, unless the network is offline or metered (the same check as the automatic Proton updates): the guest
 *    asks again in the next session, and the waiting move stays in steam-compatibility's state until the tool is there.
 */
object CompatInstall {
    enum class Outcome { INSTALLED, QUEUED, ALREADY_QUEUED, PROMPT, WAIT_NETWORK, NOTHING }

    const val FAILED = "failed"
    private const val PREFS = "compat-install"
    private const val REFUSED = "refused"

    private fun tool() = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refused(context: Context, app: String): Boolean = app in (prefs(context).getStringSet(REFUSED, emptySet()) ?: emptySet())

    /** The player pressed "Not now" for this title: it is not asked about again. */
    fun refuse(context: Context, app: String) {
        val now = (prefs(context).getStringSet(REFUSED, emptySet()) ?: emptySet()) + app
        prefs(context).edit().putStringSet(REFUSED, now).apply()
    }

    fun request(context: Context, reason: String, app: String): Outcome {
        val own = tool()
        if (ProtonExtras.installedAll(context, own).isNotEmpty()) return Outcome.INSTALLED
        if (ProtonExtras.declined(context)) {
            return if (reason == FAILED && !refused(context, app)) Outcome.PROMPT else Outcome.NOTHING
        }
        if (!AutoComponents.online(context) || AutoComponents.unfavourable(context)) return Outcome.WAIT_NETWORK
        if (ProtonExtras.queued(context, own)) return Outcome.ALREADY_QUEUED
        ProtonExtras.enqueue(context, own)
        return Outcome.QUEUED
    }

    /** The player pressed "Install" in the prompt: that is the choice, so it is asked for whatever the network says. */
    fun accept(context: Context) {
        val own = tool()
        ProtonExtras.setDeclined(context, false)
        if (ProtonExtras.installedAll(context, own).isEmpty()) ProtonExtras.enqueue(context, own)
    }
}
