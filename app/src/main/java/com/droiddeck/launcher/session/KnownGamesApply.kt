package com.droiddeck.launcher.session

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.AppVulkan
import com.droiddeck.launcher.gpu.VkBridgeComponent
import com.droiddeck.launcher.gpu.VulkanInfo
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Puts the known-games table (KnownGames) into the stores the game already reads at its launch:
 *  - tool: <compat dir>/known-games.json, a suggestion the guest's steam-compatibility takes in the
 *    default's place for that title only (a pick in the client's menu or in the app beats it);
 *  - dxvk: the game's component profile (ComponentsManager), the package fetched first;
 *  - fex, env: the game's environment (GameEnvironmentStore);
 *  - bridge: <bridge cache dir>/presets/<appid>.env, the bridge server's per-game file.
 * Run for every title with an appmanifest (so one being installed has its settings before its first
 * start) at session start, then again whenever a title or a Proton appears during the session.
 * Who set what is kept in <files>/known-games-applied.json; KnownGames.plan decides.
 */
object KnownGamesApply {
    private const val TAG = "KnownGames"
    private const val DOWNLOADED = "known-games.json"
    private const val MARKS = "known-games-applied.json"
    private const val GUEST_FILE = "root/.local/share/droiddeck-compat/known-games.json"
    private const val INHERIT = "\u0000inherit"
    private const val UNSET = "\u0000unset"
    private const val WATCH_MS = 15_000L
    private const val RETRY_MS = 3 * 60_000L

