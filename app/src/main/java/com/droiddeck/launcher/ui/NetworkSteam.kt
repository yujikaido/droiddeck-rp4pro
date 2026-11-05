package com.droiddeck.launcher.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.net.NetShare
import com.droiddeck.launcher.net.NetShares
import com.droiddeck.launcher.net.NetSteam
import com.droiddeck.launcher.net.NetSteamGame
import com.droiddeck.launcher.net.NetSteamShelf
import com.droiddeck.launcher.net.NetTransfers
import com.droiddeck.launcher.net.SmbLink
import com.droiddeck.launcher.net.describeNetError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** One cover on the Steam games tab: a game on the share, on this device, or both. [localBuild]: the device .acf's buildid. */
private class SteamCard(
    val appId: Int, val name: String, val net: NetSteamGame?, val local: Library.SteamGame?, val localBuild: Long = 0L, val localSize: Long = 0L,
) {
    val size: Long get() = net?.size?.takeIf { it > 0L } ?: localSize
    private val both: Boolean get() = net != null && local != null && net.build > 0L && localBuild > 0L
    /** The share has a newer build than the device (updated on the PC). */
    val updateHere: Boolean get() = both && net!!.build > localBuild
    /** The device has a newer build than the share (updated here). */
    val backupOld: Boolean get() = both && localBuild > net!!.build
    /** The device library the game is in (the folder holding steamapps). */
    val localRoot: File? get() = local?.gameFiles?.parentFile?.parentFile?.parentFile
    val localAcf: File? get() = local?.gameFiles?.parentFile?.parentFile?.let { File(it, "appmanifest_$appId.acf") }
}

/** The library chosen in Settings for new installs (its folder's path), or null for the card's when there is one. */
internal fun steamTargetPref(ctx: android.content.Context): String? =
    ctx.getSharedPreferences("net_shares", android.content.Context.MODE_PRIVATE).getString("steam_target", null)

/** The device libraries a game can go into, named as the Network page shows them. */
internal fun steamTargets(ctx: android.content.Context): List<SteamTarget> =
    Library.steamLibraries(ctx).filter { (root, _) -> root.isDirectory }
        .map { (root, label) -> SteamTarget(root, if (label == "internal") ctx.getString(R.string.net_builtin) else label) }

/** A device library a game can go into: Steam's own on built-in storage, or the card's. */
internal class SteamTarget(val root: File, val label: String) {
    val free: Long get() = runCatching { (if (root.isDirectory) root else root.parentFile ?: root).usableSpace }.getOrDefault(0L)
}

/**
 * The Network page's Steam games tab: the covers of the games in the share's steamapps folder, then the games on this
 * device that aren't on the share yet. A game opens a page to install it from the share (into internal storage or the
 * card) or back it up to the share. Both copy the game's common\ folder first and its .acf last.
 */
