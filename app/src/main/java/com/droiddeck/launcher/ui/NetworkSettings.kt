package com.droiddeck.launcher.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.net.NetShare
import com.droiddeck.launcher.net.NetSteam
import com.droiddeck.launcher.net.NetTransfers

/**
 * The Network page's Settings tab: the saved shares (each opens the share dialog, which holds its sign-in, Save
 * password and Encrypt traffic), how copies run, where Steam games install, which folder on the share holds them,
 * and a few words on keeping the NAS safe.
 */
@Composable
internal fun NetworkSettings(
    shares: List<NetShare>, current: NetShare, onEdit: (NetShare) -> Unit, onAdd: () -> Unit, onPickInFiles: () -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val host = rememberMenuHost()
    val prefs = remember { ctx.getSharedPreferences("net_shares", Context.MODE_PRIVATE) }
    var lanes by remember { mutableIntStateOf(NetTransfers.lanes(ctx)) }
    val targets = remember { steamTargets(ctx) }
    var target by remember { mutableStateOf(steamTargetPref(ctx) ?: targets.lastOrNull()?.root?.path) }
    var steamTick by remember { mutableIntStateOf(0) }
    val steamPath = remember(current.id, steamTick) { NetSteam.savedPath(ctx, current) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        SettingsGroup(stringResource(R.string.net_set_shares), compact = true) {
            shares.forEach { sh ->
                val notes = listOfNotNull(
                    sh.user.ifBlank { stringResource(R.string.net_set_guest) },
                    stringResource(R.string.net_set_encrypted).takeIf { sh.encrypt },
                    stringResource(R.string.net_set_ask).takeIf { !sh.savePassword && sh.user.isNotBlank() },
                )
                SettingsRow(sh.title, sh.address + "  ·  " + notes.joinToString("  ·  ")) {
                    SecondaryButton(stringResource(R.string.net_edit), compact = true, icon = Icons.Outlined.Edit) { onEdit(sh) }
                }
            }
            SettingsRow(stringResource(R.string.net_add), null) {
                SecondaryButton(stringResource(R.string.net_add_short), compact = true, icon = Icons.Outlined.Add, onClick = onAdd)
            }
        }
        SettingsGroup(stringResource(R.string.net_set_transfers), compact = true) {
            SettingsRow(stringResource(R.string.net_set_lanes), stringResource(R.string.net_set_lanes_hint)) {
                SegmentedTabs(listOf(1 to "1", 2 to "2", 4 to "4", 8 to "8"), lanes) { n -> lanes = n; NetTransfers.setLanes(ctx, n) }
            }
            if (targets.size > 1) ChoiceRow(
                host, "netSteamTarget", stringResource(R.string.net_set_target), stringResource(R.string.net_set_target_hint),
                targets.map { t -> t.root.path to "${t.label} · ${stringResource(R.string.net_free, Formatter.formatShortFileSize(ctx, t.free))}" },
                target ?: "",
            ) { path -> target = path; prefs.edit().putString("steam_target", path).apply() }
        }
        SettingsGroup(stringResource(R.string.net_set_steam, current.title), compact = true) {
            SettingsRow(steamPath?.let { current.address + "\\" + it } ?: stringResource(R.string.net_set_steam_none), null, stacked = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(stringResource(R.string.net_set_find_again), compact = true, enabled = steamPath != null) {
                        NetSteam.forgetPath(ctx, current)
                        steamTick++
                    }
                    SecondaryButton(stringResource(R.string.net_set_pick_in_files), compact = true, onClick = onPickInFiles)
                }
            }
        }
        SettingsGroup(stringResource(R.string.net_set_safety), compact = true) {
            Text(
                stringResource(R.string.net_set_safety_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
