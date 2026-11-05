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
) {
    val title: String get() = name.ifBlank { "$host\\$share" }
    val address: String get() = "\\\\$host\\$share"
}

/** Saved shares. The password is sealed with an Android Keystore key that never leaves the device, so a copy of
 * the app's preferences (a backup, a log bundle) can't be read back into it. */
object NetShares {
    private const val PREFS = "net_shares"
    private const val KEY_ALIAS = "droiddeck_net_shares"

    fun list(context: Context): List<NetShare> {
        val raw = prefs(context).getString("shares", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NetShare(o.getString("id"), o.optString("name"), o.getString("host"), o.getString("share"), o.optString("user"), o.optString("domain"))
            }
        }.getOrDefault(emptyList())
    }

    /** Saves [share] (replacing one with the same id). A null [password] keeps the stored one. */
    fun save(context: Context, share: NetShare, password: String?) {
        val all = list(context).filter { it.id != share.id } + share
        val arr = JSONArray()
        all.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share).put("user", s.user).put("domain", s.domain))
        }
        val edit = prefs(context).edit().putString("shares", arr.toString())
        if (password != null) edit.putString("pw:${share.id}", seal(password))
        edit.apply()
    }

    fun remove(context: Context, id: String) {
        val arr = JSONArray()
        list(context).filter { it.id != id }.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share).put("user", s.user).put("domain", s.domain))
        }
        prefs(context).edit().putString("shares", arr.toString()).remove("pw:$id").apply()
    }

    fun password(context: Context, id: String): String =
        prefs(context).getString("pw:$id", null)?.let { runCatching { open(it) }.getOrNull() } ?: ""

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
