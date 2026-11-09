package com.droiddeck.launcher.runtime

/** On the Mali bridge, a runtime update over an installed runtime (an install over MaliBridge's
 *  0.4.0-based builds brings a new Ubuntu runtime) is said before it downloads: its size, the free
 *  space, and that Steam's sign-in, the games and the Proton prefixes stay. */
internal object RuntimeUpdateNotice {
    fun wanted(bridge: Boolean, installed: String?, available: String?): Boolean =
        bridge && installed != null && available != null && available != installed

    /** Download MB, and whether the free space is short: the archive plus its unpacked tree (about 2.5x). */
    fun downloadMb(sizeBytes: Long): Long = (sizeBytes + 999_999) / 1_000_000

    fun short(sizeBytes: Long, freeBytes: Long): Boolean = sizeBytes > 0 && freeBytes < sizeBytes * 7 / 2
}
