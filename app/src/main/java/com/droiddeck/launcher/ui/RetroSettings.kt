package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.RetroLibrary
import kotlinx.coroutines.launch

/**
 * Retro mode's Settings tab, laid out as Daijishō's: a breadcrumb, rows with an icon, a title and the current value
 * under it, grouped under small headers. A row opens its page (Emulators, ROM folders, Wallpapers) and B comes back.
 */
@Composable
internal fun RetroSettingsPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember { HashMap<String, FocusRequester>() }
    val rowFor = { key: String -> rows.getOrPut(key) { FocusRequester() } }
    val first = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var listDefault by remember { mutableStateOf(RetroLibrary.listView(context)) }
    var choosingView by remember { mutableStateOf(false) }
    var coverDefault by remember { mutableStateOf(RetroLibrary.listCover(context)) }
    var choosingPicture by remember { mutableStateOf(false) }
    BackHandler(enabled = page != null) {
        val from = page ?: return@BackHandler
        page = null
        scope.launch { focusSoon { rowFor(from) } }
    }
    LaunchedEffect(page) { if (page != null) focusSoon { first } }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 26.dp, end = 22.dp, top = 6.dp, bottom = 8.dp)) {
            Icon(Icons.Outlined.Settings, null, tint = colors.onBackground, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.retro_settings), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(start = 10.dp))
            page?.let { Text(stringResource(sectionLabel(it)), fontSize = 18.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp)) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(LocalPalette.current.line))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp)) {
            val open = page
            if (open != null) RetroSettingsSection(s, a, open, first)
            else {
                SettingsHeader(stringResource(R.string.retro_library))
                SettingRow(Icons.Outlined.VideogameAsset, stringResource(R.string.retro_emulators), stringResource(R.string.retro_systems_count, s.retro.size), rowFor("emulators")) { page = "emulators" }
                SettingRow(Icons.Outlined.FolderOpen, stringResource(R.string.retro_paths_title), foldersLine(s), rowFor("paths")) { page = "paths" }
                SettingsHeader(stringResource(R.string.retro_section_defaults))
                val views = listOf(stringResource(R.string.retro_view_list), stringResource(R.string.retro_view_grid))
                SettingRow(
                    if (listDefault) Icons.Outlined.ViewList else Icons.Outlined.GridView, stringResource(R.string.retro_game_view),
                    views[if (listDefault) 0 else 1], rowFor("view"),
                ) { choosingView = true }
                if (choosingView) RetroChoiceDialog(stringResource(R.string.retro_game_view), views, if (listDefault) 0 else 1, onPick = { i ->
                    listDefault = i == 0
                    RetroLibrary.setListView(context, listDefault)
                }, onDismiss = { choosingView = false })
                val pictures = listOf(stringResource(R.string.retro_picture_snap), stringResource(R.string.retro_picture_cover))
                SettingRow(Icons.Outlined.Image, stringResource(R.string.retro_list_picture), pictures[if (coverDefault) 1 else 0], rowFor("picture")) { choosingPicture = true }
                if (choosingPicture) RetroChoiceDialog(stringResource(R.string.retro_list_picture), pictures, if (coverDefault) 1 else 0, onPick = { i ->
                    coverDefault = i == 1
                    RetroLibrary.setListCover(context, coverDefault)
                }, onDismiss = { choosingPicture = false })
                SettingsHeader(stringResource(R.string.retro_section_appearance))
                SettingRow(Icons.Outlined.Wallpaper, stringResource(R.string.retro_wallpapers), s.retroPack ?: stringResource(R.string.retro_wallpapers_none), rowFor("wallpapers")) { page = "wallpapers" }
                SettingsHeader(stringResource(R.string.retro_section_about))
                SettingRow(Icons.Outlined.Info, "Daijishō", stringResource(R.string.retro_credit), null, onClick = null)
            }
        }
        KeysBar(stringResource(if (page == null) R.string.retro_settings_keys else R.string.retro_gallery_keys))
    }
}

@Composable
private fun foldersLine(s: FrontEndState): String = when (s.retroRoots.size) {
    0 -> stringResource(R.string.retro_folders_none)
    1 -> s.retroRoots[0]
    else -> stringResource(R.string.retro_folders_count, s.retroRoots.size)
}

