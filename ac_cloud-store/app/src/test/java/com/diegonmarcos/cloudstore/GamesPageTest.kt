package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #1895 the CloudGames bridge behind Search > Games (the old Tauri commands installed_packages and open_url), over a Robolectric Context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GamesPageTest {
    private val ctx get() = RuntimeEnvironment.getApplication() as Application

    private fun call(op: String, args: JSONObject): JSONObject =
        JSONObject(GamesPage.handle(ctx, JSONObject().put("id", 7).put("op", op).put("args", args).toString()))

    @Test fun theReplyCarriesTheRequestId() {
        assertEquals(7, call("installed_packages", JSONObject().put("pkgs", JSONArray())).getInt("id"))
    }

    @Test fun installedPackagesAnswersOnlyLaunchablePackages() {
        val pm = shadowOf(ctx.packageManager)
        pm.addActivityIfNotPresent(android.content.ComponentName("org.example.game", "org.example.game.Main"))
        pm.addIntentFilterForActivity(android.content.ComponentName("org.example.game", "org.example.game.Main"),
            android.content.IntentFilter(Intent.ACTION_MAIN).also { it.addCategory(Intent.CATEGORY_LAUNCHER) })
        val r = call("installed_packages", JSONObject().put("pkgs", JSONArray(listOf("org.example.game", "org.example.absent"))))
        val got = r.getJSONArray("result")
        assertEquals(listOf("org.example.game"), (0 until got.length()).map { got.getString(it) })
    }

    @Test fun anUnknownOpOrABadRequestIsAnErrorNeverAThrow() {
        assertEquals("unknown op", call("nope", JSONObject()).getString("error"))
        assertTrue(JSONObject(GamesPage.handle(ctx, "not json")).has("error"))
    }

    @Test fun openUrlTakesHttpsAndIntentUrlsOnly() {
        assertEquals(Intent.ACTION_VIEW, GamesPage.intentFor("https://f-droid.org/packages/x").action)
        val i = GamesPage.intentFor("intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=org.example.game;component=a.b/.C;end")
        assertEquals("org.example.game", i.`package`)
        assertNull("an intent URL cannot name a component", i.component)
        assertNotNull(call("open_url", JSONObject().put("url", "http://insecure.example")).optString("error").takeIf { it.isNotEmpty() })
        assertNotNull(call("open_url", JSONObject().put("url", "javascript:alert(1)")).optString("error").takeIf { it.isNotEmpty() })
    }
}
