package com.droiddeck.launcher.session

import android.content.SharedPreferences
import android.util.Log

/**
 * Session preferences that survive a value of the wrong type. A setting written as text where the
 * app keeps an on/off (by `am`/`settings` from a shell, a test harness or an older build) made
 * SharedPreferences throw ClassCastException, and reading the HUD setting at session start crashed
 * the app. A wrong-typed value is read as what it says when it can be ("true", "0", "60"), else
 * as the default, and stored again with the right type so the next read is a plain one.
 */
internal class TolerantPrefs(private val base: SharedPreferences) : SharedPreferences by base {
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = try {
        base.getBoolean(key, defValue)
    } catch (_: ClassCastException) {
        val value = when (val raw = base.all[key]) {
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
            is Number -> raw.toLong() != 0L
            else -> null
        }
        repair(key, value ?: defValue, value != null) { putBoolean(key, it) }
    }

    override fun getInt(key: String?, defValue: Int): Int = try {
        base.getInt(key, defValue)
    } catch (_: ClassCastException) {
        val value = when (val raw = base.all[key]) {
            is String -> raw.trim().toIntOrNull()
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Boolean -> if (raw) 1 else 0
            else -> null
        }
        repair(key, value ?: defValue, value != null) { putInt(key, it) }
    }

    override fun getLong(key: String?, defValue: Long): Long = try {
        base.getLong(key, defValue)
    } catch (_: ClassCastException) {
        val value = when (val raw = base.all[key]) {
            is String -> raw.trim().toLongOrNull()
            is Int -> raw.toLong()
            else -> null
        }
        repair(key, value ?: defValue, value != null) { putLong(key, it) }
    }

    override fun getFloat(key: String?, defValue: Float): Float = try {
        base.getFloat(key, defValue)
    } catch (_: ClassCastException) {
        val value = when (val raw = base.all[key]) {
            is String -> raw.trim().toFloatOrNull()
            is Number -> raw.toFloat()
            else -> null
        }
        repair(key, value ?: defValue, value != null) { putFloat(key, it) }
    }

    override fun getString(key: String?, defValue: String?): String? = try {
        base.getString(key, defValue)
    } catch (_: ClassCastException) {
        // A number or an on/off where text is kept: its text is what was meant. A set has no one text.
        val value = when (val raw = base.all[key]) {
            is Number, is Boolean -> raw.toString()
            else -> null
        }
        repair(key, value ?: defValue, value != null) { if (it == null) remove(key) else putString(key, it) }
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = try {
        base.getStringSet(key, defValues)
    } catch (_: ClassCastException) {
        repair(key, defValues, false) { remove(key) }
    }

    private inline fun <T> repair(key: String?, value: T, understood: Boolean, put: SharedPreferences.Editor.(T) -> Unit): T {
        Log.w(TAG, "setting $key had the wrong type (${base.all[key]?.javaClass?.simpleName}); " +
            if (understood) "kept its value, stored again with the right type" else "back to its default")
        runCatching { base.edit().apply { if (understood) put(value) else remove(key) }.apply() }
        return value
    }

    private companion object {
        const val TAG = "SessionPrefs"
    }
}
