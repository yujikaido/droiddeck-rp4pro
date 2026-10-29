package com.droiddeck.launcher.session

import org.json.JSONObject

/**
 * The known-games table: per Steam game, the settings that game is known to need on this kind of
 * device (assets/known-games.json, or a newer downloaded copy of the same schema). The table only
 * says what is wanted; who set what is decided by [plan], which never takes back a player's choice.
 *
 * Every setting is one field: "tool", "dxvk", "fex", "env:<KEY>", "bridge". A field's value is a
 * plain string (a Proton depot folder, a DXVK package file, a FEX preset id, an environment value,
 * the bridge preset file's text), so one rule covers them all.
 */
object KnownGames {
    const val SCHEMA = 1
    const val TOOL = "tool"
    const val DXVK = "dxvk"
    const val FEX = "fex"
    const val BRIDGE = "bridge"
    const val ENV = "env:"

    /** Table tool ids and the Proton depot folders they stand for (the guest's DroidDeck builds of them). */
    val TOOLS = mapOf(
        "proton-experimental-arm64" to "Proton Experimental (ARM64)",
        "proton-11-arm64" to "Proton 11.0 (ARM64)",
    )
    const val DEFAULT_TOOL = "proton-experimental-arm64"

    private val APP_ID = Regex("[1-9][0-9]{0,9}")
    private val DXVK_VERSION = Regex("[0-9]+(\\.[0-9]+){1,3}")
    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val BRIDGE_KEY = Regex("VKBRIDGE_[A-Z0-9_]+")
    private val SAFE_VALUE = Regex("[^\\n\\r\\u0000]{0,512}")
    private val MALI_RELEASE = Regex("r([0-9]+)p([0-9]+)")

    /** What the device is; null members are unknown, and a condition on an unknown never holds. */
    data class Device(
        val gpu: String?,
        val driver: Pair<Int, Int>?,
        val vulkan: Pair<Int, Int>?,
        val bridge: Boolean,
    )

    data class Condition(
        val gpus: List<String> = emptyList(),
        val driverMin: Pair<Int, Int>? = null,
        val driverMax: Pair<Int, Int>? = null,
        val vulkanBelow: Pair<Int, Int>? = null,
        val vulkanAtLeast: Pair<Int, Int>? = null,
        val bridge: Boolean? = null,
    ) {
        fun holds(device: Device): Boolean {
            if (bridge != null && bridge != device.bridge) return false
            if (gpus.isNotEmpty() && (device.gpu == null || gpus.none { device.gpu.contains(it, ignoreCase = true) })) return false
            if (driverMin != null && (device.driver == null || compare(device.driver, driverMin) < 0)) return false
            if (driverMax != null && (device.driver == null || compare(device.driver, driverMax) > 0)) return false
            if (vulkanBelow != null && (device.vulkan == null || compare(device.vulkan, vulkanBelow) >= 0)) return false
            if (vulkanAtLeast != null && (device.vulkan == null || compare(device.vulkan, vulkanAtLeast) < 0)) return false
            return true
        }
    }

    data class Entry(
        val name: String,
        val condition: Condition,
        val fields: Map<String, String>,
        val source: String,
    )

    data class Table(val version: Int, val defaultTool: String, val games: Map<String, List<Entry>>)

    /** What a game gets on this device: its name, its fields, and where they come from. */
    data class Wanted(val name: String, val fields: Map<String, String>, val sources: List<String>)

    enum class By { MALIBRIDGE, PLAYER }

    /**
     * Who owns a field of a game. For MALIBRIDGE, the value MaliBridge put there. [pending]: written
     * before MaliBridge changes the field to [value] (null: takes it back), so a run cut short between
     * the change and the final marks leaves the field MaliBridge's at either [previous] or [value].
     */
    data class Mark(val by: By, val value: String?, val previous: String? = null, val pending: Boolean = false) {
        fun mine(now: String?): Boolean = by == By.MALIBRIDGE && (now == value || (pending && now == previous))
    }

    data class Plan(
        val set: Map<String, String>,
        val clear: Set<String>,
        val marks: Map<String, Mark>,
    ) {
        val changes: Boolean get() = set.isNotEmpty() || clear.isNotEmpty()
    }

    internal fun compare(a: Pair<Int, Int>, b: Pair<Int, Int>): Int =
        if (a.first != b.first) a.first.compareTo(b.first) else a.second.compareTo(b.second)

