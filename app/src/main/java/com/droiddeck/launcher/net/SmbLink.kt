package com.droiddeck.launcher.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** One file or folder on a share. [path] is relative to the share root, with '\' between parts ("" is the root). */
data class NetEntry(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long)

/** An open connection to one share. Not thread-safe: the page and the transfer worker each open their own. */
class SmbLink private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    val disk: DiskShare,
) : Closeable {

    fun list(path: String): List<NetEntry> =
        disk.list(path).asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val dir = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                NetEntry(info.fileName, join(path, info.fileName), dir, if (dir) 0L else info.endOfFile, info.lastWriteTime.toEpochMillis())
            }
            .sortedWith(compareBy<NetEntry>({ !it.dir }, { it.name.lowercase() }))
            .toList()

    /** A file's size and last-write time, or null if it isn't there. */
    fun stat(path: String): NetEntry? {
        if (!disk.fileExists(path)) return null
        val info = disk.getFileInformation(path)
        return NetEntry(path.substringAfterLast('\\'), path, false, info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis())
    }

    /** Free space on the share, or null when the server doesn't say. */
    fun freeSpace(): Long? = runCatching { disk.shareInformation.freeSpace }.getOrNull()?.takeIf { it > 0L }

    /** Gives a finished upload the device file's date, then moves it from its .ddpart name onto [path]. */
    fun finish(file: com.hierynomus.smbj.share.File, path: String, modified: Long) {
        if (modified > 0L) file.setFileInformation(
            FileBasicInformation(FileBasicInformation.DONT_SET, FileBasicInformation.DONT_SET, FileTime.ofEpochMillis(modified), FileBasicInformation.DONT_SET, 0L),
        )
        file.rename(path, true)
    }

    /** A small text file (an .acf), whole. */
    fun readText(path: String, max: Int = 1 shl 20): String = openRead(path).use { f ->
        val size = f.fileInformation.standardInformation.endOfFile.coerceAtMost(max.toLong()).toInt()
        val buf = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = f.read(buf, at.toLong(), at, size - at)
            if (n <= 0) break
            at += n
        }
        String(buf, 0, at, Charsets.UTF_8)
    }

    fun mkdirs(path: String) {
        var at = ""
        path.split('\\').filter { it.isNotEmpty() }.forEach { part ->
            at = join(at, part)
            if (!disk.folderExists(at)) disk.mkdir(at)
        }
    }

    fun openRead(path: String): com.hierynomus.smbj.share.File = disk.openFile(
        path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
    )

    /** Opens [path] for writing, keeping what's there (so an interrupted upload can carry on from its end); DELETE lets [finish] rename it. */
    fun openWrite(path: String): com.hierynomus.smbj.share.File = disk.openFile(
        path, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN_IF, null,
    )

    /** The largest read/write the server agreed to (SMB2.1+ servers allow up to 8 MB). */
    val chunk: Int get() = connection.connectionContext.negotiatedProtocol.maxReadSize.coerceIn(64 * 1024, 4 * 1024 * 1024)

    override fun close() {
        runCatching { disk.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        internal const val NO_ENCRYPTION = "SMB_NO_ENCRYPTION"

        /**
         * A name from the server, safe to put in a device path: not empty, not . or .., and no / \ or control
         * characters. A server can send any name it likes; one with ../ in it would land outside the folder picked.
         */
        fun safeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }

        fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent\\$name"

        fun parent(path: String): String = path.substringBeforeLast('\\', "")

        /** Connects and signs in; an empty user signs in as a guest. Throws with the server's reason on failure. */
        fun open(share: NetShare, password: String): SmbLink {
            val (client, connection, session) = signIn(share, password)
            try {
                val disk = session.connectShare(share.share.trim().trim('\\', '/')) as? DiskShare
                    ?: throw IllegalStateException("\\\\${share.host}\\${share.share} is not a folder share")
                return SmbLink(client, connection, session, disk)
            } catch (e: Throwable) {
                runCatching { client.close() }
                throw e
            }
        }

        /** The server's folder shares (no hidden $ ones), sorted: what it would show in a PC's network folder. [share]'s share name isn't used. */
        fun shares(share: NetShare, password: String): List<String> {
            val (client, _, session) = signIn(share, password)
            try {
                return ShareEnum.list(session, share.host.trim().substringBeforeLast(':'))
            } finally {
                runCatching { session.close() }
                runCatching { client.close() }
            }
        }

        private fun signIn(share: NetShare, password: String): Triple<SMBClient, Connection, Session> {
            val config = SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withReadBufferSize(4 * 1024 * 1024)
                .withWriteBufferSize(4 * 1024 * 1024)
                .withTransactBufferSize(4 * 1024 * 1024)
                .withEncryptData(share.encrypt)
                .build()
            val client = SMBClient(config)
            try {
                val (host, port) = share.host.trim().let { h ->
                    val p = h.substringAfterLast(':', "").toIntOrNull()
                    if (p != null && h.count { it == ':' } == 1) h.substringBeforeLast(':') to p else h to 445
                }
                val connection = client.connect(host, port)
                val auth = if (share.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(share.user.trim(), password.toCharArray(), share.domain.trim().ifEmpty { null })
                val session = connection.authenticate(auth)
                if (share.encrypt && !session.shouldEncryptData()) throw IllegalStateException(NO_ENCRYPTION)
                return Triple(client, connection, session)
            } catch (e: Throwable) {
                runCatching { client.close() }
                throw e
            }
        }
    }
}

/** What went wrong, in words a player can act on (the raw smbj/socket message when it's none of the usual ones). */
fun describeNetError(e: Throwable): String {
    val all = generateSequence(e) { it.cause }.toList()
    val msg = all.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.firstOrNull() ?: e.javaClass.simpleName
    return when {
        "STATUS_LOGON_FAILURE" in msg -> "Wrong user name or password"
        "STATUS_BAD_NETWORK_NAME" in msg -> "No share by that name on the server"
        "STATUS_ACCESS_DENIED" in msg -> "The server refused access"
        "STATUS_DISK_FULL" in msg -> "The share is full"
        SmbLink.NO_ENCRYPTION in msg -> "This server can't encrypt (it needs SMB 3). Turn off Encrypt traffic for it, or turn on SMB 3 on the server."
        "STATUS_ACCESS_DENIED" in msg && "encrypt" in msg.lowercase() -> "The server wants encrypted traffic: turn on Encrypt traffic for this share"
        "ENOSPC" in msg || "No space left" in msg -> "This device's storage is full"
        all.any { it is java.net.UnknownHostException } -> "Can't find that server on the network"
        all.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException } ||
            "failed to connect" in msg || "timed out" in msg.lowercase() ->
            "The server didn't answer. Check the address, that it's on, and that SMB is turned on."
        else -> msg
    }
}