/** One settings page's rows: each system's emulator, the ROM folders, or the wallpaper pack ([first] goes on its first row). */
@Composable
internal fun RetroSettingsSection(s: FrontEndState, a: FrontEndActions, section: String, first: FocusRequester? = null) {
    val colors = MaterialTheme.colorScheme
    when (section) {
        "emulators" -> {
            var choosing by remember { mutableStateOf<RetroLibrary.System?>(null) }
            if (s.retro.isEmpty()) Note(stringResource(R.string.retro_empty))
            s.retro.forEachIndexed { i, sys ->
                SettingRow(Icons.Outlined.VideogameAsset, sys.platform.name, sys.player?.label ?: stringResource(R.string.retro_no_emulator), if (i == 0) first else null) { choosing = sys }
            }
            choosing?.let { sys -> RetroEmulatorDialog(s.retro.firstOrNull { it.platform.id == sys.platform.id } ?: sys, a) { choosing = null } }
        }
        "paths" -> {
            var removing by remember { mutableStateOf<String?>(null) }
            Text(stringResource(R.string.retro_paths_hint), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            SettingRow(Icons.Filled.Add, stringResource(R.string.retro_add_folder), null, first) { a.onRetroAddRoot() }
            s.retroRoots.forEach { path ->
                SettingRow(Icons.Outlined.Folder, path, stringResource(R.string.retro_folder_hint), null) { removing = path }
            }
            removing?.let { path ->
                ConfirmDialog(
                    stringResource(R.string.retro_remove_folder_title), stringResource(R.string.retro_remove_folder_text, path),
                    stringResource(R.string.retro_remove), onConfirm = { a.onRetroRemoveRoot(path) }, onDismiss = { removing = null },
                )
            }
        }
        else -> {
            LaunchedEffect(Unit) { a.onRetroWallpaperList() }
            Text(stringResource(R.string.retro_wallpapers_hint), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            s.retroPackBusy?.let { Text(stringResource(R.string.retro_wallpapers_busy, it), fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
            (listOf<String?>(null) + s.retroPacks).forEachIndexed { i, name ->
                SettingRow(null, name ?: stringResource(R.string.retro_wallpapers_none), null, if (i == 0) first else null, selected = name == s.retroPack) {
                    if (s.retroPackBusy == null) a.onRetroWallpaperPack(name)
                }
            }
        }
    }
}

/** A system's emulator as a pop-up list, the way Retroid's own settings ask: up and down to pick, A to choose, B to close. */
@Composable
internal fun RetroEmulatorDialog(sys: RetroLibrary.System, a: FrontEndActions, onDismiss: () -> Unit) {
    val players = sys.players
    val chosen = players.indexOf(sys.player).coerceAtLeast(0)
    RetroChoiceDialog(
        sys.platform.name, players.map { it.label }, chosen,
        before = if (players.isEmpty()) stringResource(R.string.retro_no_player_hint, sys.platform.name,
            sys.platform.players.map { it.label }.filter { it.isNotBlank() }.distinct().take(6).joinToString(", "))
            // DroidDeck can't see which cores RetroArch has; one it lacks plays the game as a black screen
            else if (players.any { it.core != null }) stringResource(R.string.retro_core_hint) else null,
        after = stringResource(R.string.retro_paths_title) + ": " + sys.folders.joinToString(", ") { it.path },
        onPick = { i -> if (i != chosen) a.onRetroPlayer(sys, players[i]) }, onDismiss = onDismiss,
    )
}

/** A pop-up list of choices with the current one marked; picking one closes it. [before] and [after] are notes around the list. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun RetroChoiceDialog(
    title: String, options: List<String>, chosen: Int, before: String? = null, after: String? = null,
    onPick: (Int) -> Unit, onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember(options.size) { List(options.size) { FocusRequester() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            // the pop-up is its own window, which starts in touch mode: switch it to the D-pad before focusing the choice
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            LaunchedEffect(Unit) {
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                if (focus.isNotEmpty()) focusSoon { focus[chosen.coerceIn(0, focus.lastIndex)] }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (before != null) Text(before, fontSize = 14.sp, color = colors.onSurfaceVariant)
                options.forEachIndexed { i, label ->
                    SettingRow(null, label, null, focus[i], selected = i == chosen) { onPick(i); onDismiss() }
                }
                if (after != null) Text(after, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun SettingsHeader(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.signal, modifier = Modifier.padding(start = 52.dp, top = 14.dp, bottom = 2.dp))
}

/**
 * A settings row: icon, title and the value under it, lit while focused. [selected] non-null draws a radio mark on the
 * right (a choice in a list); [onClick] null makes it plain text that focus skips.
 */
@Composable
private fun SettingRow(
    icon: ImageVector?, title: String, summary: String?, focus: FocusRequester?,
    selected: Boolean? = null, onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(if (focused) colors.onBackground.copy(alpha = 0.13f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.onBackground, modifier = Modifier.size(24.dp))
        else if (selected == null) Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).padding(start = if (icon != null || selected == null) 16.dp else 4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(start = 12.dp).size(22.dp).border(2.dp, if (selected) pal.signal else colors.onSurfaceVariant, CircleShape),
        ) { if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(pal.signal)) }
    }
}
