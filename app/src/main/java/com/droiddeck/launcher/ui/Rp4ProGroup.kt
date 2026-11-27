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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Rp4ProGroup(host: MenuHost) {
    val context = LocalContext.current
    if (!Rp4ProControls.supported()) return
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Rp4ProControls.state(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (true) {
            state = withContext(Dispatchers.IO) { Rp4ProControls.state(context) }
            delay(1000)
        }
    }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val switch = { set: (Boolean) -> Boolean, on: Boolean ->
        scope.launch {
            if (!withContext(Dispatchers.IO) { set(on) }) toast(context.getString(R.string.rp4_switch_failed))
            delay(800)
            refresh++
        }
        Unit
    }

    SettingsGroup(stringResource(R.string.rp4_title)) {
        if (state.wifi != null) ToggleRow(host, "rp4-wifi", stringResource(R.string.rp4_wifi), null, state.wifi == true) {
            switch({ on -> Rp4ProControls.setWifi(context, on) }, it)
        }
        if (state.bluetooth != null) ToggleRow(host, "rp4-bluetooth", stringResource(R.string.rp4_bluetooth), null, state.bluetooth == true) {
            switch({ on -> Rp4ProControls.setBluetooth(context, on) }, it)
        }
    }
}
