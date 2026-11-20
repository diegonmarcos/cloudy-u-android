package com.diegonmarcos.cloudwebserver

import com.diegonmarcos.cloudwebserver.ui.AdbLive
import com.diegonmarcos.cloudwebserver.ui.HomeModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1834 the Summary lights read the real state of each service: each of the three lights moves on its own, none is derived from another. */
class HomeModelTest {
    private fun pw(server: Boolean, running: Boolean, ka: Boolean) = JSONObject("""{"server":$server,"running":$running,"keep_alive":$ka}""")
    private fun served(vararg serving: Boolean) = JSONObject("""{"rows":[${serving.joinToString(",") { """{"name":"p","port":1,"serving":$it}""" }}]}""")
    private fun door(verdict: String, process: Boolean) = """{"id":"d","state":"listening","status":{"verdict":"$verdict","checks":[{"id":"process","ok":$process}]}}"""

    @Test fun `pages light one is a port answering, light three needs every port, keep-alive is its own switch`() {
        val l = HomeModel.pages(pw(true, true, false), served(true, false))
        assertEquals(true, l.on); assertEquals(false, l.keepAlive); assertEquals(false, l.healthy)
        val all = HomeModel.pages(pw(true, true, true), served(true, true))
        assertEquals(true, all.healthy); assertEquals(true, all.keepAlive)
        assertEquals(false, HomeModel.pages(pw(true, true, true), served(false)).on)
        assertNull(HomeModel.pages(null, null).on)
    }

    @Test fun `api healthy is the health route answer, not the server flag`() {
        assertEquals(false, HomeModel.api(pw(true, true, true), null).healthy)
        assertEquals(false, HomeModel.api(pw(true, true, true), JSONObject("""{"ok":false}""")).healthy)
        assertEquals(true, HomeModel.api(pw(true, true, false), JSONObject("""{"ok":true}""")).healthy)
        assertEquals(false, HomeModel.api(pw(false, false, true), null).on)
    }

    @Test fun `sshd on is the process check, healthy is every door green, keep-alive is the sshd switch`() {
        val d = JSONObject("""{"keep_alive":true,"doors":[${door("green", true)},${door("amber", true)}]}""")
        val l = HomeModel.sshd(d)
        assertEquals(true, l.on); assertEquals(true, l.keepAlive); assertEquals(false, l.healthy)
        assertEquals(false, HomeModel.sshd(JSONObject("""{"keep_alive":false,"doors":[${door("red", false)}]}""")).on)
        assertEquals(true, HomeModel.sshd(JSONObject("""{"doors":[${door("green", true)}]}""")).healthy)
    }

    @Test fun `adb lights come from the port probe, the keep-alive switch and the echo round trip separately`() {
        val up = JSONObject("""{"server_up":true,"server_port":38099}""")
        val l = HomeModel.adb(up, AdbLive(true, false, false))
        assertEquals(true, l.on); assertEquals(false, l.keepAlive); assertEquals(false, l.healthy)
        val none = HomeModel.adb(null, AdbLive(false, null, null))
        assertNull(none.on); assertNull(none.keepAlive); assertNull(none.healthy)
    }

    @Test fun `status messages name the live state`() {
        assertTrue(HomeModel.startStopMsg(pw(true, true, true), JSONObject("""{"server_up":false}""")).contains("ADB server not answering"))
        assertTrue(HomeModel.keepAliveMsg(pw(true, true, false), AdbLive(true, true, null)).contains("Keep alive OFF"))
        assertTrue(HomeModel.keepAliveMsg(pw(true, true, false), AdbLive(false, null, null)).contains("n/a here"))
        assertTrue(HomeModel.probeMsg(served(true, false), null, null, null, AdbLive(false, null, null)).startsWith("Pages 1/2 serving"))
    }
}