    /** "r32p1" -> 32 to 1; "1.3" or "1.3.250" -> 1 to 3. */
    internal fun version(text: String?): Pair<Int, Int>? {
        val t = text?.trim().orEmpty()
        MALI_RELEASE.matchEntire(t)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val parts = t.split('.')
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        return major to minor
    }

    /** The Mali driver release from the driver's own words ("v1.r32p1-01eac0.2b8d..."), else null. */
    fun maliRelease(driverInfo: String?): Pair<Int, Int>? =
        driverInfo?.let { MALI_RELEASE.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    fun dxvkFile(version: String): String = "dxvk-$version-linux.wcp"

    /** The bridge preset file MaliBridge writes for a game; the first line says who wrote it. */
    fun bridgeFile(values: Map<String, String>): String =
        "# Set by MaliBridge (known games). Edit or delete this file and MaliBridge leaves it to you.\n" +
            values.entries.joinToString("") { (k, v) -> "$k=$v\n" }

    /** A table, or null when it is not one this build reads. Bad entries are dropped one by one. */
    fun parse(text: String?): Table? {
        val root = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return null
        if (root.optInt("schema", 0) != SCHEMA) return null
        val version = root.optInt("version", 0).takeIf { it > 0 } ?: return null
        val defaultTool = root.optString("defaultTool", DEFAULT_TOOL).takeIf { it in TOOLS } ?: DEFAULT_TOOL
        val games = root.optJSONObject("games") ?: JSONObject()
        val parsed = buildMap {
            for (id in games.keys()) {
                if (!APP_ID.matches(id) || id.toLong() > 4294967295L) continue
                val list = games.optJSONArray(id) ?: continue
                val entries = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::entry) }
                if (entries.isNotEmpty()) put(id, entries)
            }
        }
        return Table(version, defaultTool, parsed)
    }

    private fun entry(o: JSONObject): Entry? {
        val fields = LinkedHashMap<String, String>()
        o.optString("tool").takeIf { it.isNotEmpty() }?.let { fields[TOOL] = TOOLS[it] ?: return null }
        o.optString("dxvk").takeIf { it.isNotEmpty() }?.let {
            if (!DXVK_VERSION.matches(it)) return null
            fields[DXVK] = dxvkFile(it)
        }
        o.optString("fexPreset").takeIf { it.isNotEmpty() }?.let {
            if (!ENV_NAME.matches(it)) return null
            fields[FEX] = it
        }
        o.optJSONObject("env")?.let { env ->
            for (k in env.keys()) {
                val v = env.optString(k)
                if (!ENV_NAME.matches(k) || k.startsWith("VKBRIDGE_") || !SAFE_VALUE.matches(v)) return null
                fields[ENV + k] = v
            }
        }
        o.optJSONObject("bridge")?.let { bridge ->
            val values = sortedMapOf<String, String>()
            for (k in bridge.keys()) {
                val v = bridge.optString(k)
                if (!BRIDGE_KEY.matches(k) || v.isEmpty() || v.any { it.isWhitespace() } || !SAFE_VALUE.matches(v)) return null
                values[k] = v
            }
            if (values.isNotEmpty()) fields[BRIDGE] = bridgeFile(values)
        }
        if (fields.isEmpty()) return null
        val w = o.optJSONObject("when")
        val condition = if (w == null) Condition() else Condition(
            gpus = w.optJSONArray("gpu")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
                ?: w.optString("gpu").takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty(),
            driverMin = w.optString("driverMin").takeIf { it.isNotEmpty() }?.let { version(it) ?: return null },
            driverMax = w.optString("driverMax").takeIf { it.isNotEmpty() }?.let { version(it) ?: return null },
            vulkanBelow = w.optString("vulkanBelow").takeIf { it.isNotEmpty() }?.let { version(it) ?: return null },
            vulkanAtLeast = w.optString("vulkanAtLeast").takeIf { it.isNotEmpty() }?.let { version(it) ?: return null },
            bridge = if (w.has("bridge")) w.optBoolean("bridge") else null,
        )
        return Entry(o.optString("name"), condition, fields, o.optString("source"))
    }

    /** The shipped table, unless a downloaded copy of the same schema is newer. */
    fun choose(shipped: Table?, downloaded: Table?): Table? = when {
        downloaded == null -> shipped
        shipped == null -> downloaded
        downloaded.version > shipped.version -> downloaded
        else -> shipped
    }

    /** The entries for [appId] that hold on [device], later ones winning field by field; null if none. */
    fun resolve(table: Table, appId: String, device: Device): Wanted? {
        val entries = table.games[appId].orEmpty().filter { it.condition.holds(device) }
        if (entries.isEmpty()) return null
        val fields = LinkedHashMap<String, String>()
        entries.forEach { fields.putAll(it.fields) }
        return Wanted(entries.last().name.ifEmpty { appId }, fields, entries.map { it.source })
    }

    /**
     * What to change for one game. [wanted] is what the table asks for now, [current] what is set now
     * (missing: nothing set), [marks] who set what before, [global] the fields the player set for all
     * games (the Default profile's environment, a general FEX preset other than the default).
     *  - A field the player owns stays theirs for good.
     *  - A field the player set for all games is theirs too: a per-game value would override it, so
     *    MaliBridge sets none, and takes back one it set before.
     *  - A field with no mark: MaliBridge sets it only when nothing is set; something already set
     *    there is the player's.
     *  - A field MaliBridge set: still its value, MaliBridge keeps it in line with the table (changes
     *    or removes it); anything else there now (another value, or nothing: cleared or reset) is the
     *    player's doing, and stays.
     */
    fun plan(
        wanted: Map<String, String>, current: Map<String, String?>, marks: Map<String, Mark>,
        global: Set<String> = emptySet(),
    ): Plan {
        val set = LinkedHashMap<String, String>()
        val clear = LinkedHashSet<String>()
        val out = LinkedHashMap<String, Mark>()
        for (field in (marks.keys + wanted.keys)) {
            val mark = marks[field]
            val now = current[field]
            val want = wanted[field]
            when {
                mark?.by == By.PLAYER -> out[field] = mark
                mark == null && (now != null || field in global) -> out[field] = Mark(By.PLAYER, null)
                mark == null -> if (want != null) { set[field] = want; out[field] = Mark(By.MALIBRIDGE, want) }
                !mark.mine(now) -> out[field] = Mark(By.PLAYER, null)
                field in global -> { if (now != null) clear += field; out[field] = Mark(By.PLAYER, null) }
                want == null -> if (now != null) clear += field
                want != now -> { set[field] = want; out[field] = Mark(By.MALIBRIDGE, want) }
                else -> out[field] = Mark(By.MALIBRIDGE, now)
            }
        }
        return Plan(set, clear, out)
    }

    /** A field as the player reads it: "PROTON_USE_WINED3D=1", "DXVK 1.10.3", "FEX preset STABILITY". */
    fun describe(field: String, value: String): String = when {
        field == TOOL -> value.replace(" (ARM64)", " ARM64")
        field == DXVK -> "DXVK " + value.removePrefix("dxvk-").removeSuffix("-linux.wcp")
        field == FEX -> "FEX preset $value"
        field == BRIDGE -> "bridge preset " + value.lineSequence().filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString(" ")
        field.startsWith(ENV) -> field.removePrefix(ENV) + "=" + value
        else -> "$field=$value"
    }

    fun encodeMarks(games: Map<String, Map<String, Mark>>, tableVersion: Int): JSONObject = JSONObject()
        .put("version", 1)
        .put("table", tableVersion)
        .put("games", JSONObject().apply {
            games.forEach { (id, fields) ->
                if (fields.isEmpty()) return@forEach
                put(id, JSONObject().apply {
                    fields.forEach { (field, mark) ->
                        put(field, JSONObject().put("by", if (mark.by == By.PLAYER) "player" else "malibridge").apply {
                            if (mark.value != null) put("value", mark.value)
                            if (mark.pending) { put("pending", true); if (mark.previous != null) put("previous", mark.previous) }
                        })
                    }
                })
            }
        })

    private fun JSONObject.optNullString(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    fun decodeMarks(text: String?): Map<String, Map<String, Mark>> {
        val root = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return emptyMap()
        if (root.optInt("version", 0) != 1) return emptyMap()
        val games = root.optJSONObject("games") ?: return emptyMap()
        return buildMap {
            for (id in games.keys()) {
                val fields = games.optJSONObject(id) ?: continue
                put(id, buildMap {
                    for (field in fields.keys()) {
                        val m = fields.optJSONObject(field) ?: continue
                        when (m.optString("by")) {
                            "player" -> put(field, Mark(By.PLAYER, null))
                            "malibridge" -> when {
                                m.optBoolean("pending") -> put(field, Mark(By.MALIBRIDGE, m.optNullString("value"), m.optNullString("previous"), pending = true))
                                m.has("value") -> put(field, Mark(By.MALIBRIDGE, m.optString("value")))
                            }
                        }
                    }
                })
            }
        }
    }
}
