package com.droiddeck.launcher.frontend

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * A Daijishō player's `am start` arguments as an Intent for one ROM. {file.path} is the ROM's path; {file.uri} a
 * content URI from this app's FileProvider (`<applicationId>.roms`), read access granted to the player's package.
 * {tags.scummvm_id} is the ScummVM game id written in the file (`kq6-cd-win`, or Daijishō's `[scummvm_id] kq6-cd-win`),
 * else its name. A player that needs any other tag (Steam app ids, title ids) is not usable here.
 */
object AndroidLaunch {
    private const val TAG = "AndroidLaunch"
    const val SCUMMVM_ID = "{tags.scummvm_id}"

    private fun scummvmId(rom: File): String {
        val lines = runCatching { rom.bufferedReader().use { r -> r.lineSequence().take(20).toList() } }.getOrDefault(emptyList())
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            Regex("^\\[scummvm_id]\\s*(\\S+)", RegexOption.IGNORE_CASE).find(line)?.let { return it.groupValues[1] }
            if (!line.startsWith("[") && line.none { it.isWhitespace() }) return line
        }
        return rom.nameWithoutExtension
    }

    class Launch(val intent: Intent, val uri: Uri?)

    private fun tokens(args: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (ch in args) {
            when {
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> { quote = ch; any = true }
                quote == null && ch.isWhitespace() -> { if (any) out.add(cur.toString()); cur.clear(); any = false }
                else -> { cur.append(ch); any = true }
            }
        }
        if (any) out.add(cur.toString())
        return out
    }

    fun usable(player: Platforms.Player): Boolean = !player.args.replace(SCUMMVM_ID, "").contains("{tags.")

    fun romUri(context: Context, rom: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.roms", rom)

    private fun mime(rom: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(rom.extension.lowercase()) ?: "application/octet-stream"

    fun build(context: Context, player: Platforms.Player, rom: File): Launch? {
        if (!usable(player)) return null
        var uri: Uri? = null
        fun fill(s: String): String {
            var v = s
            if (v.contains("{file.uri}")) {
                val u = uri ?: romUri(context, rom).also { uri = it }
                v = v.replace("{file.uri}", u.toString())
            }
            if (v.contains(SCUMMVM_ID)) v = v.replace(SCUMMVM_ID, scummvmId(rom))
            return v.replace("{file.path}", rom.absolutePath).replace("{file.mime}", mime(rom))
                .replace("{file.name}", rom.name).replace("{file.basename}", rom.nameWithoutExtension)
        }
        val t = tokens(player.args)
        val intent = Intent()
        var data: Uri? = null
        var type: String? = null
        var i = 0
        fun next(): String? = t.getOrNull(++i)
        while (i < t.size) {
            when (val k = t[i]) {
                "-n" -> next()?.let { intent.component = ComponentName.unflattenFromString(it) }
                "-a" -> next()?.let { intent.action = it }
                "-d" -> next()?.let { data = Uri.parse(fill(it)) }
                "-t" -> next()?.let { type = fill(it) }
                "-c" -> next()?.let { intent.addCategory(it) }
                "-e", "--es" -> { val key = next(); val v = next(); if (key != null && v != null) intent.putExtra(key, fill(v)) }
                "--ez" -> { val key = next(); val v = next(); if (key != null && v != null) intent.putExtra(key, v.equals("true", true) || v == "1") }
                "--ei" -> { val key = next(); val v = next()?.toIntOrNull(); if (key != null && v != null) intent.putExtra(key, v) }
                "--el" -> { val key = next(); val v = next()?.toLongOrNull(); if (key != null && v != null) intent.putExtra(key, v) }
                "--ef" -> { val key = next(); val v = next()?.toFloatOrNull(); if (key != null && v != null) intent.putExtra(key, v) }
                "--esa" -> { val key = next(); val v = next(); if (key != null && v != null) intent.putExtra(key, v.split(',').map { fill(it) }.toTypedArray()) }
                "-f" -> next()?.let { f -> (if (f.startsWith("0x")) f.drop(2).toIntOrNull(16) else f.toIntOrNull())?.let { intent.addFlags(it) } }
                "--activity-clear-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                "--activity-clear-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                "--activity-no-history" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                "--activity-single-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                "--activity-new-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                "--activity-exclude-from-recents" -> intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                else -> Log.w(TAG, "${player.id}: argument $k not understood; skipped")
            }
            i++
        }
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }
        // a new task, cleared: Android keeps an emulator's old task and would rebuild it with the game it had. The
        // emulator's process is closed first (MainActivity.launchRetro); clearing a live RetroArch makes it hang.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        uri?.let { u ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (intent.clipData == null) intent.clipData = ClipData.newRawUri(rom.name, u)
            player.pkg?.let { pkg ->
                runCatching { context.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
        }
        return Launch(intent, uri)
    }
}
