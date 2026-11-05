package com.diegonmarcos.superapp.adbdebug

import com.diegonmarcos.superapp.adbdebug.WdKeeperCore.Action
import com.diegonmarcos.superapp.adbdebug.WdKeeperCore.Cause
import com.diegonmarcos.superapp.adbdebug.WdKeeperCore.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1467 the keeper's state machine, leader election and audit log (all pure). */
class WdKeeperCoreTest {
    private val base = Facts(armed = true, canWrite = true, settingOn = false, wifiConnected = true, leader = true)

    @Test fun `a disarmed keeper never acts, whatever the phone says`() {
        assertEquals(Action.DISARMED, WdKeeperCore.decide(base.copy(armed = false)))
        assertEquals(Action.DISARMED, WdKeeperCore.decide(base.copy(armed = false, settingOn = true)))
    }

    @Test fun `setting on is nothing to do`() = assertEquals(Action.NOTHING, WdKeeperCore.decide(base.copy(settingOn = true)))

    @Test fun `off with everything in place re-arms`() = assertEquals(Action.REARM, WdKeeperCore.decide(base))

    @Test fun `no permission waits for the permission and does not write`() =
        assertEquals(Action.WAIT_PERMISSION, WdKeeperCore.decide(base.copy(canWrite = false)))

    @Test fun `no Wi-Fi waits for the network callback`() =
        assertEquals(Action.WAIT_WIFI, WdKeeperCore.decide(base.copy(wifiConnected = false)))

    @Test fun `a follower does not write`() = assertEquals(Action.NOT_LEADER, WdKeeperCore.decide(base.copy(leader = false)))

    @Test fun `an owner's own off is honoured, and beats a re-arm`() =
        assertEquals(Action.OWNER_OFF, WdKeeperCore.decide(base.copy(ownerOff = true)))

    @Test fun `decide trusts the owner-off flag, the keeper clears it when the setting is seen on`() =
        assertEquals(Action.OWNER_OFF, WdKeeperCore.decide(base.copy(ownerOff = true, settingOn = true)))

    @Test fun `the order is permission, then Wi-Fi, then leadership`() {
        assertEquals(Action.WAIT_PERMISSION, WdKeeperCore.decide(base.copy(canWrite = false, wifiConnected = false, leader = false)))
        assertEquals(Action.WAIT_WIFI, WdKeeperCore.decide(base.copy(wifiConnected = false, leader = false)))
    }

    @Test fun `cause guesses`() {
        assertEquals(Cause.SELF, WdKeeperCore.guessCause(true, null, 1_000_000, selfWriteRecent = true))
        assertEquals(Cause.BOOT, WdKeeperCore.guessCause(false, null, 30_000, false))
        assertEquals(Cause.WIFI_LOST, WdKeeperCore.guessCause(false, 2_000, 9_000_000, false))
        assertEquals(Cause.NETWORK_CHANGED, WdKeeperCore.guessCause(true, 5_000, 9_000_000, false))
        assertEquals(Cause.OWNER_OR_SYSTEM, WdKeeperCore.guessCause(true, null, 9_000_000, false))
        assertEquals(Cause.OWNER_OR_SYSTEM, WdKeeperCore.guessCause(true, 600_000, 9_000_000, false))
    }
}

class WdAuditLogRowTest {
    @Test fun `one readable row per event with time, state, Wi-Fi, cause and who re-armed`() {
        val e = WdAuditLog.Entry(5, on = true, wifi = false, cause = "Wi-Fi lost", by = "superapp (self-grant)")
        assertEquals("T5  ON   wifi no  Wi-Fi lost  by superapp (self-grant)", WdAuditLog.row(e) { "T$it" })
    }
}

class WdKeeperLeaderTest {
    private val now = 1_000_000L
    private fun c(pkg: String, armed: Boolean = true, w: Boolean = true, age: Long = 1_000) = WdKeeperLeader.Claim(pkg, armed, w, now - age)

    @Test fun `nobody armed means no leader`() = assertNull(WdKeeperLeader.elect(listOf(c("a", armed = false), c("b", armed = false)), now))

    @Test fun `one armed app leads`() = assertTrue(WdKeeperLeader.isLeader("b", listOf(c("a", armed = false), c("b")), now))

    @Test fun `both armed, the same app is chosen from either side`() {
        val claims = listOf(c("com.x.store"), c("com.x.superapp"))
        assertEquals("com.x.store", WdKeeperLeader.elect(claims, now))
        assertEquals(1, listOf("com.x.store", "com.x.superapp").count { WdKeeperLeader.isLeader(it, claims, now) })
    }

