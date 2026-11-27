package com.diegonmarcos.superapp.media

import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1966 the Media card's buds-app button: present when installed, the device map picks the app, the default comes from build.json, hidden with a reason when absent. */
class BudsApp1966Test {
    private val declared: BadgeDeclaration.Badge by lazy {
        val nc = JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("notification_center")
        BadgeDeclaration.parse(nc.toString()).first { it.id == "media_now_playing" }
    }
    private val soundcore = "com.oceanwing.soundcore"
    private val rules get() = declared.budsApps
    private fun ids(installed: Set<String>, setting: String = soundcore, connected: List<String> = emptyList()) =
        MediaLaunchers.visible({ it in installed }, "com.aspiro.tidal", BudsApp.resolve(setting, connected, rules) { it in installed }).map { it.first.id }

    @Test fun `the button is present after spotify when the app is installed`() {
        val all = setOf("com.aspiro.tidal", "com.spotify.music", soundcore)
        assertEquals(listOf("play-last", "open-tidal", "open-spotify", "open-buds-app"), ids(all))
        assertEquals(soundcore, MediaLaunchers.visible({ it in all }, "com.aspiro.tidal", soundcore).first { it.first.kind == MediaLaunchers.Kind.BUDS_APP }.second)
    }

    @Test fun `the device map picks the right app`() {
        val inst = setOf(soundcore, "com.samsung.android.app.watchmanager", "com.google.android.apps.wearables.maestro.companion")
        assertEquals("com.samsung.android.app.watchmanager", BudsApp.resolve(soundcore, listOf("Galaxy Buds2 Pro (1A2B)"), rules) { it in inst })
        assertEquals("com.google.android.apps.wearables.maestro.companion", BudsApp.resolve(soundcore, listOf("My Speaker", "pixel buds pro"), rules) { it in inst })
        assertEquals(soundcore, BudsApp.resolve(soundcore, listOf("Soundcore Liberty 4 NC"), rules) { it in inst })
        // nothing connected, or an unknown device: the setting's app
        assertEquals(soundcore, BudsApp.resolve(soundcore, emptyList(), rules) { it in inst })
        assertEquals(soundcore, BudsApp.resolve(soundcore, listOf("Car Kit"), rules) { it in inst })
        // the setting itself is honoured
        assertEquals("com.samsung.android.app.watchmanager", BudsApp.resolve("com.samsung.android.app.watchmanager", emptyList(), rules) { it in inst })
    }

    @Test fun `the default comes from the declaration not from code`() {
        val opt = declared.customization.firstOrNull { it.key == BudsApp.KEY }
        assertNotNull(opt)
        assertEquals(soundcore, opt!!.default)
        assertTrue(soundcore in opt.options)
        // code carries no fallback app: an empty setting yields no candidates
        assertEquals(emptyList<String>(), BudsApp.candidates("", emptyList(), rules))
    }

    @Test fun `the button is hidden with a reason when the app is not installed`() {
        val none = emptySet<String>()
        assertEquals(listOf("play-last"), ids(setOf("com.aspiro.tidal")) .filter { it != "open-tidal" })
        assertTrue("open-buds-app" !in ids(none + "com.aspiro.tidal"))
        val why = BudsApp.reason(soundcore, emptyList(), rules) { false }
        assertNotNull(why)
        assertTrue(why!!, why.contains(soundcore) || why.contains("Soundcore"))
        assertNull(BudsApp.reason(soundcore, emptyList(), rules) { it == soundcore })
    }
}
