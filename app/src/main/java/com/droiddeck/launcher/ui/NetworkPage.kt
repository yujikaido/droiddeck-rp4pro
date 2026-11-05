package com.droiddeck.launcher.ui

import android.content.Context
import android.text.format.Formatter
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.files.StorageRoots
import com.droiddeck.launcher.net.NetEntry
import com.droiddeck.launcher.net.NetShare
import com.droiddeck.launcher.net.NetShares
import com.droiddeck.launcher.net.NetTransfers
import com.droiddeck.launcher.net.SmbLink
import com.droiddeck.launcher.net.describeNetError
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** One row in either side of the Network page. */
private data class Row2(val key: String, val name: String, val dir: Boolean, val size: Long, val icon: ImageVector? = null, val detail: String? = null)

/**
 * The rail's Network page: a saved SMB share on the left, this device on the right, and the copy queue under them.
 * A opens a folder (or picks a file), X picks, Y copies what's picked (or the row it's on) to the other side, B goes
 * up a folder. Copies run in [NetTransfers], so they carry on when the page is left.
 */
@Composable
internal fun NetworkPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("net_shares", Context.MODE_PRIVATE) }
    var shares by remember { mutableStateOf(NetShares.list(ctx)) }
    var currentId by rememberSaveable { mutableStateOf(prefs.getString("current", null)) }
    val share = shares.firstOrNull { it.id == currentId } ?: shares.firstOrNull()
    var editing by remember { mutableStateOf<NetShare?>(null) }
    val narrow = LocalNarrowPane.current
    // 0 Steam games, 1 Files; L1/R1 flip them wherever focus is (the rail too), as they do Retro's systems
    var tab by rememberSaveable { mutableIntStateOf(prefs.getInt("tab", 0)) }
    val pickTab: (Int) -> Unit = { t -> tab = t; prefs.edit().putInt("tab", t).apply() }
    ShoulderKeys(active = share != null && editing == null) { pickTab((tab + 1) % 2) }

    Column(modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        PageHeader(stringResource(R.string.net_title)) {
            if (share != null) {
                Spacer(Modifier.weight(1f))
                TabStrip(listOf(stringResource(R.string.net_tab_steam), stringResource(R.string.net_tab_files)), tab, pickTab)
            }
        }
        if (share == null) {
            Note(stringResource(R.string.net_intro))
            Spacer(Modifier.height(14.dp))
            PrimaryButton(stringResource(R.string.net_add), icon = Icons.Outlined.Add) { editing = blankShare() }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            ) {
                shares.forEach { sh ->
                    ShareChip(sh.title, sh.id == share.id) {
                        currentId = sh.id
                        prefs.edit().putString("current", sh.id).apply()
                    }
                }
                SecondaryButton(stringResource(R.string.net_edit), compact = true, icon = Icons.Outlined.Edit) { editing = share }
                SecondaryButton(stringResource(R.string.net_add_short), compact = true, icon = Icons.Outlined.Add) { editing = blankShare() }
            }
            if (tab == 0) SteamShelfView(share, s, a, Modifier.weight(1f).fillMaxWidth())
            else Browser(share, Modifier.weight(1f).fillMaxWidth()) { pickTab(0) }
            Transfers(hint = tab == 1)
        }
    }

    editing?.let { e ->
        ShareDialog(
            e,
            isNew = shares.none { it.id == e.id },
            onSaved = { saved ->
                shares = NetShares.list(ctx)
                currentId = saved.id
                prefs.edit().putString("current", saved.id).apply()
            },
            onRemoved = {
                NetShares.remove(ctx, e.id)
                shares = NetShares.list(ctx)
                currentId = shares.firstOrNull()?.id
            },
            onClose = { editing = null },
        )
    }
}

private fun blankShare() = NetShare(NetShares.newId(), "", "", "", "", "")

@Composable
private fun ShareChip(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (on) pal.signal.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f))
            .glideBorder(hot, shape, pal.signal, if (on) pal.signal.copy(alpha = 0.5f) else pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Icon(Icons.Outlined.Dns, null, tint = if (on) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1)
    }
}