@Composable
internal fun SteamShelfView(share: NetShare, s: FrontEndState, a: FrontEndActions, onEdit: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val io = remember(share.id) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
    var link by remember(share.id) { mutableStateOf<SmbLink?>(null) }
    var shelf by remember(share.id) { mutableStateOf<NetSteamShelf?>(null) }
    var busy by remember(share.id) { mutableStateOf(true) }
    var error by remember(share.id) { mutableStateOf<String?>(null) }
    var missing by remember(share.id) { mutableStateOf(false) }
    var tick by remember(share.id) { mutableIntStateOf(0) }
    var local by remember { mutableStateOf<List<Library.SteamGame>>(emptyList()) }
    var localBuilds by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var localSizes by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var localTick by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<SteamCard?>(null) }

    DisposableEffect(share.id) {
        onDispose {
            val l = link
            scope.launch(io) { runCatching { l?.close() } }.invokeOnCompletion { io.close() }
        }
    }

    LaunchedEffect(share.id, tick) {
        busy = true
        error = null
        missing = false
        val r = withContext(io) {
            // the connection kept from the last scan may have been dropped by the server while idle: on any failure
            // with it, scan once more on a new one before calling the share unreachable
            fun scan(l: SmbLink): NetSteamShelf? {
                val path = NetSteam.savedPath(ctx, share)?.takeIf { p -> runCatching { l.list(p) }.isSuccess } ?: NetSteam.find(l)
                return path?.let { NetSteam.savePath(ctx, share, it); NetSteam.scan(l, it) }
            }
            val kept = link
            runCatching { scan(kept ?: throw IllegalStateException()) }.recoverCatching {
                runCatching { kept?.close() }
                link = null
                val fresh = SmbLink.open(share, NetShares.password(ctx, share.id))
                link = fresh
                scan(fresh)
            }
        }
        busy = false
        r.onSuccess { found -> shelf = found; missing = found == null }
            .onFailure { e -> error = describeNetError(e); runCatching { withContext(io) { link?.close() } }; link = null }
    }

    LaunchedEffect(localTick) {
        val games = withContext(Dispatchers.IO) { runCatching { Library.steamGames(ctx) }.getOrDefault(emptyList()) }
        val acfs = withContext(Dispatchers.IO) {
            games.mapNotNull { g ->
                val acf = g.gameFiles?.parentFile?.parentFile?.let { File(it, "appmanifest_${g.appId}.acf") } ?: return@mapNotNull null
                runCatching { g.appId to acf.readText() }.getOrNull()
            }.toMap()
        }
        localBuilds = acfs.mapValues { NetSteam.buildOf(it.value) }
        localSizes = acfs.mapValues { NetSteam.sizeOf(it.value) }
        local = games
    }

    // a finished install adds a game to the device (and to the Games page); a finished backup adds one to the share
    val finishedSteam = NetTransfers.jobs.count { it.appId != null && it.status == NetTransfers.Status.DONE }
    LaunchedEffect(finishedSteam) {
        if (finishedSteam > 0) {
            localTick++
            tick++
            a.onLibraryChanged()
        }
    }

    val localById = local.filter { it.library != Library.ADDED }.associateBy { it.appId }
    val onShare = shelf?.games.orEmpty().map { SteamCard(it.appId, it.name, it, localById[it.appId], localBuilds[it.appId] ?: 0L, localSizes[it.appId] ?: 0L) }
    val shareIds = onShare.map { it.appId }.toSet()
    val onlyHere = localById.values.filter { it.appId !in shareIds }.sortedBy { it.name.lowercase() }
        .map { SteamCard(it.appId, it.name, null, it, localBuilds[it.appId] ?: 0L, localSizes[it.appId] ?: 0L) }
    val targets = remember(localTick) { steamTargets(ctx) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(
                shelf?.let { share.address + "\\" + it.steamapps } ?: share.address,
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            SecondaryButton(stringResource(R.string.net_rescan), compact = true, icon = Icons.Outlined.Refresh) { tick++; localTick++ }
        }
        when {
            busy && shelf == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = LocalPalette.current.signal, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.net_steam_looking), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            error != null -> Unreachable(share.title, error!!, onRetry = { tick++ }, onEdit = onEdit, modifier = Modifier.weight(1f).fillMaxWidth())
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (missing) item(span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.net_steam_missing, share.address)) }
                if (shelf != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.net_steam_on_share, share.title), onShare.size.toString()) }
                    if (onShare.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Note(stringResource(R.string.net_steam_share_empty)) }
                    items(onShare, key = { "n${it.appId}" }) { card -> Cover(card) { open = card } }
                }
                if (onlyHere.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.net_steam_only_here), onlyHere.size.toString()) }
                    items(onlyHere, key = { "l${it.appId}" }) { card -> Cover(card) { open = card } }
                }
            }
        }
    }

    open?.let { card ->
        SteamGameDialog(card, share, shelf, targets, s.sessionRunning, onClose = { open = null })
    }
}

