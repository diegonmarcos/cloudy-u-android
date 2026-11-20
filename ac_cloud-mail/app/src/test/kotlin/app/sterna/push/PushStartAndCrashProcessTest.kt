package app.sterna.push

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1828 the two crashes of 2026-11-20 (build 6a800dacf5fd), pinned at the source:
 *  - ForegroundServiceDidNotStartInTimeException: every startForegroundService must be answered with startForeground,
 *    including a start that reaches an already-running PushService (only onStartCommand runs then);
 *  - the :crash process (libs:crash's screen) ran SternaApplication.onCreate, whose AppContainer needs WorkManager,
 *    which androidx.startup never initialises outside the main process.
 */
class PushStartAndCrashProcessTest {

    private fun src(rel: String): String =
        listOf("src/main/kotlin/app/sterna/$rel", "app/src/main/kotlin/app/sterna/$rel").map(::File).first { it.exists() }.readText()

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing: $signature", start >= 0)
        val end = text.indexOf("\n    override fun ", start + signature.length).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    private val service by lazy { src("push/PushService.kt") }

    @Test fun `every start command answers its foreground start before doing any work`() {
        val cmd = body(service, "override fun onStartCommand(")
        val fg = cmd.indexOf("if (!goForeground()) return START_NOT_STICKY")
        assertTrue("onStartCommand calls startForeground", fg >= 0)
        assertTrue("the Stop action still ends the service first", cmd.indexOf("ACTION_STOP") in 0 until fg)
        assertTrue("before the reconnect is launched", fg < cmd.indexOf("scope.launch { reconnectAll("))
    }

    @Test fun `onCreate goes foreground before the WorkManager re-schedule, and keeps both`() {
        val create = body(service, "override fun onCreate()")
        val fg = create.indexOf("goForeground()")
        assertTrue(fg >= 0)
        assertTrue("channels first: the notification needs one", create.indexOf("Notifications.ensureChannels(this)") in 0 until fg)
        assertTrue("the #1768 schedule is kept, after", create.indexOf("OfflineSyncWorker.ensureScheduled(this, hasPush = true)") > fg)
    }

    @Test fun `a refused foreground start is still swallowed and stops the service (#98)`() {
        val go = service.substring(service.indexOf("private fun goForeground()"))
        assertTrue(go.contains("FleetNotify.startKeepAlive(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), FleetNotify.specialUseType())"))
        assertTrue(go.contains("catch (e: RuntimeException)"))
        assertTrue(go.contains("stopSelf()"))
    }

    @Test fun `the crash screen's process builds nothing`() {
        val app = src("SternaApplication.kt")
        val create = app.substring(app.indexOf("override fun onCreate()"))
        val guard = create.indexOf("if (com.diegonmarcos.superapp.crash.CrashReports.isCrashProcess()) return")
        assertTrue(guard >= 0)
        assertTrue("before AppContainer and every WorkManager user", guard < create.indexOf("container = AppContainer(this)"))
        assertTrue(guard < create.indexOf("Updater.start(this)"))
        assertTrue(guard < create.indexOf("OfflineSyncWorker.ensureScheduled"))
    }
}
