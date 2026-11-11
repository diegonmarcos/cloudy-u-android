package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.ChannelMode.AUTO
import com.diegonmarcos.superapp.adbdebug.ChannelMode.EMBEDDED_ONLY
import com.diegonmarcos.superapp.adbdebug.ChannelMode.LOCAL_SERVER
import com.diegonmarcos.superapp.adbdebug.ChannelMode.SHIZUKU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Setup checklist: which items a mode has, their real status, and the screen each button opens. */
class SetupChecklistTest {

    private val ready = ChannelFacts(LOCAL_SERVER, 1L, devOptions = true, wirelessDebug = true, onWifi = true, notificationsAllowed = true,
        adbPaired = true, adbConnected = true, serverPort = 38099, serverRunning = true,
        shizukuInstalled = true, shizukuRunning = true, shizukuGranted = true)
    private fun ids(f: ChannelFacts, via: LaunchVia = LaunchVia.ADB) = SetupChecklist.items(f, via).map { it.id }
    private fun item(f: ChannelFacts, id: String, via: LaunchVia = LaunchVia.ADB) = SetupChecklist.items(f, via).first { it.id == id }

    @Test fun adbModesListTheFivePrerequisitesInOrder() {
        val five = listOf("dev-options", "wireless-debugging", "wifi", "paired", "notifications")
        assertEquals(five, ids(ready))
        assertEquals(five, ids(ready.copy(mode = EMBEDDED_ONLY)))
        assertEquals(five, ids(ready.copy(mode = AUTO)))
        assertEquals(five, ids(ready, LaunchVia.ADB))
    }

    @Test fun shizukuModeListsOnlyShizukuItems() {
        assertEquals(listOf("shizuku-running", "shizuku-permission"), ids(ready.copy(mode = SHIZUKU)))
        // Local server mode is the adb channel whatever the launcher choice says: never a Shizuku item
        assertEquals(listOf("dev-options", "wireless-debugging", "wifi", "paired", "notifications"), ids(ready, LaunchVia.SHIZUKU))
        assertTrue(ids(ready).none { it.startsWith("shizuku") })
        // and Shizuku mode lists nothing of adb
        assertTrue(ids(ready.copy(mode = SHIZUKU)).none { it in setOf("dev-options", "wireless-debugging", "wifi", "paired", "notifications") })
        // Shizuku permission is not asked for in modes that never use it
        assertTrue(ids(ready.copy(mode = EMBEDDED_ONLY)).none { it.startsWith("shizuku") })
        assertTrue(ids(ready.copy(mode = AUTO)).none { it.startsWith("shizuku") })
    }

    @Test fun everyItemShowsItsRealStatus() {
        val all = SetupChecklist.items(ready)
        assertTrue(all.all { it.status == SetupStatus.OK })
        assertEquals("5/5 ready", SetupChecklist.summary(all))
        val bad = ready.copy(devOptions = false, wirelessDebug = false, onWifi = false, adbPaired = false, adbConnected = false, notificationsAllowed = false)
        assertEquals(List(5) { SetupStatus.TODO }, SetupChecklist.items(bad).map { it.status })
        assertEquals("0/5 ready", SetupChecklist.summary(SetupChecklist.items(bad)))
        val unknown = ready.copy(devOptions = null, wirelessDebug = null, onWifi = null, notificationsAllowed = null)
        assertEquals(listOf(SetupStatus.UNKNOWN, SetupStatus.UNKNOWN, SetupStatus.UNKNOWN, SetupStatus.OK, SetupStatus.UNKNOWN),
            SetupChecklist.items(unknown).map { it.status })
    }

    @Test fun eachButtonOpensTheExactScreen() {
        val bad = ready.copy(devOptions = false, wirelessDebug = false, onWifi = false, adbPaired = false, notificationsAllowed = false)
        assertEquals(SetupAction.OPEN_ABOUT_PHONE, item(bad, "dev-options").action)                 // hidden: tap Build number
        assertEquals(SetupAction.OPEN_DEVELOPER_OPTIONS, item(ready, "dev-options").action)
        assertEquals(SetupAction.OPEN_WIRELESS_DEBUGGING, item(bad, "wireless-debugging").action)
        assertEquals(SetupAction.OPEN_WIFI, item(bad, "wifi").action)
        assertEquals(SetupAction.PAIR, item(bad, "paired").action)
        assertEquals(SetupAction.OPEN_NOTIFICATION_SETTINGS, item(bad, "notifications").action)
        assertEquals(SetupAction.OPEN_SHIZUKU, item(ready.copy(mode = SHIZUKU), "shizuku-running").action)
        assertEquals(SetupAction.REQUEST_SHIZUKU, item(ready.copy(mode = SHIZUKU), "shizuku-permission").action)
        assertTrue(SetupChecklist.items(bad).all { it.action != null && it.actionLabel.isNotBlank() })
    }

    @Test fun shizukuStatusFollowsItsFourStates() {
        fun st(i: Boolean, r: Boolean, g: Boolean): Pair<SetupStatus, SetupStatus> {
            val items = SetupChecklist.items(ready.copy(mode = SHIZUKU, shizukuInstalled = i, shizukuRunning = r, shizukuGranted = g))
            return items[0].status to items[1].status
        }
        assertEquals(SetupStatus.OK to SetupStatus.OK, st(true, true, true))
        assertEquals(SetupStatus.OK to SetupStatus.TODO, st(true, true, false))
        assertEquals(SetupStatus.TODO to SetupStatus.UNKNOWN, st(true, false, false))
        assertEquals(SetupStatus.TODO to SetupStatus.UNKNOWN, st(false, false, false))
        assertEquals("approve the Shizuku prompt", SetupChecklist.items(ready.copy(mode = SHIZUKU, shizukuGranted = false))[1].detail)
    }
}
