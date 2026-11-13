package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.StoreBar
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #565 — Store ▸ Phone Apps: the shared top bar, export and import, asserted on
 * what the code RENDERS and RETURNS.
 *
 * Bar: both Store fragments are hosted and their bars read back by the tag
 * StoreBar puts on every control (StoreBar.itemTag) — same controls, same labels, every verb
 * enabled on both (#571 made Install all / Update all real on Phone Apps).
 *
 * Export: packages are installed into Robolectric's PackageManager with a real
 * install-source record, and every field of the exported JSON is compared to
 * what the PackageManager, the fleet manifest and the central classification
 * answer for that package.
 *
 * Import: the plan is built over the ONE installer→store map (#564's
 * appstore-install-sources.json, read through PhoneAppActions.sources), and
 * each bucket is read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorePhoneAppsTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = ctx.packageManager

    /** #1732 The chip probe is injected: a standing clock and a fixed broker answer, run inline, so no real time and no probe thread decide the outcome. */
    @org.junit.Before fun freshChannelProbe() {
        com.diegonmarcos.superapp.appstore.StoreTestHooks.injectProbe(
            PROBE_AT, com.diegonmarcos.superapp.shellclient.ShellStatus(false, false, "none", "Cloud C3 Webserver missing", PROBE_AT, "Cloud C3 Webserver is not installed"))
    }

    @After fun resetHost() {
        com.diegonmarcos.superapp.appstore.StoreTestHooks.restoreProbe()
        AppStoreHost.classify = { _, _ -> emptyMap() }
    }

    private companion object { const val PROBE_AT = 1_700_000_000_000L; const val MAX_IDLE_PASSES = 50 }

    private fun String.withoutCheckedAt() = replace(Regex("\\(checked [0-9:]+\\)"), "(checked)")

    private fun idle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }

    /**
     * The Wireless Dbg chip is filled by StoreStatus.refresh. With the probe injected (see [freshChannelProbe]) refresh runs inline and its
     * result is posted to the main looper, so a bounded number of idle passes settles it: no wall clock, no sleep. The assertion stays: the
     * chip must have left "checking..." before the tabs are compared.
     */
    private fun settleChannel() {
        fun label() = compose.onAllNodes(hasTestTag(StoreBar.itemTag(StoreBar.Item.WIRELESS_DEBUG)))
            .fetchSemanticsNodes().firstOrNull()?.text()
        var passes = 0
        while (label()?.endsWith("checking...") != false) {
            assertTrue("the Wireless Dbg status never settled (still '${label()}')", ++passes <= MAX_IDLE_PASSES)
            idle()
        }
    }

    /** The bar as the semantics tree shows it: its controls by item, and the captions under them. */
    /** What one bar control said when it was read (nodes go stale when their page is replaced). */
    private class Seen(val text: String, val disabled: Boolean, val clickable: Boolean)
    private class Bar(val controls: Map<StoreBar.Item, Seen>, val captions: Set<String>)

    private fun barOf(f: Fragment): Bar {
        compose.runOnUiThread {
            compose.activity.supportFragmentManager.fragments.toList().forEach {
                compose.activity.supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
        }
        show(f); idle()
        settleChannel()
        val root = compose.onAllNodes(hasTestTag(StoreBar.TAG), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("${f::class.simpleName} draws no Store bar", root.isNotEmpty())
        val controls = StoreBar.Item.values().mapNotNull { item ->
            compose.onAllNodes(hasTestTag(StoreBar.itemTag(item))).fetchSemanticsNodes().firstOrNull()
                ?.let { item to Seen(it.text(), it.isDisabled(), SemanticsActions.OnClick in it.config) }
        }.toMap()
        // the captions: every text of the bar that is not inside one of its controls
        fun captions(n: SemanticsNode): List<String> =
            if (n.tag()?.startsWith(StoreBar.TAG + ":") == true) emptyList()
            else n.children.flatMap { captions(it) } + listOfNotNull(n.text().takeIf { it.isNotEmpty() && n.children.isEmpty() })
        return Bar(controls, root.first().children.flatMap { captions(it) }.toSet())
    }

    @Test
    fun `Phone Apps draws the Cloud tab's bar, same controls, and since 571 every verb is real on both`() {
        val cloud = barOf(StoreCloudFragment())
        val phone = barOf(StorePhoneFragment())
        assertEquals("the Cloud bar lost controls", StoreBar.Item.values().toSet(), cloud.controls.keys)
        // #784 Download all fills the fleet cache, which only the Cloud tab owns:
        // a null verb draws no button at all, so it is the one control Phone lacks.
        assertEquals("Phone Apps does not draw the same controls",
            cloud.controls.keys - StoreBar.Item.DOWNLOAD, phone.controls.keys)
        for ((item, seen) in phone.controls)
            // the chip says when it was checked ("(checked 14:18:45)"): two tabs read a second apart must still be the same label
            assertEquals("$item label differs between the tabs", cloud.controls.getValue(item).text.withoutCheckedAt(), seen.text.withoutCheckedAt())
        assertEquals("the Cloud tab has a disabled control", emptySet<StoreBar.Item>(),
            cloud.controls.filterValues { it.disabled }.keys)
        // #571: Install all / Update all are real on Phone Apps — this store installs
        // fleet apps through the release path and external apps through their
        // declared ladder — so nothing is disabled and no reason caption is drawn.
        assertEquals("Phone Apps still disables a verb the resolver made real", emptySet<StoreBar.Item>(),
            phone.controls.filterValues { it.disabled }.keys)
        assertEquals("Phone Apps draws a disabled-reason caption for verbs that are enabled",
            emptySet<String>(), (phone.captions - cloud.captions).filterNot { it.endsWith("checking...") }.toSet())
        assertTrue("an enabled verb must answer a tap",
            phone.controls.filterKeys { it == StoreBar.Item.INSTALL || it == StoreBar.Item.UPDATE }.values
                .all { it.clickable })
    }

    private fun install(pkg: String, versionName: String, code: Long, installer: String?) {
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = pkg; this.versionName = versionName; longVersionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        })
        shadowOf(pm).setInstallSourceInfo(pkg, installer, installer)
    }

    @Test
    fun `export records what the phone reports, sorted, stable, with the central shelf`() {
        AppStoreHost.classify = StoreShelves::of
        val fleetPkg = (AppInventory.fleetPackages() - ctx.packageName).sorted().first()
        val whatsapp = "com.whatsapp"
        val side = "org.example.sideloaded"
        install(whatsapp, "2.24.1", 241_000L, "com.android.vending")
        install(side, "0.9", 9L, "com.google.android.packageinstaller")
        install(fleetPkg, "1.0", 3_000_001L, ctx.packageName)
        val labels = linkedMapOf(side to "Side", whatsapp to "WhatsApp", fleetPkg to "cloud")

        val json = AppInventory.toJson(AppInventory.entriesFor(ctx, labels))
        val doc = JSONObject(json)
        assertEquals(AppInventory.KIND, doc.getString("kind"))
        assertEquals(AppInventory.SCHEMA, doc.getInt("schema"))
        val apps = doc.getJSONArray("apps").let { a -> (0 until a.length()).map(a::getJSONObject) }
        assertEquals("not sorted by package id", labels.keys.sorted(), apps.map { it.getString("package") })

        for (a in apps) {
            val pkg = a.getString("package")
            val info = pm.getPackageInfo(pkg, 0)
            val src = pm.getInstallSourceInfo(pkg)
            assertEquals("$pkg version_code", info.longVersionCode, a.getLong("version_code"))
            assertEquals("$pkg version_name", info.versionName, a.getString("version_name"))
            assertEquals("$pkg origin_store is not the install-source record",
                src.installingPackageName, a.getString("origin_store"))
            assertEquals("$pkg ours flag", pkg == fleetPkg, a.getBoolean("ours"))
        }
        val shelf = StoreShelves.of(ctx, mapOf(whatsapp to "WhatsApp"))[whatsapp]
        assertNotNull("the central classification shelves nothing for WhatsApp", shelf)
        assertEquals("category is not the central shelf", shelf!!.heading,
            apps.first { it.getString("package") == whatsapp }.getString("category"))

        val again = AppInventory.toJson(AppInventory.entriesFor(ctx, labels.entries.reversed().associate { it.key to it.value }))
        assertEquals("the same phone exported different bytes", json, again)
    }

    @Test
    fun `import plans ours to Constellation, foreign to its declared store, the rest to manual`() {
        val sources = PhoneAppActions.sources(ctx)
        val stores = sources.getJSONObject("sources")
        val storeInstaller = stores.keys().asSequence().sorted().first()
        val fleet = AppInventory.fleetPackages()
        val fleetPkg = (fleet - ctx.packageName).sorted().first()
        fun e(pkg: String, origin: String?, ours: Boolean) = AppInventory.Entry(pkg, "1", 1, origin, ours, null)
        val file = AppInventory.toJson(listOf(
            e(fleetPkg, ctx.packageName, true),
            e("com.example.foreign", storeInstaller, false),
            e("com.example.sideloaded", "com.google.android.packageinstaller", false),
            e("com.example.claims.ours", null, true),
            e("com.example.here", storeInstaller, false)))

        val plan = AppInventory.plan(AppInventory.parse(file), setOf("com.example.here"), fleet, sources)
        assertEquals(listOf("com.example.here"), plan.installed.map { it.pkg })
        assertEquals("ours must be the fleet's missing members only", listOf(fleetPkg), plan.ours.map { it.pkg })
        assertEquals(listOf("com.example.foreign"), plan.store.map { it.entry.pkg })
        val link = plan.store.single()
        assertEquals("foreign app sent to the wrong store", stores.getJSONObject(storeInstaller).getString("label"), link.label)
        assertEquals("the store page is not aimed at the store that installed it", storeInstaller, link.intent.`package`)
        assertTrue("the store link does not name the app: ${link.intent.data}",
            link.intent.data.toString().contains("com.example.foreign"))
        assertEquals("a sideload, or a file claiming `ours`, must be manual",
            listOf("com.example.claims.ours", "com.example.sideloaded"), plan.manual.map { it.pkg })
        assertFalse("a newer-schema file must be refused",
            runCatching { AppInventory.parse(JSONObject(file).put("schema", AppInventory.SCHEMA + 1).toString()) }.isSuccess)
    }
}
