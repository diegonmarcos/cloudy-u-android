package com.diegonmarcos.superapp.adbdebug

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The channel arms itself only when the app's switch says so; the default per app is build.json data. */
class ChannelAutoArmTest {

    @Test fun declaredDefaultComesFromTheBlock() {
        assertFalse(ChannelAutoArm.declaredDefault("""{"default_on":false,"needs":[]}"""))
        assertTrue(ChannelAutoArm.declaredDefault("""{"default_on":true}"""))
    }

    @Test fun anAppThatDeclaresNothingKeepsTheOldBehaviour() {
        assertTrue(ChannelAutoArm.declaredDefault(null))
        assertTrue(ChannelAutoArm.declaredDefault("{}"))
        assertTrue(ChannelAutoArm.declaredDefault("not json"))
    }

    @Test fun theUsersExplicitChoiceBeatsTheDefault() {
        val off = """{"default_on":false}"""
        val on = """{"default_on":true}"""
        assertFalse(ChannelAutoArm.resolve(null, off))
        assertTrue(ChannelAutoArm.resolve(true, off))
        assertTrue(ChannelAutoArm.resolve(null, on))
        assertFalse(ChannelAutoArm.resolve(false, on))
    }

    /** SuperApp ships OFF, read from its own build.json. Since #1512 the Store and Account are shell-broker clients with no block: the no-block default (on) applies. */
    @Test fun defaultPerApp() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "aa_cloud-superapp/build.json").exists() }
        assumeTrue("not run from a full checkout", repo != null)
        fun def(app: String) = ChannelAutoArm.declaredDefault(
            JSONObject(File(repo, "$app/build.json").readText()).optJSONObject("privileged_channel")?.toString())
        assertFalse(def("aa_cloud-superapp"))
        assertTrue(def("ac_cloud-store"))
        assertEquals(true, def("ac_cloud-account"))
        // #1833 the ADB owner declares OFF: without the block its implicit triggers ran the chain to the pairing prompt.
        assertFalse(def("ac_cloud-c3-webserver"))
    }

    /** #1833 an installed host switch (the owner's WirelessDebugKeeper.Prefs) is THE answer; the stored lib choice no longer counts. */
    @Test fun theHostSwitchWinsOverTheStoredChoiceAndTheDefault() {
        assertFalse(ChannelAutoArm.resolveWithHost(false, true, """{"default_on":true}"""))
        assertTrue(ChannelAutoArm.resolveWithHost(true, false, """{"default_on":false}"""))
        assertFalse(ChannelAutoArm.resolveWithHost(null, null, """{"default_on":false}"""))
        assertTrue(ChannelAutoArm.resolveWithHost(null, null, "{}"))
    }
}
