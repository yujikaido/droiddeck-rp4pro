package com.droiddeck.launcher.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved SMB share for the Network page: a NAS (TrueNAS, Synology…) or a Windows shared folder. */
data class NetShare(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val user: String,
    val domain: String,
    /** SMB 3 encryption for everything sent and received (slower: it's done in Java on the device). */
    val encrypt: Boolean = false,
    /** Off: the password is asked for once each time DroidDeck starts and kept in memory only. */
    val savePassword: Boolean = true,
) {
    val title: String get() = name.ifBlank { "$host\\$share" }
    val address: String get() = "\\\\$host\\$share"
}

/** Saved shares. The password is sealed with an Android Keystore key that never leaves the device, so a copy of
 * the app's preferences (a backup, a log bundle) can't be read back into it. A share with [NetShare.savePassword]
 * off keeps its password in memory only, until DroidDeck is closed. */
object NetShares {
    private const val PREFS = "net_shares"
    private const val KEY_ALIAS = "droiddeck_net_shares"
    private val remembered = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun toJson(s: NetShare) = JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share)
        .put("user", s.user).put("domain", s.domain).put("encrypt", s.encrypt).put("savePassword", s.savePassword)

    fun list(context: Context): List<NetShare> {
        val raw = prefs(context).getString("shares", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NetShare(
                    o.getString("id"), o.optString("name"), o.getString("host"), o.getString("share"), o.optString("user"), o.optString("domain"),
                    o.optBoolean("encrypt", false), o.optBoolean("savePassword", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Saves [share] (replacing one with the same id). A null [password] keeps the one it has (saved or in memory). */
    fun save(context: Context, share: NetShare, password: String?) {
        val all = list(context).filter { it.id != share.id } + share
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        val known = password ?: password(context, share.id).takeIf { it.isNotEmpty() }
        val edit = prefs(context).edit().putString("shares", arr.toString())
        if (share.savePassword) {
            if (known != null) edit.putString("pw:${share.id}", seal(known))
            remembered.remove(share.id)
        } else {
            edit.remove("pw:${share.id}")
            if (known != null) remembered[share.id] = known
        }
        edit.apply()
    }

    /** A share that signs in with a password that isn't saved, and hasn't been typed since DroidDeck started. */
    fun needsPassword(context: Context, share: NetShare): Boolean =
        !share.savePassword && share.user.isNotBlank() && password(context, share.id).isEmpty()

    /** Keeps a typed password in memory for this run (shares that don't save theirs). */
    fun rememberPassword(id: String, password: String) { remembered[id] = password }

    fun remove(context: Context, id: String) {
        val arr = JSONArray()
        list(context).filter { it.id != id }.forEach { arr.put(toJson(it)) }
        remembered.remove(id)
        prefs(context).edit().putString("shares", arr.toString()).remove("pw:$id").apply()
    }

    fun password(context: Context, id: String): String =
        prefs(context).getString("pw:$id", null)?.let { runCatching { open(it) }.getOrNull() } ?: remembered[id] ?: ""

    fun newId(): String = UUID.randomUUID().toString()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(stored: String): String {
        if (stored.isEmpty()) return ""
        val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(data), Charsets.UTF_8)
    }
}