@Composable
private fun Cover(card: SteamCard, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val scale by animateFloatAsState(if (hot) 1.04f else 1f, Motion.sp(0.55f), label = "coverScale")
    val job = NetTransfers.jobs.lastOrNull { it.appId == card.appId && it.active }
    Column(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (hot) 14.dp.toPx() else 2.dp.toPx(); shape = Shape12; clip = false }
            .clip(Shape12).background(colors.surface)
            .glideBorder(hot, Shape12, pal.signal, hotWidth = 2.5.dp)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(artBrush(hueOf(card.name)))) {
            CoverArt(card, Modifier.fillMaxSize())
            if (job != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (job.bytesTotal > 0) CircularProgressIndicator(progress = { job.fraction }, strokeWidth = 3.dp, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.25f), modifier = Modifier.size(40.dp))
                    else CircularProgressIndicator(strokeWidth = 3.dp, color = pal.signal, modifier = Modifier.size(40.dp))
                    Text(if (job.bytesTotal > 0) "${(job.fraction * 100).toInt()}%" else stringResource(R.string.net_status_queued),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                }
            }
            // on both sides: installed here and backed up there (an arrow when one side has a newer build)
            if (job == null && card.net != null && card.local != null) Icon(
                when {
                    card.updateHere -> Icons.Outlined.Downloading
                    card.backupOld -> Icons.Outlined.CloudUpload
                    else -> Icons.Outlined.CheckCircle
                },
                stringResource(R.string.net_steam_installed), tint = if (card.updateHere || card.backupOld) pal.signal else pal.good,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(2.dp),
            )
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(card.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    card.updateHere -> stringResource(R.string.net_steam_update_available)
                    card.backupOld -> stringResource(R.string.net_steam_backup_old)
                    card.net != null && card.local != null -> stringResource(R.string.net_steam_installed)
                    card.net != null -> if (card.size > 0) Formatter.formatShortFileSize(ctx, card.size) else stringResource(R.string.net_steam_on_share_short)
                    else -> stringResource(R.string.net_steam_not_backed_up)
                },
                fontSize = 11.sp, maxLines = 1,
                color = when {
                    card.updateHere || card.backupOld -> pal.signal
                    card.net != null && card.local != null -> pal.good
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

/** Steam's cached poster when the game is on this device; otherwise Steam's CDN, then its wide header, then just the name. */
@Composable
private fun CoverArt(card: SteamCard, modifier: Modifier) {
    val localArt = card.local?.art
    if (localArt != null) {
        CoverImage(localArt, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = NetSteam.posterUrl(card.appId), contentDescription = card.name, contentScale = ContentScale.Crop, modifier = modifier,
        error = {
            SubcomposeAsyncImage(
                model = NetSteam.headerUrl(card.appId), contentDescription = card.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                error = { CoverName(card.name) },
            )
        },
    )
}

@Composable
private fun CoverName(name: String) = Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomStart) {
    Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SteamGameDialog(card: SteamCard, share: NetShare, shelf: NetSteamShelf?, targets: List<SteamTarget>, steamRunning: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    var target by remember {
        val chosen = steamTargetPref(ctx)
        mutableIntStateOf(targets.indexOfFirst { it.root.path == chosen }.takeIf { it >= 0 }
            ?: targets.indexOfLast { it.label != ctx.getString(R.string.net_builtin) }.coerceAtLeast(0))
    }
    val job = NetTransfers.jobs.lastOrNull { it.appId == card.appId && it.active }
    AppDialog(shown, close, "netSteamGame", wide = true, maxWidth = 760.dp) {
        DialogTitleRow(card.name, onClose = close)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(150.dp).aspectRatio(2f / 3f).clip(Shape12).background(artBrush(hueOf(card.name)))) { CoverArt(card, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    listOfNotNull("App ${card.appId}", card.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(ctx, it) }).joinToString("  ·  "),
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
                Actions {
                    if (card.net != null) ActionChip(stringResource(R.string.net_steam_on_share_named, share.title), ok = true)
                    if (card.local != null) ActionChip(stringResource(R.string.net_steam_on_device_named, card.local.library.let { if (it == "internal") stringResource(R.string.net_builtin) else it }), ok = true)
                }
                if (card.net != null && card.local == null) {
                    if (card.net.dir == null) Note(stringResource(R.string.net_steam_no_folder, card.net.installDir))
                    else if (targets.isEmpty()) Note(stringResource(R.string.net_steam_no_library))
                    else {
                        Text(stringResource(R.string.net_steam_install_to), fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Actions {
                            targets.forEachIndexed { i, t ->
                                ShareChoice("${t.label} · ${stringResource(R.string.net_free, Formatter.formatShortFileSize(ctx, t.free))}", i == target) { target = i }
                            }
                        }
                        val t = targets.getOrNull(target)
                        val fits = t != null && (card.size <= 0 || card.size < t.free)
                        if (!fits) Text(stringResource(R.string.net_steam_no_space), fontSize = 13.sp, color = colors.error)
                        PrimaryButton(stringResource(R.string.net_steam_install, share.title), enabled = fits && job == null && t != null, main = false) {
                            NetTransfers.installSteamGame(ctx, share, card.net, t!!.root, t.label)
                            close()
                        }
                    }
                }
                // a newer build on the share: copy it over the installed one, into the same library
                if (card.updateHere && card.net != null && card.net.dir != null && card.local != null) {
                    Text(stringResource(R.string.net_steam_update_note, share.title, card.net.build, card.localBuild), fontSize = 13.sp, color = colors.onSurfaceVariant)
                    if (steamRunning) Text(stringResource(R.string.net_steam_close_first), fontSize = 13.sp, color = colors.error)
                    val root = card.localRoot
                    PrimaryButton(stringResource(R.string.net_steam_install_update, share.title), enabled = job == null && !steamRunning && root != null, main = false) {
                        NetTransfers.installSteamGame(ctx, share, card.net, root!!, card.local.library.let { if (it == "internal") ctx.getString(R.string.net_builtin) else it })
                        close()
                    }
                }
                if (card.backupOld) Text(stringResource(R.string.net_steam_backup_old_note, share.title, card.localBuild, card.net!!.build), fontSize = 13.sp, color = colors.onSurfaceVariant)
                // not offered when the share's copy is newer: backing up would put the older build over it
                if (card.local != null && !card.updateHere) {
                    val files = card.local.gameFiles
                    val acf = card.localAcf
                    if (shelf == null) Note(stringResource(R.string.net_steam_missing, share.address))
                    else if (files == null || acf?.isFile != true) Note(stringResource(R.string.net_steam_cant_back_up))
                    else SecondaryButton(
                        stringResource(if (card.net != null) R.string.net_steam_update_backup else R.string.net_steam_back_up, share.title),
                        enabled = job == null,
                    ) {
                        NetTransfers.backUpSteamGame(ctx, share, card.appId, card.name, files, acf, shelf.steamapps)
                        close()
                    }
                }
                if (job != null) Text(stringResource(R.string.net_steam_copying), fontSize = 13.sp, color = pal.signal)
                if (steamRunning && card.local == null) Text(stringResource(R.string.net_steam_restart_note), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ShareChoice(label: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (on) pal.onSignal else colors.onBackground,
        modifier = Modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (on) pal.signal else Color.White.copy(alpha = 0.03f))
            .border(1.dp, if (on) pal.signal else pal.line2, shape)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
