package com.droiddeck.launcher.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Rp4ProControls
import com.droiddeck.launcher.runtime.DeckyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Rp4ProGroup(host: MenuHost) {
    val context = LocalContext.current
    if (!remember { Rp4ProControls.supported(context) }) return
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Rp4ProControls.state(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var pluginInstalled by remember { mutableStateOf(DeckyManager.bundledPluginInstalled(context)) }
    var installing by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        while (true) {
            state = withContext(Dispatchers.IO) { Rp4ProControls.state(context) }
            delay(1000)
        }
    }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val openTiles = { if (!Rp4ProControls.openQuickSettings(context)) toast(context.getString(R.string.rp4_qs_failed)) }
    val switch = { set: (Boolean) -> Boolean, on: Boolean ->
        scope.launch {
            if (!withContext(Dispatchers.IO) { set(on) }) toast(context.getString(R.string.rp4_switch_failed))
            delay(800)
            refresh++
        }
        Unit
    }

    SettingsGroup(stringResource(R.string.rp4_title)) {
        ActionRow(stringResource(R.string.rp4_fan),
            stringResource(R.string.rp4_mode_hint, stringResource(Rp4ProControls.fanLabel(state.fan))),
            stringResource(R.string.rp4_change), onClick = openTiles)
        ActionRow(stringResource(R.string.rp4_performance),
            stringResource(R.string.rp4_mode_hint, stringResource(Rp4ProControls.performanceLabel(state.performance))),
            stringResource(R.string.rp4_change), onClick = openTiles)
        if (state.wifi != null) ToggleRow(host, "rp4-wifi", stringResource(R.string.rp4_wifi), null, state.wifi == true) {
            switch({ on -> Rp4ProControls.setWifi(context, on) }, it)
        }
        if (state.bluetooth != null) ToggleRow(host, "rp4-bluetooth", stringResource(R.string.rp4_bluetooth), null, state.bluetooth == true) {
            switch({ on -> Rp4ProControls.setBluetooth(context, on) }, it)
        }
        if (DeckyManager.installed(context) != null) ActionRow(stringResource(R.string.rp4_quick_menu),
            stringResource(if (pluginInstalled && DeckyManager.supervisorEnabled(context)) R.string.rp4_quick_menu_on else R.string.rp4_quick_menu_hint),
            stringResource(if (pluginInstalled) R.string.rp4_reinstall else R.string.rp4_add),
            onClick = {
                if (!installing) {
                    installing = true
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { DeckyManager.installRp4ProPlugin(context) }
                        installing = false
                        pluginInstalled = DeckyManager.bundledPluginInstalled(context)
                        toast(error ?: context.getString(R.string.rp4_quick_menu_done))
                    }
                }
            })
    }
}