/** The two sides. Each keeps its own folder, list and picks; Y on either side copies to the folder open on the other. */
@Composable
private fun Browser(share: NetShare, modifier: Modifier, onSteamFolder: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // one thread per page for the share: SmbLink isn't thread-safe, and the copy worker opens its own
    val io = remember(share.id) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    var link by remember(share.id) { mutableStateOf<SmbLink?>(null) }
    var connecting by remember(share.id) { mutableStateOf(true) }
    var remoteError by remember(share.id) { mutableStateOf<String?>(null) }
    var remotePath by remember(share.id) { mutableStateOf("") }
    var remoteEntries by remember(share.id) { mutableStateOf<List<NetEntry>>(emptyList()) }
    var remotePicked by remember(share.id) { mutableStateOf<Set<String>>(emptySet()) }
    var connectTick by remember(share.id) { mutableIntStateOf(0) }

    var localDir by rememberSaveable { mutableStateOf<String?>(null) }
    var localEntries by remember { mutableStateOf<List<File>>(emptyList()) }
    var localPicked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localTick by remember { mutableIntStateOf(0) }
    // some ROMs add /storage/sdcard0 as a link back to internal storage; show each place once
    val roots = remember { StorageRoots.list(ctx).filter { it.readable }.distinctBy { runCatching { it.dir.canonicalPath }.getOrDefault(it.dir.path) } }

    DisposableEffect(share.id) {
        onDispose {
            val l = link
            scope.launch(io) { runCatching { l?.close() } }.invokeOnCompletion { io.close() }
        }
    }

    fun listRemote(path: String) {
        val l = link ?: return
        scope.launch {
            remoteError = null
            val r = withContext(io) { runCatching { l.list(path) } }
            r.onSuccess { remotePath = path; remoteEntries = it; remotePicked = emptySet() }
                .onFailure { remoteError = describeNetError(it); runCatching { withContext(io) { l.close() } }; link = null }
        }
    }

    LaunchedEffect(share.id, connectTick) {
        connecting = true
        remoteError = null
        val r = withContext(io) { runCatching { SmbLink.open(share, NetShares.password(ctx, share.id)).also { it.list(remotePath) } } }
        connecting = false
        r.onSuccess { link = it; listRemote(remotePath) }.onFailure { remoteError = describeNetError(it) }
    }

    LaunchedEffect(localDir, localTick) {
        val dir = localDir?.let(::File)
        localEntries = if (dir == null) emptyList() else withContext(kotlinx.coroutines.Dispatchers.IO) {
            dir.listFiles().orEmpty().sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
        localPicked = localPicked.filter { p -> localEntries.any { it.path == p } }.toSet()
    }

    // a finished copy changes what's on one side or the other
    val finished = NetTransfers.jobs.count { it.status == NetTransfers.Status.DONE }
    LaunchedEffect(finished) {
        if (finished > 0) {
            localTick++
            if (link != null) listRemote(remotePath)
        }
    }

    fun copyDown(only: NetEntry?) {
        val into = localDir?.let(::File)
        if (into == null) { Toast.makeText(ctx, R.string.net_pick_device_folder, Toast.LENGTH_SHORT).show(); return }
        val picked = remoteEntries.filter { it.path in remotePicked }.ifEmpty { listOfNotNull(only) }
        if (picked.isEmpty()) return
        NetTransfers.download(ctx, share, picked, into)
        remotePicked = emptySet()
    }

    fun copyUp(only: File?) {
        if (link == null) { Toast.makeText(ctx, R.string.net_offline, Toast.LENGTH_SHORT).show(); return }
        val picked = localEntries.filter { it.path in localPicked }.ifEmpty { listOfNotNull(only) }
        if (picked.isEmpty()) return
        NetTransfers.upload(ctx, share, picked, remotePath)
        localPicked = emptySet()
    }

    Column(modifier) {
    // in a steamapps folder: offer it to the Steam games tab (for shares where it isn't at the top or one folder down)
    if (remotePath.substringAfterLast('\\').equals("steamapps", ignoreCase = true)) Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp),
    ) {
        PrimaryButton(stringResource(R.string.net_use_for_steam), compact = true) {
            com.droiddeck.launcher.net.NetSteam.savePath(ctx, share, remotePath)
            onSteamFolder()
        }
    }
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val remoteRows = remoteEntries.map { Row2(it.path, it.name, it.dir, it.size) }
        Side(
            title = share.title,
            path = share.address + if (remotePath.isEmpty()) "" else "\\$remotePath",
            here = remotePath,
            icon = Icons.Outlined.Dns,
            rows = remoteRows,
            picked = remotePicked,
            busy = connecting,
            error = remoteError,
            canGoUp = remotePath.isNotEmpty(),
            copyLabel = stringResource(R.string.net_copy_to_device),
            onRetry = { connectTick++ },
            onUp = { listRemote(SmbLink.parent(remotePath)) },
            onOpen = { row -> listRemote(row.key) },
            onPick = { row -> remotePicked = if (row.key in remotePicked) remotePicked - row.key else remotePicked + row.key },
            onCopy = { row -> copyDown(row?.let { r -> remoteEntries.firstOrNull { it.path == r.key } }) },
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
        val dir = localDir?.let(::File)
        val root = roots.firstOrNull { it.dir.path == localDir }
        val localRows = if (dir == null) roots.map { r ->
            // Android names a card after its volume ID (3132-3731); with just one card, "SD card" says enough
            val label = if (r.removable && roots.count { it.removable } == 1) stringResource(R.string.net_sd_card) else r.label
            Row2(r.dir.path, label, true, 0L, if (r.removable) Icons.Outlined.SdCard else Icons.Outlined.Smartphone, freeSpace(ctx, r.dir))
        } else localEntries.map { Row2(it.path, it.name, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
        Side(
            title = stringResource(R.string.net_device),
            path = dir?.path ?: "",
            here = dir?.path ?: "",
            icon = Icons.Outlined.Smartphone,
            rows = localRows,
            picked = localPicked,
            busy = false,
            error = null,
            canGoUp = dir != null,
            copyLabel = stringResource(R.string.net_copy_to_share),
            onRetry = {},
            onUp = { localDir = if (root != null || dir == null) null else dir.parentFile?.path; localPicked = emptySet() },
            onOpen = { row -> localDir = row.key; localPicked = emptySet() },
            onPick = { row -> if (dir != null) localPicked = if (row.key in localPicked) localPicked - row.key else localPicked + row.key },
            onCopy = { row -> if (dir != null) copyUp(row?.let { File(it.key) }) },
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
    }
}

private fun freeSpace(ctx: Context, dir: File): String? =
    runCatching { ctx.getString(R.string.net_free, Formatter.formatShortFileSize(ctx, dir.usableSpace)) }.getOrNull()

@Composable
private fun Side(
    title: String, path: String, here: String, icon: ImageVector, rows: List<Row2>, picked: Set<String>,
    busy: Boolean, error: String?, canGoUp: Boolean, copyLabel: String,
    onRetry: () -> Unit, onUp: () -> Unit, onOpen: (Row2) -> Unit, onPick: (Row2) -> Unit, onCopy: (Row2?) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    var focusedHere by remember { mutableStateOf(false) }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    val upFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Opening or leaving a folder replaces every row, and the focused one with them; without this the controller's
    // focus falls back to the rail. So it's put on the new list: on the folder just left when going up, else the top.
    var refocus by remember { mutableStateOf<String?>(null) }
    val open: (Row2) -> Unit = { row -> if (focusedHere) refocus = ""; onOpen(row) }
    val up: () -> Unit = { if (focusedHere) refocus = here; onUp() }
    LaunchedEffect(path, rows.size, busy) {
        val target = refocus
        val at = if (target.isNullOrEmpty()) -1 else rows.indexOfFirst { it.key == target }
        listState.scrollToItem(at.coerceAtLeast(0))
        if (target != null && !busy) {
            refocus = null
            focusSoon { rows.getOrNull(at.coerceAtLeast(0))?.let { rowFocus.getOrPut(it.key) { FocusRequester() } } ?: upFocus }
        }
    }
    Column(
        modifier.clip(shape).background(colors.surface).border(1.dp, if (focusedHere) pal.signal.copy(alpha = 0.45f) else pal.line, shape)
            .onFocusChanged { focusedHere = it.hasFocus }
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                if (k.keyCode != KeyEvent.KEYCODE_BUTTON_B || !canGoUp) false
                else { if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) up(); true }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Icon(icon, null, tint = pal.signal, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // the end of a long path is the part that tells folders apart
                if (path.isNotEmpty()) Text(if (path.length > 56) "…" + path.takeLast(55) else path, fontSize = 11.5.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SecondaryButton("", compact = true, enabled = canGoUp, icon = Icons.Outlined.ArrowUpward, modifier = Modifier.focusRequester(upFocus), onClick = up)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.net_connecting), fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(error, fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(bottom = 10.dp))
                    SecondaryButton(stringResource(R.string.net_retry), compact = true, onClick = onRetry)
                }
                rows.isEmpty() -> Text(stringResource(R.string.net_empty_folder), fontSize = 13.sp, color = colors.onSurfaceVariant)
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
                        EntryRow(
                            row, row.key in picked, Modifier.focusRequester(rowFocus.getOrPut(row.key) { FocusRequester() }),
                            onA = { if (row.dir) open(row) else onPick(row) },
                            onX = { onPick(row) },
                            onY = { onCopy(row) },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (picked.isEmpty()) pluralStringResource(R.plurals.fm_items, rows.size, rows.size) else stringResource(R.string.net_selected, picked.size),
                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            SecondaryButton(copyLabel, compact = true, enabled = picked.isNotEmpty()) { onCopy(null) }
        }
    }
}

@Composable
private fun EntryRow(row: Row2, picked: Boolean, extra: Modifier, onA: () -> Unit, onX: () -> Unit, onY: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().then(extra).heightIn(min = 44.dp)
            .background(when { hot -> pal.signal.copy(alpha = 0.26f); picked -> pal.signal.copy(alpha = 0.10f); else -> Color.Transparent })
            .drawBehind { if (hot) drawRect(pal.signal, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                val act: (() -> Unit)? = when (k.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_A -> onA
                    KeyEvent.KEYCODE_BUTTON_X -> onX
                    KeyEvent.KEYCODE_BUTTON_Y -> onY
                    else -> null
                }
                if (act == null) false else { if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) act(); true }
            }
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onA)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            if (picked) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null,
            tint = if (picked) pal.signal else colors.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp).clip(RoundedCornerShape(99.dp)).clickable(onClick = onX),
        )
        Icon(row.icon ?: if (row.dir) Icons.Outlined.Folder else Icons.Outlined.Description, null,
            tint = if (row.dir) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(row.name, fontSize = 13.5.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val detail = row.detail ?: if (!row.dir) Formatter.formatShortFileSize(ctx, row.size) else null
        if (detail != null) Text(detail, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * The copy queue under the two sides: copies still running (or failed, to retry) get a row each, finished ones fold
 * into one line. Height is capped and scrolls, so a long queue never squeezes the games or files out of view.
 */
@Composable
private fun Transfers(hint: Boolean) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val all = NetTransfers.jobs.toList()
    if (all.isEmpty()) {
        if (hint) Text(stringResource(R.string.net_hints), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        return
    }
    val jobs = all.filter { it.status != NetTransfers.Status.DONE }.reversed()
    val done = all.filter { it.status == NetTransfers.Status.DONE }.reversed()
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (done.isNotEmpty() || jobs.any { !it.active }) Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (done.isEmpty()) "" else pluralStringResource(R.plurals.net_done_summary, done.size, done.size, done.joinToString(", ") { it.label }),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            SecondaryButton(stringResource(R.string.net_clear_finished), compact = true) { NetTransfers.clearFinished() }
        }
        jobs.forEach { job ->
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).border(1.dp, pal.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        job.label + "  →  " + job.destination, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sizes = "${Formatter.formatShortFileSize(ctx, job.bytesDone)} / ${Formatter.formatShortFileSize(ctx, job.bytesTotal)}"
                    val line = when (job.status) {
                        NetTransfers.Status.QUEUED -> stringResource(R.string.net_status_queued)
                        NetTransfers.Status.SCANNING -> stringResource(R.string.net_status_scanning)
                        NetTransfers.Status.COPYING -> stringResource(R.string.net_progress, sizes, job.filesDone, job.filesTotal) +
                            (if (job.rate > 0) " · ${Formatter.formatShortFileSize(ctx, job.rate)}/s" else "") +
                            (if (job.current.isNotEmpty()) " · ${job.current}" else "")
                        NetTransfers.Status.RETRYING -> stringResource(R.string.net_status_retrying) + (job.error?.let { " ($it)" } ?: "")
                        NetTransfers.Status.DONE -> stringResource(R.string.net_status_done, pluralStringResource(R.plurals.net_files, job.filesTotal, job.filesTotal), Formatter.formatShortFileSize(ctx, job.bytesTotal))
                        NetTransfers.Status.FAILED -> stringResource(R.string.net_status_failed, job.error ?: "")
                        NetTransfers.Status.CANCELLED -> stringResource(R.string.net_status_cancelled)
                    }
                    Text(line, fontSize = 12.sp, color = if (job.status == NetTransfers.Status.FAILED) colors.error else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (job.active) LinearProgressIndicator(
                        progress = { job.fraction }, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(99.dp)),
                    )
                }
                when {
                    job.active -> SecondaryButton(stringResource(R.string.net_cancel), compact = true) { NetTransfers.cancel(job) }
                    job.status == NetTransfers.Status.FAILED || job.status == NetTransfers.Status.CANCELLED ->
                        SecondaryButton(stringResource(R.string.net_retry), compact = true) { NetTransfers.retry(ctx, job) }
                }
            }
        }
    }
}