    @Test fun `an app that can write beats one that cannot`() =
        assertEquals("z", WdKeeperLeader.elect(listOf(c("a", w = false), c("z")), now))

    @Test fun `a stale heartbeat drops out and the other app takes over`() {
        val claims = listOf(c("a", age = WdKeeperLeader.TTL_MS + 1), c("b"))
        assertEquals("b", WdKeeperLeader.elect(claims, now))
        assertTrue(WdKeeperLeader.isLeader("b", claims, now))
    }

    @Test fun `a lone stale app is not eligible`() = assertNull(WdKeeperLeader.elect(listOf(c("a", age = 10 * WdKeeperLeader.TTL_MS)), now))

    @Test fun `a disarmed app is never the leader`() = assertEquals(false, WdKeeperLeader.isLeader("a", listOf(c("a", armed = false), c("b")), now))
}

class WdAuditLogTest {
    private class Mem(var blob: String? = null) : WdAuditLog.Store {
        override fun load() = blob
        override fun save(json: String) { blob = json }
    }
    private fun e(at: Long, on: Boolean = false, by: String = "-") = WdAuditLog.Entry(at, on, true, "boot", by)

    @Test fun `the ring keeps the newest entries`() {
        val log = WdAuditLog(Mem(), capacity = 3)
        (1..5).forEach { log.add(e(it.toLong())) }
        assertEquals(listOf(3L, 4L, 5L), log.entries().map { it.at })
    }

    @Test fun `entries survive a restart`() {
        val store = Mem()
        WdAuditLog(store).add(WdAuditLog.Entry(7, true, false, "Wi-Fi lost", "com.x"))
        assertEquals(listOf(WdAuditLog.Entry(7, true, false, "Wi-Fi lost", "com.x")), WdAuditLog(store).entries())
    }

    @Test fun `a damaged blob is an empty log`() = assertEquals(0, WdAuditLog(Mem("{not json")).entries().size)

    @Test fun `the last re-arm is the newest entry with a rearmer`() {
        val log = WdAuditLog(Mem())
        assertNull(log.lastRearm())
        log.add(e(1, on = true, by = "com.x")); log.add(e(2)); 
        assertEquals(1L, log.lastRearm()?.at)
    }

    @Test fun `text carries time, state, wifi, cause and who`() =
        assertEquals("T1  OFF  wifi yes  boot  by nobody", WdAuditLog(Mem()).also { it.add(e(1)) }.asText { "T$it" })
}

class WdKeeperStatusTest {
    private val ok = WdKeeperStatus.Snapshot(true, true, true, true, true, true, 1_000L, "com.x")

    @Test fun `all held is all OK`() =
        assertTrue(WdKeeperStatus.chips(ok) { "now" }.all { it.level == WdKeeperStatus.Level.OK })

    @Test fun `a missing permission and a restricted battery show`() {
        val c = WdKeeperStatus.chips(ok.copy(canWrite = false, batteryExempt = false)) { "now" }.associateBy { it.id }
        assertEquals(WdKeeperStatus.Level.BAD, c.getValue("permission").level)
        assertEquals("WRITE_SECURE_SETTINGS (Write secure settings): not granted to this app", c.getValue("permission").let { it.label + ": " + it.value })
        assertEquals(WdKeeperStatus.Level.WARN, c.getValue("battery").level)
    }

    @Test fun `an armed keeper with no service is red, a disarmed one is neutral`() {
        assertEquals(WdKeeperStatus.Level.BAD, WdKeeperStatus.chips(ok.copy(serviceRunning = false)) { "" }.first { it.id == "service" }.level)
        assertEquals(WdKeeperStatus.Level.UNKNOWN, WdKeeperStatus.chips(ok.copy(armed = false, serviceRunning = false)) { "" }.first { it.id == "service" }.level)
    }

    @Test fun `never re-armed says never`() =
        assertEquals("never", WdKeeperStatus.chips(ok.copy(lastRearmAt = null, lastRearmBy = null)) { "" }.first { it.id == "rearm" }.value)

    @Test fun `the json carries the log`() {
        val j = org.json.JSONObject(WdKeeperStatus.json(ok, listOf(WdAuditLog.Entry(1, true, true, "boot", "com.x"))))
        assertEquals(1, j.getJSONArray("log").length())
        assertEquals(true, j.getBoolean("armed"))
    }
}
