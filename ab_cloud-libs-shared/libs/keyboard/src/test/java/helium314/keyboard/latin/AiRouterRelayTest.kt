package helium314.keyboard.latin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1801 the keyboard's Text tools hold no key for a provider that needs one: AiRouter.complete hands the same body to the
 * AI-key owner engine (use keyboard_text_tools), an engine "no_key" refusal is NoTokenException (the message the user
 * already reads), the settings row opens the engine's AI keys screen, and ITextTools carries no credential method. Read
 * from the sources (AiRouter needs the baked registry and Android to run), so the property itself is what is held.
 */
class AiRouterRelayTest {

    private fun src(rel: String) = File(rel).readText()
    private val router = src("src/main/java/helium314/keyboard/latin/AiRouter.kt")

    @Test fun `a key-needing provider is relayed and its refusal is the old no-key failure`() {
        assertTrue(router.contains("const val RELAY_USE = \"keyboard_text_tools\""))
        val complete = router.substring(router.indexOf("fun complete(context: Context, system: String, user: String, route: Route)"))
        val relay = complete.indexOf("DecisionsLink.relay(context, RELAY_USE, \"chat\", body)")
        val direct = complete.indexOf("openConnection()")
        assertTrue("the relay is taken before any direct connection", relay in 0 until direct)
        assertTrue(complete.contains("if (r.reason == \"no_key\") throw NoTokenException(p)"))
        assertTrue("relayed and direct answers are read by one function", complete.contains("return read(p, route, r.code, r.body)") && complete.contains("return read(p, route, code, text)"))
    }

    @Test fun `no key is read here for a provider that needs one`() {
        assertTrue(router.contains("if (p.needsToken) \"\" else context.prefs().getString(Settings.PREF_AI_TOKEN_PREFIX + p.id"))
        assertFalse(router.contains("revealAiKey"))
    }

    @Test fun `a key stored here before is handed to the engine once, then cleared, never dropped`() {
        val fn = router.substring(router.indexOf("fun retireStoredKey("))
        val hand = fn.indexOf("DecisionsLink.handOver(context, p.id, v, perApp = false)")
        val keep = fn.indexOf("if (v.isNotEmpty() && !handOver(v)) return false")
        val clear = fn.indexOf(".remove(slot)")
        assertTrue("handed over (fleet slot), kept on failure, cleared only after", hand in 0 until keep && keep < clear)
        val log = fn.substring(0, fn.indexOf("return true"))
        assertFalse("the migration log line carries no key", Regex("""Log\.\w\([^)]*\$\{?v\b""").containsMatchIn(log))
        assertTrue(router.contains("if (p.needsToken) retireStoredKey(context, p)"))
    }

    @Test fun `the settings row opens the engine for a key-needing provider and keeps the bridge's optional token`() {
        val screen = src("src/main/java/helium314/keyboard/settings/screens/AiRoutingScreen.kt")
        assertTrue(screen.contains("if (p.needsToken) {") && screen.contains("AiRouter.openKeySettings(ctx, p)"))
        assertTrue(screen.contains("} else TextInputPreference(setting, \"\")"))
    }

    @Test fun `ITextTools carries no credential method`() {
        val aidl = src("../text-tools/src/main/aidl/com/diegonmarcos/superapp/texttools/ITextTools.aidl")
        val sigs = aidl.lines().filter { Regex("""^\s+(String\[\]|String|List<String>) [a-zA-Z]+\(""").containsMatchIn(it) }
        assertTrue(sigs.size >= 16)
        assertTrue(sigs.toString(), sigs.none { Regex("(?i)token|key|credential").containsMatchIn(it) })
        assertTrue("the slots are kept so later transaction codes do not move", sigs.indexOfFirst { "retiredRoutingWrite" in it } == 10 && sigs.indexOfFirst { "retiredRevealSlot" in it } == 11)
    }

    @Test fun `the engine serves the keyboard's use`() {
        val u = JSONObject(src("../../../fleet-private-data/decisions.json"))
            .getJSONObject("relay").getJSONObject("uses").getJSONObject("keyboard_text_tools")
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudkeyboard"))
        assertEquals("chat", u.getJSONArray("endpoints").getString(0))
    }
}
