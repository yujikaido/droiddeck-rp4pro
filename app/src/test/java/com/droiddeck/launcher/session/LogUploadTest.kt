package com.droiddeck.launcher.session

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LogUploadTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<Activity>>()

    private fun resumed(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup().also {
        it.get().setTheme(android.R.style.Theme_DeviceDefault)
        controllers += it
        LogUpload.attach(it.get())
    }

    private fun pause(c: ActivityController<Activity>) { LogUpload.detach(c.get()); c.pause() }

    /** The newest dialog still on screen (closed ones don't count). */
    private fun latestDialog(): AlertDialog? = ShadowAlertDialog.getLatestAlertDialog()?.takeIf { it.isShowing }

    private fun notifiedCode(): String? = shadowOf(app.getSystemService(NotificationManager::class.java))
        .getNotification(LogUpload.NOTIFICATION_ID)?.extras?.getCharSequence("android.text")?.toString()

    @Before fun clean() {
        app.getSharedPreferences("log_upload", Context.MODE_PRIVATE).edit().clear().commit()
        if (LogUpload.sendingSince != null) LogUpload.finished(app, Result.failure(Exception("reset")))
        ShadowDialog.reset()
    }

    @After fun close() {
        controllers.forEach { runCatching { LogUpload.detach(it.get()); it.pause().stop().destroy() } }
    }

    @Test fun theCodeIsFoundInTheServiceReply() {
        assertEquals("ML-7F3K", LogUpload.codeIn("""{"code":"ML-7F3K"}"""))
        assertNull(LogUpload.codeIn("""{"error":"rate"}"""))
    }

    @Test fun theLastCodeIsKeptAndANewOneIsUnseenAgain() {
        assertNull(LogUpload.LastCode.read(app))
        assertFalse(LogUpload.LastCode.unseen(app))
        LogUpload.LastCode.save(app, "ML-AAAA", at = 1234L)
        assertEquals("ML-AAAA", LogUpload.LastCode.read(app))
        assertEquals(1234L, LogUpload.LastCode.at(app))
        assertTrue(LogUpload.LastCode.unseen(app))
        LogUpload.LastCode.markSeen(app)
        assertFalse(LogUpload.LastCode.unseen(app))
        LogUpload.LastCode.save(app, "ML-BBBB")
        assertEquals("ML-BBBB", LogUpload.LastCode.read(app))
        assertTrue(LogUpload.LastCode.unseen(app))
    }

    @Test fun onlyOneUploadRunsAtATime() {
        assertTrue(LogUpload.begin(now = 1L))
        assertFalse(LogUpload.begin(now = 2L))
        assertEquals(1L, LogUpload.sendingSince)
        LogUpload.finished(app, Result.failure(Exception("x")))
        assertNull(LogUpload.sendingSince)
        assertTrue(LogUpload.begin())
        LogUpload.finished(app, Result.failure(Exception("x")))
    }

    @Test fun whileSendingTheScreenShowsAProgressDialogAnOutsideTapCannotClose() {
        resumed()
        LogUpload.begin()
        LogUpload.attach(controllers.last().get())
        val d = latestDialog()
        assertNotNull(d)
        assertTrue(d!!.isShowing)
        assertFalse(shadowOf(d).isCancelableOnTouchOutside)
        assertFalse(shadowOf(d).isCancelable)
        LogUpload.finished(app, Result.failure(Exception("x")))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(d.isShowing)
    }

    @Test fun aCodeWithTheScreenOpenIsShownSavedAndNotClosedByAnOutsideTap() {
        resumed()
        LogUpload.begin()
        LogUpload.finished(app, Result.success("ML-C0DE"))
        assertEquals("ML-C0DE", LogUpload.LastCode.read(app))
        assertEquals("ML-C0DE", LogUpload.lastCode(app))
        val d = latestDialog()!!
        assertTrue(d.isShowing)
        assertFalse(shadowOf(d).isCancelableOnTouchOutside)
        assertNull(notifiedCode())
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(LogUpload.LastCode.unseen(app))
        assertEquals("ML-C0DE", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun aCodeAfterTheScreenWentAwayGoesToANotificationAndComesBackOnTheNextScreen() {
        val session = resumed()
        LogUpload.begin()
        pause(session)                       // the player stopped the session / left the app
        ShadowDialog.reset()
        LogUpload.finished(app, Result.success("ML-DNBG"))
        assertEquals("ML-DNBG", LogUpload.LastCode.read(app))
        assertEquals("Your code is ML-DNBG", notifiedCode())
        assertNull(latestDialog())
        resumed()                            // back in the app: the code is shown
        val d = latestDialog()!!
        assertTrue(d.isShowing)
        assertEquals("Your code is ML-DNBG", notifiedCode())   // the notification stays until it is closed
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(notifiedCode())
    }

    @Test fun aRotateDuringTheUploadShowsTheProgressAndThenTheCodeOnTheNewScreen() {
        val old = resumed()
        LogUpload.begin()
        LogUpload.attach(old.get())
        pause(old)
        ShadowDialog.reset()
        resumed()                            // the recreated activity
        val progress = latestDialog()!!
        assertTrue(progress.isShowing)
        LogUpload.finished(app, Result.success("ML-R0T8"))
        val code = latestDialog()!!
        assertTrue(code !== progress && code.isShowing)
        assertFalse(progress.isShowing)
    }

    @Test fun aClosedCodeDoesNotComeBackButStaysAsTheLastCode() {
        resumed()
        LogUpload.begin()
        LogUpload.finished(app, Result.success("ML-SEEN"))
        latestDialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(LogUpload.LastCode.unseen(app))
        ShadowDialog.reset()
        resumed()
        assertNull(latestDialog())
        assertEquals("ML-SEEN", LogUpload.lastCode(app))
    }

    @Test fun aFailedUploadKeepsThePreviousCode() {
        LogUpload.LastCode.save(app, "ML-OLD1")
        LogUpload.LastCode.markSeen(app)
        LogUpload.begin()
        LogUpload.finished(app, Result.failure(Exception("timeout")))
        assertNull(LogUpload.sendingSince)
        assertEquals("ML-OLD1", LogUpload.LastCode.read(app))
        assertFalse(LogUpload.LastCode.unseen(app))
    }

    @Test fun theNotificationsCopyButtonCopiesTheCode() {
        LogUpload.LastCode.save(app, "ML-N0TE")
        LogUpload.CopyReceiver().onReceive(app, Intent().putExtra(LogUpload.EXTRA_CODE, "ML-N0TE"))
        assertEquals("ML-N0TE", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertFalse(LogUpload.LastCode.unseen(app))
    }

    // The upload's foreground service (Android 16 cuts a background app's network ~5 s after Home).

    private fun service(): ServiceController<LogUploadService> =
        Robolectric.buildService(LogUploadService::class.java).create().also { it.startCommand(0, 1) }

    @After fun stopService() { LogUploadService.instance?.stopNow(remove = true) }

    @Test fun whileSendingTheServiceHoldsTheForegroundWithAProgressNotification() {
        LogUpload.begin()
        val s = service()
        val shadow = shadowOf(s.get())
        assertEquals(LogUpload.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals("Sending logs… 0 s", shadow.lastForegroundNotification.extras.getCharSequence("android.text").toString())
        assertFalse(shadow.isStoppedBySelf)
        assertTrue(LogUploadService.instance === s.get())
        LogUploadService.tick(app, 7)
        assertEquals("Sending logs… 7 s", notifiedCode())
        LogUpload.finished(app, Result.failure(Exception("x")))
    }

    @Test fun aCodeAfterLeavingTheAppReplacesTheProgressNotificationAndTheServiceStops() {
        val screen = resumed()
        LogUpload.begin()
        val s = service()
        pause(screen)                        // Home, or the session was stopped
        LogUpload.finished(app, Result.success("ML-BG16"))
        val shadow = shadowOf(s.get())
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertNull(LogUploadService.instance)
        assertEquals("Your code is ML-BG16", notifiedCode())
        assertEquals("ML-BG16", LogUpload.LastCode.read(app))
    }

    @Test fun aCodeWithTheScreenOpenRemovesTheProgressNotification() {
        resumed()
        LogUpload.begin()
        service()
        LogUpload.finished(app, Result.success("ML-FR0N"))
        assertNull(notifiedCode())
        assertTrue(latestDialog()!!.isShowing)
        assertNull(LogUploadService.instance)
    }

    @Test fun aSessionStoppedMidUploadStillGetsTheCodeInTheNotificationWithTheMainScreenInFront() {
        val session = resumed()
        LogUpload.begin()                    // Send in the session's drawer
        val s = service()
        pause(session)                       // Stop: the session screen goes ...
        resumed()                            // ... and the main screen comes to the front
        LogUpload.finished(app, Result.success("ML-ST0P"))
        assertEquals("Your code is ML-ST0P", notifiedCode())
        assertTrue(shadowOf(s.get()).isStoppedBySelf)
        val d = latestDialog()!!             // the main screen shows it as well
        assertTrue(d.isShowing)
        assertEquals("ML-ST0P", LogUpload.LastCode.read(app))
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ML-ST0P", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertFalse(LogUpload.LastCode.unseen(app))
        assertNull(notifiedCode())           // copied: the notification goes
    }

    @Test fun aStopRightAfterTheCodeCameStillPutsItInTheNotification() {
        val session = resumed()
        LogUpload.begin()                    // Send in the session's drawer
        service()
        LogUpload.finished(app, Result.success("ML-AU52"))   // the code reaches the session screen ...
        assertTrue(latestDialog()!!.isShowing)
        assertNull(notifiedCode())
        session.get().finish()               // ... and Stop lands a moment later: the screen closes with it
        pause(session)
        assertEquals("Your code is ML-AU52", notifiedCode())
        assertTrue(LogUpload.LastCode.unseen(app))
        ShadowDialog.reset()
        resumed()                            // the next open shows it too; Copy clears both
        val d = latestDialog()!!
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(LogUpload.LastCode.unseen(app))
        assertNull(notifiedCode())
    }

    @Test fun aCodeClosedBeforeTheScreenGoesGetsNoNotification() {
        val screen = resumed()
        LogUpload.begin()
        LogUpload.finished(app, Result.success("ML-CL0S"))
        latestDialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pause(screen)
        assertNull(notifiedCode())
    }

    @Test fun aSessionStoppedMidUploadThatFailsSaysSoInTheNotification() {
        val session = resumed()
        LogUpload.begin()
        service()
        pause(session)
        resumed()
        LogUpload.finished(app, Result.failure(java.net.UnknownHostException("malibridge-logs")))
        assertEquals(app.getString(com.droiddeck.launcher.R.string.logsend_failed), notifiedCode())
    }

    @Test fun aFailureAfterLeavingTheAppSaysSoInTheNotification() {
        val screen = resumed()
        LogUpload.begin()
        service()
        pause(screen)
        LogUpload.finished(app, Result.failure(java.net.UnknownHostException("malibridge-logs")))
        assertEquals(app.getString(com.droiddeck.launcher.R.string.logsend_failed), notifiedCode())
        assertNull(LogUploadService.instance)
    }

    @Test fun aServiceThatStartsAfterTheUploadEndedStopsAtOnce() {
        LogUpload.begin()
        LogUpload.finished(app, Result.failure(Exception("x")))
        val s = service()
        val shadow = shadowOf(s.get())
        assertTrue(shadow.isStoppedBySelf)
        assertNull(LogUploadService.instance)
        assertNull(notifiedCode())
    }

    @Test fun sendStartsTheServiceInTheForeground() {
        LogUploadService.start(app)
        val started = shadowOf(app).nextStartedService
        assertEquals(LogUploadService::class.java.name, started.component!!.className)
    }
}