    private fun shipped(context: Context): KnownGames.Table? =
        runCatching { context.assets.open(DOWNLOADED).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull().let(KnownGames::parse)

    /** The table in use: the shipped one, or a newer downloaded copy at <files>/known-games.json. */
    fun table(context: Context): KnownGames.Table? = KnownGames.choose(
        shipped(context),
        File(context.filesDir, DOWNLOADED).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.let(KnownGames::parse),
    )

    fun device(context: Context): KnownGames.Device {
        val info = VulkanInfo.query()
        return KnownGames.Device(
            gpu = info["device"],
            driver = KnownGames.maliRelease(info["driver_info"]),
            vulkan = runCatching { AppVulkan.detect(context) }.getOrNull()?.let { it.major to it.minor },
            bridge = DeviceSupport.vkBridge(context),
        )
    }

    /** Every Steam app id with an appmanifest, installed or being installed. */
    fun installedIds(context: Context): Set<String> = buildSet {
        for ((library, _) in Library.steamLibraries(context)) {
            File(library, "steamapps").listFiles()?.forEach { f ->
                val id = f.name.removePrefix("appmanifest_").removeSuffix(".acf")
                if (f.name.startsWith("appmanifest_") && f.name.endsWith(".acf") && GameEnvironment.validScope(id) && id.isNotEmpty()) add(id)
            }
        }
    }

    private fun marksFile(context: Context) = File(context.filesDir, MARKS)
    private fun guestFile(context: Context) = File(LinuxRuntime.rootDir(context), GUEST_FILE)
    private fun presetFile(context: Context, appId: String) = File(VkBridgeComponent.cacheDir(context), "presets/$appId.env")

    fun marks(context: Context): Map<String, Map<String, KnownGames.Mark>> =
        KnownGames.decodeMarks(runCatching { AtomicFile(marksFile(context)).readFully().toString(Charsets.UTF_8) }.getOrNull())

    /** Staged and renamed into place, synced first, so the file is the old marks or the new ones, never half. */
    private fun writeMarks(context: Context, games: Map<String, Map<String, KnownGames.Mark>>, tableVersion: Int) {
        val file = AtomicFile(marksFile(context))
        val out = file.startWrite()
        try {
            out.write((KnownGames.encodeMarks(games, tableVersion).toString(1) + "\n").toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun suggestions(context: Context): MutableMap<String, String> {
        val root = runCatching { JSONObject(guestFile(context).readText()) }.getOrNull() ?: return mutableMapOf()
        val games = root.optJSONObject("games") ?: return mutableMapOf()
        return games.keys().asSequence().mapNotNull { id -> games.optJSONObject(id)?.optString("dir")?.takeIf { it.isNotEmpty() }?.let { id to it } }
            .toMap().toMutableMap()
    }

    private fun writeAtomic(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "no folder for ${file.name}" }
        val staged = File(file.parentFile, file.name + ".staged")
        staged.writeText(text)
        if (!staged.renameTo(file)) { staged.delete(); throw java.io.IOException("could not write ${file.name}") }
    }

    /** What is set now for a game, field by field (KnownGames' fields), read from each store. */
    private fun current(
        context: Context, appId: String, fields: Set<String>, config: GameEnvironment.Config,
        choices: Map<String, ProtonDefault.GameChoice?>, suggested: Map<String, String>,
    ): Map<String, String?> = fields.associateWith { field ->
        when {
            field == KnownGames.TOOL ->
                if (choices.containsKey(appId)) choices[appId]?.dir ?: INHERIT else suggested[appId]
            field == KnownGames.DXVK -> ComponentsManager.gameComponents(context, appId)["dxvk"]
            field == KnownGames.FEX -> GameEnvironment.gameFexPreset(config, appId)
            field == KnownGames.BRIDGE -> presetFile(context, appId).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            field.startsWith(KnownGames.ENV) -> config.games[appId]?.let { e ->
                val key = field.removePrefix(KnownGames.ENV)
                if (e.containsKey(key)) e[key] ?: UNSET else null
            }
            else -> null
        }
    }

    /** What a run said, and whether something it wanted to set or take back is still to do. */
    data class Result(val lines: List<String>, val failed: Boolean)

    /**
     * The fields a game's per-game value would override a choice the player made for all games in: the
     * Default profile's environment, or a general FEX preset other than the default.
     */
    internal fun globalFields(fields: Collection<String>, shared: Map<String, String?>, generalFexPreset: String): Set<String> =
        fields.filter { field ->
            when {
                field == KnownGames.FEX -> generalFexPreset != SessionPrefs.DEFAULT_FEX_PRESET || FexPreset.environmentKeys.any(shared::containsKey)
                field.startsWith(KnownGames.ENV) -> shared.containsKey(field.removePrefix(KnownGames.ENV))
                else -> false
            }
        }.toSet()

    /**
     * The marks to keep while a run changes things: each field about to be set or taken back is
     * MaliBridge's at the value it has now or at the one it is getting, so a run cut short anywhere
     * leaves it MaliBridge's to finish, not the player's.
     */
    internal fun stagedMarks(plan: KnownGames.Plan, now: Map<String, String?>): Map<String, KnownGames.Mark> =
        plan.marks + (plan.set.keys + plan.clear).associateWith { field ->
            KnownGames.Mark(KnownGames.By.MALIBRIDGE, plan.set[field], previous = now[field], pending = true)
        }

    /**
     * Applies the table to [ids]. [download]: fetch a DXVK package that is not here (off the main
     * thread only); without it, such a field waits for the next run.
     */
    @Synchronized
    fun apply(context: Context, ids: Collection<String>, download: Boolean): Result {
        val table = table(context) ?: return Result(listOf("known games: no table"), failed = false)
        val device = device(context)
        val keepProtonDxvk = SessionPrefs.bridgeProtonDxvk(context)
        val order = ids.sortedBy { it.toLongOrNull() ?: 0L }
        val wanted = order.associateWith { KnownGames.resolve(table, it, device) }
        fun fields(id: String) = wanted[id]?.fields.orEmpty().filterKeys { !(it == KnownGames.DXVK && keepProtonDxvk) }
        val lines = mutableListOf<String>()
        var failed = false

        // Downloads first, before the game's settings are read: a slow one must not hold them, or the
        // settings saved after it would be the ones from before it, without what the player changed meanwhile.
        val marksBefore = marks(context)
        val notHere = mutableMapOf<String, String>()
        for (id in order) {
            val dxvk = fields(id)[KnownGames.DXVK] ?: continue
            if (dxvk in notHere || ComponentsManager.hasPackage(context, dxvk)) continue
            val mark = marksBefore[id]?.get(KnownGames.DXVK)
            val now = ComponentsManager.gameComponents(context, id)["dxvk"]
            val plan = KnownGames.plan(mapOf(KnownGames.DXVK to dxvk), mapOf(KnownGames.DXVK to now), listOfNotNull(mark?.let { KnownGames.DXVK to it }).toMap())
            if (KnownGames.DXVK !in plan.set) continue
            val why = if (!download) "waits for its download"
                else runCatching { ComponentsManager.fetchPackage(context, "dxvk", dxvk, "Dxvk-Linux") }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (why != null) notHere[dxvk] = why
        }

        val allMarks = marks(context).toMutableMap()
        val choices = ProtonDefault.gameChoices(context)
        val suggested = suggestions(context)
        val suggestedBefore = suggested.toMap()
        val plans = LinkedHashMap<String, KnownGames.Plan>()
        // The environment (FEX preset, env) is read, planned and changed under the store's own lock, so an
        // edit the player makes in the drawer meanwhile is either seen here or made after, never lost.
        GameEnvironmentStore.update(context) { start ->
            var config = start
            val generalFex = SessionPrefs.fexPreset(context)
            val staged = allMarks.toMutableMap()
            for (id in order) {
                val marks = allMarks[id].orEmpty()
                if (wanted[id] == null && marks.isEmpty()) continue
                val fields = fields(id)
                val now = current(context, id, fields.keys + marks.keys, config, choices, suggested)
                val plan = KnownGames.plan(fields, now, marks, globalFields(fields.keys + marks.keys, config.shared, generalFex))
                plans[id] = plan
                staged[id] = stagedMarks(plan, now)
            }
            if (plans.values.any { it.changes }) writeMarks(context, staged, table.version)
            for ((id, plan) in plans) {
                for ((field, value) in plan.set) when {
                    field == KnownGames.FEX -> config = GameEnvironment.withGameFexPreset(config, id, value)
                    field.startsWith(KnownGames.ENV) -> config = GameEnvironment.withScopeValue(config, id, field.removePrefix(KnownGames.ENV), value)
                }
                for (field in plan.clear) when {
                    field == KnownGames.FEX -> config = GameEnvironment.withGameFexPreset(config, id, null)
                    field.startsWith(KnownGames.ENV) -> config = GameEnvironment.withScopeValue(config, id, field.removePrefix(KnownGames.ENV), null)
                }
            }
            config
        }

        for ((id, plan) in plans) {
            val marks = allMarks[id].orEmpty()
            val fields = fields(id)
            val marksOut = plan.marks.toMutableMap()
            val done = mutableListOf<String>()
            for ((field, value) in plan.set) {
                val ok = runCatching {
                    when {
                        field == KnownGames.TOOL -> suggested[id] = value
                        field == KnownGames.DXVK -> {
                            notHere[value]?.let { error(it) }
                            ComponentsManager.setGameComponent(context, id, "dxvk", value)
                        }
                        field == KnownGames.BRIDGE -> writeAtomic(presetFile(context, id), value)
                    }
                    Unit
                }.onFailure { e ->
                    // Not set: the mark from before, so the next run tries again (and the field stays the player's to set meanwhile).
                    failed = true
                    if (marks[field] == null) marksOut.remove(field) else marksOut[field] = marks.getValue(field)
                    lines += "known games: ${wanted[id]?.name ?: id}: ${KnownGames.describe(field, value)} not set (${e.message ?: e.javaClass.simpleName})"
                }.isSuccess
                if (ok) done += KnownGames.describe(field, value)
            }
            for (field in plan.clear) {
                runCatching {
                    when {
                        field == KnownGames.TOOL -> suggested.remove(id)
                        field == KnownGames.DXVK -> ComponentsManager.setGameComponent(context, id, "dxvk", null)
                        field == KnownGames.BRIDGE -> presetFile(context, id).let { f -> check(!f.exists() || f.delete()) { "could not delete ${f.name}" } }
                    }
                    Unit
                }.onFailure { e ->
                    // Still there: still MaliBridge's, so the next run takes it back.
                    failed = true
                    marks[field]?.let { marksOut[field] = it }
                    Log.w(TAG, "$id: could not undo $field", e)
                }
            }
            if (marksOut.isEmpty()) allMarks.remove(id) else allMarks[id] = marksOut
            val name = wanted[id]?.name ?: id
            val kept = marksOut.filter { it.value.by == KnownGames.By.PLAYER && it.key in fields }.keys
                .map { KnownGames.describe(it, fields.getValue(it)) }
            if (done.isNotEmpty()) lines += "known games: $name ($id): set by MaliBridge: ${done.joinToString(", ")}"
            if (plan.clear.isNotEmpty()) lines += "known games: $name ($id): no longer needed, taken back: ${plan.clear.joinToString(", ")}"
            if (kept.isNotEmpty() && (done.isNotEmpty() || plan.clear.isNotEmpty() || marks != marksOut))
                lines += "known games: $name ($id): the player's own choice kept instead of ${kept.joinToString(", ")}"
        }
        if (suggested != suggestedBefore) {
            val games = JSONObject()
            suggested.toSortedMap().forEach { (id, dir) -> games.put(id, JSONObject().put("valve", true).put("dir", dir)) }
            writeAtomic(guestFile(context), JSONObject().put("version", 1).put("games", games).toString(1) + "\n")
        }
        writeMarks(context, allMarks, table.version)
        return Result(lines, failed)
    }

    /** One line for a game's profile: what MaliBridge set for it, or null. */
    fun note(context: Context, appId: String): String? {
        val mine = marks(context)[appId].orEmpty().filter { it.value.by == KnownGames.By.MALIBRIDGE && it.value.value != null }
        if (mine.isEmpty()) return null
        return mine.entries.joinToString(", ") { KnownGames.describe(it.key, it.value.value!!) }
    }

    fun appendLog(log: File?, lines: List<String>) {
        lines.forEach { Log.i(TAG, it) }
        if (log == null || lines.isEmpty()) return
        runCatching { FileOutputStream(log, true).use { out -> lines.forEach { out.write("droiddeck: $it\n".toByteArray()) } } }
            .onFailure { Log.w(TAG, "could not write to the session log", it) }
    }

    /**
     * The watcher's bookkeeping, apart from the stores so it can be tested. Titles: a new set of them is
     * applied, and a run that left something undone (a DXVK download that failed offline) is tried again
     * [retryMs] later. Protons: a change counts once it reads the same on two polls in a row, so a Proton
     * Steam is still downloading or updating is left alone until it is done.
     */
    internal class Watch(private val retryMs: Long) {
        private var ids: Set<String>? = null
        private var retryAt: Long? = null
        private var protonsStarted = false
        private var protonsSeen: String? = null
        private var protonsSettled: String? = null

        fun idsDue(now: Set<String>, at: Long): Boolean = now != ids || retryAt?.let { at >= it } == true

        fun idsDone(now: Set<String>, failed: Boolean, at: Long) {
            ids = now
            retryAt = if (failed) at + retryMs else null
        }

        /** [signature]: the Protons there now, null when one cannot be read whole (its version file missing). */
        fun protonsDue(signature: String?): Boolean {
            val before = protonsSeen
            protonsSeen = signature
            if (!protonsStarted) { protonsStarted = true; protonsSettled = signature; return false }
            if (signature == null || signature != before || signature == protonsSettled) return false
            protonsSettled = signature
            return true
        }
    }

    /** The Protons as one string: each one's folder, version and version file, or null when one has none. */
    private fun protonSignature(context: Context): String? {
        val parts = mutableListOf<String>()
        for (p in ComponentsManager.protons(context).sortedBy { it.id }) {
            val version = File(p.dir, "version")
            if (!version.isFile || p.version == "unknown") return null
            parts += "${p.id}@${p.version}:${version.length()}:${version.lastModified()}:${p.dir.lastModified()}"
        }
        return parts.joinToString("|")
    }

    /**
     * During a session: titles that appear (an install started) get their settings, and when a Proton
     * appears or changes (Steam downloads or updates one) and a game of the table is here, the automatic
     * DXVK goes into it and the per-game DXVK lines are written for it, once the Proton has settled.
     * Nothing here makes a game's start wait.
     */
    fun watch(context: Context, log: File?, alive: () -> Boolean) {
        val app = context.applicationContext
        Thread({
            val watch = Watch(RETRY_MS)
            var prefetched = false
            while (alive()) {
                runCatching {
                    if (!prefetched) {
                        prefetched = true
                        ComponentsManager.prefetchAutoDxvk(app)?.let { appendLog(log, listOf("automatic DXVK: $it")) }
                    }
                    val nowIds = installedIds(app)
                    val at = System.currentTimeMillis()
                    if (watch.idsDue(nowIds, at)) {
                        val result = runCatching { apply(app, nowIds, download = true) }
                        result.getOrNull()?.let { appendLog(log, it.lines) }
                        result.exceptionOrNull()?.let { Log.w(TAG, "known games", it) }
                        watch.idsDone(nowIds, result.getOrNull()?.failed ?: true, at)
                    }
                    if (watch.protonsDue(protonSignature(app)) && tableGameHere(app, nowIds)) {
                        ComponentsManager.ensureForcedPackages(app)?.let { appendLog(log, listOf("automatic DXVK: $it")) }
                        ComponentsManager.republish(app)
                    }
                }.onFailure { Log.w(TAG, "known games", it) }
                try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "known-games").start()
    }

    /** Whether a title here has an entry of the table for this device. */
    private fun tableGameHere(context: Context, ids: Set<String>): Boolean {
        val table = table(context) ?: return false
        val device = device(context)
        return ids.any { KnownGames.resolve(table, it, device) != null }
    }
}
