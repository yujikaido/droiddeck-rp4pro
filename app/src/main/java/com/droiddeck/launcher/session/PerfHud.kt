package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class PerfHud(context: Context) {
    var text by mutableStateOf("")
        private set

    private val context = context.applicationContext
    private val frames = AtomicInteger()
    private var lastTick = 0L
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val enabled: Boolean
        get() = SessionPrefs.hudEnabled(context)
            && !File(Environment.getExternalStorageDirectory(), "Download/droiddeck-no-hud").exists()

    var onPresentingWindowChanged: (() -> Unit)? = null
    private var lastWindow: String? = null

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            if (window != null && window != lastWindow) {
                lastWindow = window
                handler.post { onPresentingWindowChanged?.invoke() }
            }
        }
        override fun onGameFrame() { frames.incrementAndGet() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
            lastTick = now
            text = line(frames.getAndSet(0) / dt) + device()
            handler.postDelayed(this, 1000)
        }
    }

    fun refresh() {
        if (enabled) start() else stop()
    }

    fun start() {
        if (!enabled || running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        frames.set(0)
        WaylandCompositor.setGameListener(listener)
        handler.post(tick)
    }

    fun stop() {
        running = false
        WaylandCompositor.clearGameListener(listener)
        text = ""
    }

    private fun line(base: Float): String {
        val mode = FrameGen.mode(context)
        if (mode.engine == FrameGen.ENGINE_OFF) return fps(base)
        val lsfg = mode.engine == FrameGen.ENGINE_LSFG
        val name = if (lsfg) "LSFG" else "Win-FG"
        val label = FrameGen.label(context, mode)
        if (lsfg && Lossless.cacheFile(context)?.isFile != true) return context.getString(R.string.hud_fg_needs_lossless, name)
        val stats = try { WaylandCompositor.nativeFrameGenStats() } catch (t: Throwable) { null }
        val problem = try { WaylandCompositor.nativeFrameGenProblem() } catch (t: Throwable) { -1 }
        return when {
            problem == 1 -> context.getString(R.string.hud_fg_unsupported, name)
            problem == 2 -> context.getString(R.string.hud_fg_failed, name)
            stats != null && stats[3] > 1f -> String.format(Locale.US, "%s  %.0f → %.0f fps", label, base, stats[3])
            else -> context.getString(R.string.hud_fg_starting, label, fps(base))
        }
    }

    private fun fps(value: Float) = String.format(Locale.US, "%.0f fps", value)

    /* The tablet's own state next to the frame rate, so a recording shows the conditions the
     * number was reached in: battery level (and charging), battery temperature, and Android's
     * thermal status (the throttling level the system itself reports). */
    private fun device(): String {
        val battery = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        val parts = mutableListOf<String>()
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val millivolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            if (level >= 0 && scale > 0) {
                var b = context.getString(R.string.hud_battery, level * 100 / scale)
                if (tenths != Int.MIN_VALUE) b += String.format(Locale.US, " %.1f°C", tenths / 10f)
                watts(millivolts)?.let { b += String.format(Locale.US, " %+.1f W", it) }
                parts += b
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null) parts += context.getString(R.string.hud_thermal, thermalName(pm.currentThermalStatus))
        }
        return if (parts.isEmpty()) "" else "  ·  " + parts.joinToString("  ·  ")
    }

    /* The battery's net power: negative while it drains, positive while it charges. A PC's USB
     * port often shows "charging" while the game drains the battery faster than the port feeds it;
     * this shows what actually happens. The current's sign differs between makers: it is taken as
     * draining unless the battery reports itself charging. */
    private fun watts(millivolts: Int): Float? {
        if (millivolts <= 0) return null
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val micro = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (micro == Int.MIN_VALUE || micro == 0) return null
        val amps = kotlin.math.abs(micro) / 1_000_000f
        val charging = bm.isCharging && micro > 0
        return (if (charging) 1f else -1f) * amps * millivolts / 1000f
    }

    private fun thermalName(status: Int): String = context.getString(when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.hud_thermal_none
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.hud_thermal_light
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.hud_thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.hud_thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.hud_thermal_critical
        else -> R.string.hud_thermal_emergency
    })
}