/** Add or edit a share. Connect and save signs in and lists the share before saving, so a typo shows here. */
@Composable
private fun ShareDialog(share: NetShare, isNew: Boolean, onSaved: (NetShare) -> Unit, onRemoved: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    var name by remember { mutableStateOf(share.name) }
    var host by remember { mutableStateOf(share.host) }
    var shareName by remember { mutableStateOf(share.share) }
    var user by remember { mutableStateOf(share.user) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    AppDialog(shown, close, "netShare", wide = false, maxWidth = 640.dp) {
        DialogTitleRow(stringResource(if (isNew) R.string.net_add else R.string.net_edit), onClose = close)
        // two to a row, so Connect and save stays on a 16:9 handheld's screen without scrolling
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(host, { host = it }, singleLine = true, label = { Text(stringResource(R.string.net_field_host)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
            OutlinedTextField(shareName, { shareName = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false), label = { Text(stringResource(R.string.net_field_share)) }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            // no autocorrect or capitals: a keyboard "fixing" a user name is a sign-in failure that looks like a wrong password
            OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.net_field_user)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f))
            OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(stringResource(R.string.net_field_password)) },
                placeholder = if (isNew) null else ({ Text(stringResource(R.string.net_password_kept)) }),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    SmallIconButton(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(R.string.net_show_password), MaterialTheme.colorScheme.onSurfaceVariant) { showPassword = !showPassword }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.weight(1f))
        }
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.net_field_name)) }, modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error!!, fontSize = 13.sp, color = colors.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (!isNew) SecondaryButton(stringResource(R.string.net_remove), compact = true) { onRemoved(); close() }
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (testing) R.string.net_connecting else R.string.net_test_save),
                enabled = !testing && host.isNotBlank() && shareName.isNotBlank(), compact = true,
            ) {
                val candidate = NetShare(share.id, name.trim(), host.trim(), shareName.trim().trim('\\', '/'), user.trim(), share.domain)
                val pw = if (password.isEmpty() && !isNew) NetShares.password(ctx, share.id) else password
                testing = true
                error = null
                scope.launch {
                    val r = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { SmbLink.open(candidate, pw).use { it.list("") } } }
                    testing = false
                    r.onSuccess {
                        NetShares.save(ctx, candidate, if (password.isEmpty() && !isNew) null else password)
                        onSaved(candidate)
                        close()
                    }.onFailure { error = describeNetError(it) }
                }
            }
        }
    }
}
