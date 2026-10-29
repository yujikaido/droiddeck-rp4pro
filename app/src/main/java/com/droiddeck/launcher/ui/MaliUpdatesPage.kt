package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.update.MaliReleases

class MaliUpdateState(
    val installedTag: String,
    val enabled: Boolean,
    val wifiOnly: Boolean,
    val checking: Boolean,
    val checkedAt: Long,
    val release: MaliReleases.Release?,
    val standing: MaliReleases.Standing?,
    val phase: Phase,
    val percent: Int,
    val error: String?,
    val askPermission: Boolean,
    val bannerDismissed: Boolean,
) {
    enum class Phase(val busy: Boolean) { IDLE(false), WAITING_WIFI(false), DOWNLOADING(true), READY(false), INSTALLING(true), CONFIRM(true) }

    val hasUpdate: Boolean get() = standing == MaliReleases.Standing.NEWER
    val ready: Boolean get() = phase == Phase.READY && release != null
}

class MaliUpdateActions(
    val onCheck: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onAllowInstalls: () -> Unit = {},
    val onDismissPermission: () -> Unit = {},
    val onDismissBanner: () -> Unit = {},
)

@Composable
internal fun MaliUpdatesPage(s: FrontEndState, m: MaliUpdateState, ma: MaliUpdateActions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(modifier = modifier) {
        PageHeader(stringResource(R.string.upd_title)) {
            Text(
                stringResource(R.string.mali_upd_installed, m.installedTag.ifBlank { "?" }), fontSize = 12.5.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            if (!LocalNarrowPane.current) Text(
                when {
                    m.checking -> stringResource(R.string.common_checking)
                    m.checkedAt > 0 -> stringResource(R.string.upd_checked, ago(m.checkedAt).replaceFirstChar { it.lowercase() })
                    else -> stringResource(R.string.upd_not_checked)
                },
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.store_check_updates), busy = m.checking, enabled = !m.checking && !m.phase.busy, onClick = ma.onCheck)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MaliStatus(s, m, ma)
            Column(
                Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(vertical = 4.dp),
            ) {
                SettingsRow(stringResource(R.string.mali_upd_auto), stringResource(R.string.mali_upd_auto_hint)) {
                    ToggleSwitch(m.enabled, label = stringResource(R.string.mali_upd_auto)) { ma.onEnabled(it) }
                }
                SettingsRow(stringResource(R.string.mali_upd_wifi), stringResource(R.string.mali_upd_wifi_hint)) {
                    ToggleSwitch(m.wifiOnly, label = stringResource(R.string.mali_upd_wifi)) { ma.onWifiOnly(it) }
                }
            }
            Text(stringResource(R.string.mali_upd_source), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    if (m.askPermission) AlertDialog(
        onDismissRequest = ma.onDismissPermission,
        title = { Text(stringResource(R.string.upd_perm_title)) },
        text = { Text(stringResource(R.string.upd_perm_text)) },
        confirmButton = { TextButton(onClick = ma.onAllowInstalls) { Text(stringResource(R.string.upd_open_settings)) } },
        dismissButton = { TextButton(onClick = ma.onDismissPermission) { Text(stringResource(R.string.upd_not_now)) } },
    )
}

@Composable
private fun MaliStatus(s: FrontEndState, m: MaliUpdateState, ma: MaliUpdateActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val r = m.release
    val (tint, status) = when {
        m.ready -> pal.good to stringResource(R.string.mali_upd_ready_status)
        m.standing == MaliReleases.Standing.NEWER -> AttentionAmber to stringResource(R.string.mali_upd_available_status)
        m.standing == MaliReleases.Standing.CURRENT -> pal.good to stringResource(R.string.mali_upd_current_status)
        r != null -> colors.onSurfaceVariant to stringResource(R.string.mali_upd_latest_status)
        m.checking -> colors.onSurfaceVariant to stringResource(R.string.common_checking)
        else -> colors.onSurfaceVariant to stringResource(R.string.upd_not_checked)
    }
    val offered = r != null && (m.standing == MaliReleases.Standing.NEWER || m.standing == MaliReleases.Standing.UNKNOWN)
    Column(Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint)
            if (r != null && r.publishedAt > 0) Text(stringResource(R.string.upd_dot_ago, ago(r.publishedAt)), fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Text(
            when {
                r == null -> stringResource(R.string.upd_title)
                m.standing == MaliReleases.Standing.CURRENT -> stringResource(R.string.mali_upd_current_headline)
                else -> stringResource(R.string.mali_upd_new_headline, r.tag)
            },
            fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.padding(top = 4.dp),
        )
        if (r != null && offered) {
            val notes = remember(r.tag, r.notes) { MaliReleases.notesExcerpt(r.notes) }
            ReleaseNotes(r.title, notes, r.tag)
        }
        if (m.standing == MaliReleases.Standing.NO_APK && r != null) {
            Text(stringResource(R.string.mali_upd_no_apk, r.tag), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        if (m.phase == MaliUpdateState.Phase.WAITING_WIFI) {
            Text(stringResource(R.string.mali_upd_waiting_wifi), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        if (m.error != null) Text(m.error, fontSize = 13.sp, color = pal.error, modifier = Modifier.padding(top = 12.dp))
        val size = r?.apk?.size?.takeIf { it > 0 }?.let { " · ${stringResource(R.string.upd_mb, ((it + 524_288) / 1_048_576).toInt())}" }.orEmpty()
        Box(Modifier.padding(top = 14.dp)) {
            when (m.phase) {
                MaliUpdateState.Phase.DOWNLOADING, MaliUpdateState.Phase.INSTALLING, MaliUpdateState.Phase.CONFIRM -> Column(
                    Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val stage = stringResource(
                        when (m.phase) {
                            MaliUpdateState.Phase.DOWNLOADING -> if (m.percent >= 100) R.string.mali_upd_verifying else R.string.upd_stage_downloading
                            MaliUpdateState.Phase.INSTALLING -> R.string.upd_stage_installing
                            else -> R.string.upd_stage_confirm
                        },
                    )
                    val determinate = m.phase == MaliUpdateState.Phase.DOWNLOADING && m.percent in 0..99
                    Text(
                        if (determinate) stringResource(R.string.upd_stage_percent, stage, m.percent) else stringResource(R.string.upd_stage, stage),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
                    )
                    if (determinate) LinearProgressIndicator(progress = { m.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
                }
                MaliUpdateState.Phase.READY ->
                    PrimaryButton(stringResource(R.string.mali_upd_install_button, r?.tag.orEmpty()), enabled = !s.sessionRunning, main = true) { ma.onInstall() }
                else -> if (offered && r?.apk != null) {
                    PrimaryButton(stringResource(R.string.mali_upd_download_button, size), main = true) { ma.onDownload() }
                }
            }
        }
        if (s.sessionRunning && m.ready) {
            Text(stringResource(R.string.upd_stop_session), fontSize = 12.5.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** "Update ready: vX — tap to install", over every page but Updates until it is dismissed. */
@Composable
internal fun BoxScope.MaliUpdateBanner(m: MaliUpdateState?, ma: MaliUpdateActions, hidden: Boolean) {
    if (m == null || !m.ready || m.bannerDismissed || hidden) return
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).widthIn(max = 420.dp)
            .clip(Shape14).background(colors.surface).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (hot) pal.signal else pal.line, Shape14)
            .hoverable(src).focusable(interactionSource = src)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = ma.onInstall)
            .controllerConfirm(onClick = ma.onInstall)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
    ) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = pal.good, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.mali_upd_ready_banner, m.release!!.tag), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = ma.onDismissBanner) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.upd_not_now), tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ago(millis: Long): String {
    if (System.currentTimeMillis() - millis in 0 until 60_000) return stringResource(R.string.upd_just_now)
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
