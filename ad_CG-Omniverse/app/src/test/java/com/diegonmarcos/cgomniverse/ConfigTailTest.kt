package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1630 / #1631 the declaration side of the fleet-default Configs tail (Agents, Perms, Logcat, X-Ray App, About after the | separator, drawn by
 * libs:ui-nav-bars): this app links the three opt-in libs, still declares its General / About pages (nothing removed), draws its own X-Ray App
 * page through the hook, and the Avatar page still carries the render probe the X-Ray App reads.
 */
class ConfigTailTest {
    private fun module(path: String): File = File(File("").absoluteFile.let { if (it.name == "app") it.parentFile else it }, path)

    private val build by lazy { JSONObject(module("build.json").readText()) }

    @Test fun theThreeOptInLibsAreLinked() {
        val mods = build.getJSONObject("modules")
        val deps = (0 until mods.getJSONObject("app").getJSONArray("depends_on").length()).map { mods.getJSONObject("app").getJSONArray("depends_on").getString(it) }
        listOf("libs:fleetconfigs-agents", "libs:fleetconfigs-logcat", "libs:fleetconfigs-xray").forEach {
            assertTrue("$it is a module", mods.has(it))
            assertTrue("$it is a dependency of the app", it in deps)
        }
        val gradle = module("app/build.gradle").readText()
        listOf("fleetconfigs-agents", "fleetconfigs-logcat", "fleetconfigs-xray").forEach { assertTrue(gradle.contains("project(':libs:$it')")) }
    }

    @Test fun generalAndAboutAreStillDeclared() {
        val cp = build.getJSONObject("configs_pages")
        assertTrue(cp.getJSONObject("general").getJSONArray("sections").length() >= 7)
        assertTrue(cp.getJSONObject("about").has("tagline") && cp.getJSONObject("about").has("features"))
    }

    @Test fun theXrayAppPageIsRegisteredThroughTheHook() {
        val main = module("app/src/main/java/com/diegonmarcos/cgomniverse/MainActivity.kt").readText()
        assertTrue(main.contains("registerXrayPage") && main.contains("OmniXrayPage("))
    }

    @Test fun theAvatarPageStillCarriesTheRenderProbe() {
        val html = module("app/src/main/assets/avatar/index.html").readText()
        listOf("HTMLCanvasElement.prototype.getContext", "webglcontextlost", "WEBGL_debug_renderer_info", "window.fetch", "requestAnimationFrame",
            "OmniDiag", "cgc_module", "boot_called", "unhandledrejection").forEach { assertTrue("probe lacks $it", html.contains(it)) }
        val avatar = module("app/src/main/java/com/diegonmarcos/cgomniverse/ui/AvatarPage.kt").readText()
        assertTrue(avatar.contains("\"OmniDiag\"") && avatar.contains("OmniXray.cgcEvent"))
    }
}
