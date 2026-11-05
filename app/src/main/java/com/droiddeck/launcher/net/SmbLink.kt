package com.droiddeck.launcher.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
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

    /** Size of a file, or -1 if it isn't there. */
    fun sizeOf(path: String): Long =
        if (!disk.fileExists(path)) -1L else disk.getFileInformation(path).standardInformation.endOfFile

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

    /** Opens [path] for writing, keeping what's there (so an interrupted upload can carry on from its end). */
    fun openWrite(path: String): com.hierynomus.smbj.share.File = disk.openFile(
        path, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN_IF, null,
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
        fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent\\$name"

        fun parent(path: String): String = path.substringBeforeLast('\\', "")

        /** Connects and signs in; an empty user signs in as a guest. Throws with the server's reason on failure. */
        fun open(share: NetShare, password: String): SmbLink {
            val config = SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .withReadBufferSize(4 * 1024 * 1024)
                .withWriteBufferSize(4 * 1024 * 1024)
                .withTransactBufferSize(4 * 1024 * 1024)
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
                val disk = session.connectShare(share.share.trim().trim('\\', '/')) as? DiskShare
                    ?: throw IllegalStateException("\\\\$host\\${share.share} is not a folder share")
                return SmbLink(client, connection, session, disk)
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
        "ENOSPC" in msg || "No space left" in msg -> "This device's storage is full"
        all.any { it is java.net.UnknownHostException } -> "Can't find that server on the network"
        all.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException } ||
            "failed to connect" in msg || "timed out" in msg.lowercase() ->
            "The server didn't answer. Check the address, that it's on, and that SMB is turned on."
        else -> msg
    }
}
