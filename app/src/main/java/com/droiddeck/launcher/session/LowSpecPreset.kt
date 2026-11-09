package com.droiddeck.launcher.session

import android.content.Context

/** A button in the settings sheet for weak phones: 540p and no texture filtering extras. Undo puts back what was set. */
object LowSpecPreset {
    const val RESOLUTION = "540p"
    private const val NONE = "\u0000"

    private fun backup(context: Context) = context.getSharedPreferences("low_spec_preset", Context.MODE_PRIVATE)

    fun active(context: Context, mode: String): Boolean = backup(context).contains("resolution.$mode")

    fun apply(context: Context, mode: String, panel: Pair<Int, Int>) {
        if (!active(context, mode)) {
            backup(context).edit()
                .putString("resolution.$mode", SessionPrefs.resolutionChoice(context, mode, panel))
                .putInt("anisotropy", SessionPrefs.textureAnisotropy(context))
                .putString("lodBias", SessionPrefs.textureLodBias(context))
                .apply()
        }
        SessionPrefs.setResolutionChoice(context, mode, RESOLUTION)
        SessionPrefs.setTextureAnisotropy(context, 0)
        SessionPrefs.setTextureLodBias(context, com.droiddeck.launcher.core.TextureFiltering.LOD_BIAS_OFF)
    }

    fun reset(context: Context, mode: String, panel: Pair<Int, Int>) {
        val saved = backup(context)
        saved.getString("resolution.$mode", null)?.let { SessionPrefs.setResolutionChoice(context, mode, it) } ?: return
        SessionPrefs.setTextureAnisotropy(context, saved.getInt("anisotropy", 0))
        SessionPrefs.setTextureLodBias(context, saved.getString("lodBias", NONE)?.takeIf { it != NONE }
            ?: com.droiddeck.launcher.core.TextureFiltering.LOD_BIAS_OFF)
        saved.edit().clear().apply()
    }
}
