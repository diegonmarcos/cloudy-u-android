package com.diegonmarcos.superapp.configs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.adbdebug.WdAuditLog
import com.diegonmarcos.superapp.adbdebug.WdKeeperStatus
import com.diegonmarcos.superapp.perms.PermCatalogue
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.StatusFilter
import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellclient.AdbLink
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1550 Reference screenshots of the redesigned SuperApp ADB Shell and Configs > Perms pages: dark (the launcher's own palette)
 * and light (a light kit palette: the SuperApp ships no light launcher theme, the shot proves the page reads correctly on one),
 * phone (Galaxy A37, 384x832 dp) and tablet landscape (1280x800 dp, two panes). Written to build/configs-screens/ops-*.png,
 * uploaded by configs-screens.yml; each shot also asserts what it shows (two panes on the tablet, the terminal header, the KPI strip).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpsConsoleScreensTest : KitPageHarness() {

    private val light = KitPalette(surface = Color(0xFFFFFFFF), surfaceSelected = Color(0xFFE8EAF2), textPrimary = Color(0xFF111827),
        textSecondary = Color(0xFF4B5563), accent = Color(0xFF5B21B6), hairline = Color(0xFFD1D5DB), tileInk = Color(0xFFFFFFFF),
        ok = Color(0xFF15803D), warn = Color(0xFFB45309), bad = Color(0xFFB91C1C))

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            val a = compose.activity
            a.setContentView(a.kitComposeView(if (dark) LauncherPalette.kit(a) else light, content))
            a.window.decorView.setBackgroundColor(if (dark) 0xFF0B0B10.toInt() else 0xFFF3F4F6.toInt())
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bg = (compose.activity.window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color ?: android.graphics.Color.BLACK
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(bg); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(out, name).length() > 1000)
    }

    // ── ADB Shell fixture ──
    private val decl = TerminalModel.parse(File("../../ab_cloud-libs-shared/libs/shell-broker/src/main/assets/shell-allowlist.json").readText())

    private fun termUi(connected: Boolean): TermUi {
        val st = if (connected) ShellStatus(true, true, "embedded-adb", "ok", 1_762_430_000_000, adb = AdbLink.CONNECTED, adbDetail = "192.168.1.23:37123")
        else ShellStatus(true, true, "local-server", "ok", 1_762_430_000_000, adb = AdbLink.WIRELESS_OFF)
        val t0 = 1_762_430_000_000L
        val es = listOf(
            TerminalModel.Entry(1, "id", t0, t0 + 38, TerminalModel.State.OK, 0, "uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log)\n"),
            TerminalModel.Entry(2, "logcat -d -v color -t 6", t0 + 2_000, t0 + 2_412, TerminalModel.State.OK, 0,
                "\u001B[38;5;0m\u001B[34m11-06 10:12:01.120  1234  1290 D\u001B[0m WifiService: scan done\n" +
                "\u001B[32m11-06 10:12:01.511  1234  1301 I\u001B[0m ActivityManager: Start proc com.diegonmarcos.superapp\n" +
                "\u001B[33m11-06 10:12:02.004  2201  2201 W\u001B[0m PackageManager: Not granting android.permission.DUMP\n" +
                "\u001B[31;1m11-06 10:12:02.810  4410  4433 E\u001B[0m AndroidRuntime: FATAL EXCEPTION: main\n"),
            TerminalModel.Entry(3, "dumpsys battery", t0 + 9_000, t0 + 9_160, TerminalModel.State.OK, 0,
                "Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  status: 2\n  health: 2\n  level: 82\n  temperature: 301\n"),
            TerminalModel.Entry(4, "pm path com.example.missing", t0 + 12_000, t0 + 12_090, TerminalModel.State.EXIT, 1, "", ""),
            TerminalModel.Entry(5, "reboot", t0 + 15_000, t0 + 15_004, TerminalModel.State.DENIED, note = "refused by the shell broker: no allow rule for com.diegonmarcos.superapp"),
            TerminalModel.Entry(6, "dumpsys batterystats --charged", t0 + 20_000),
        )
        return TermUi(status = st, device = "SM-A376B", uid = "2000",
            tabs = listOf(TermTab(1, "shell 1", es, TerminalModel.History().push("id").push("dumpsys battery")), TermTab(2, "logcat")), active = 1,
            input = "dumpsys b", favourites = setOf("bat-status", "log-errors"),
            pending = null)
    }

    private val adbState = AdbPageState(
        status = ShellStatus(true, true, "local-server", "ok", 1_762_430_000_000, adb = AdbLink.WIRELESS_OFF),
        wireless = false, paired = true, port = "37123", keeperOn = true,
        keeper = WdKeeperStatus.Snapshot(true, true, false, true, true, false, 1_762_429_000_000, "keeper"),
        callers = listOf(AdbShellModel.CallerRow("com.diegonmarcos.cloudstore", "6 rules · stdin installs", 1_762_429_900_000),
            AdbShellModel.CallerRow("com.diegonmarcos.cloudaccount", "4 rules", 1_762_429_800_000)),
        audit = listOf(AuditLog.Entry(1_762_429_900_000, "com.diegonmarcos.cloudstore", "pm install -S 29384112", "ok", 0, 8_400, "binder")),
        keeperLog = listOf(WdAuditLog.Entry(1_762_429_000_000, false, true, "Wi-Fi dropped", "-")),
    )

    private fun adb(dark: Boolean, wide: Boolean, connected: Boolean) = show(dark) {
        AdbShellLayout(
            terminal = { h -> AdbTerminalContent(termUi(connected), decl, TermActions(), outputHeight = h) },
            sections = { AdbShellContent(adbState.copy(status = termUi(connected).status), AdbPageActions()) },
            wide = wide,
        )
    }

    // ── Perms fixture ──
    private val cat = PermCatalogue.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText())
    private val rows = cat.entries.mapIndexed { i, e ->
        PermRowState(e, PermStatus(when (i % 7) { 0, 1, 2 -> PermState.GRANTED; 3 -> PermState.DENIED; 4 -> PermState.PERMANENTLY_DENIED; 5 -> PermState.NOT_APPLICABLE; else -> PermState.UNKNOWN },
            if (i % 7 == 4) "asked twice" else ""))
    }

    private fun perms(dark: Boolean, wide: Boolean) = show(dark) {
        val st = mutableStateOf(StatusFilter.ALL)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PermsSummaryBar(rows, compose.activity, st) {}
            PermsCenterSection(rows = rows, highlight = null, channelName = "embedded-adb", adbResults = emptyList(), adbRunning = false, shellNote = null,
                onClearHighlight = {}, onGrant = {}, onShell = {}, onRunAdb = {}, onOpenAdbShell = {}, ctx = compose.activity, statusState = st, wide = wide)
        }
    }

    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    @Test fun `adb shell phone dark`() { adb(true, false, false); compose.onNodeWithTag("term:header").fetchSemanticsNode(); snap("ops-adb-shell-phone-dark.png") }

    @Config(qualifiers = "w384dp-h832dp-notnight-450dpi")
    @Test fun `adb shell phone light`() { adb(false, false, true); compose.onNodeWithTag("term:inputbar").fetchSemanticsNode(); snap("ops-adb-shell-phone-light.png") }

    @Config(qualifiers = "w1280dp-h800dp-night-land-xhdpi")
    @Test fun `adb shell tablet dark`() { adb(true, true, true); compose.onNodeWithTag("adb:two-pane").fetchSemanticsNode(); snap("ops-adb-shell-tablet-dark.png") }

    @Config(qualifiers = "w1280dp-h800dp-notnight-land-xhdpi")
    @Test fun `adb shell tablet light`() { adb(false, true, false); compose.onNodeWithTag("adb:two-pane").fetchSemanticsNode(); snap("ops-adb-shell-tablet-light.png") }

    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    @Test fun `perms phone dark`() { perms(true, false); compose.onNodeWithTag("perms:kpi").fetchSemanticsNode(); snap("ops-perms-phone-dark.png") }

    @Config(qualifiers = "w384dp-h832dp-notnight-450dpi")
    @Test fun `perms phone light`() { perms(false, false); compose.onNodeWithTag("perms:kpi").fetchSemanticsNode(); snap("ops-perms-phone-light.png") }

    @Config(qualifiers = "w1280dp-h800dp-night-land-xhdpi")
    @Test fun `perms tablet dark`() { perms(true, true); compose.onNodeWithTag("perms:two-pane").fetchSemanticsNode(); snap("ops-perms-tablet-dark.png") }

    @Config(qualifiers = "w1280dp-h800dp-notnight-land-xhdpi")
    @Test fun `perms tablet light`() { perms(false, true); compose.onNodeWithTag("perms:two-pane").fetchSemanticsNode(); snap("ops-perms-tablet-light.png") }
}
