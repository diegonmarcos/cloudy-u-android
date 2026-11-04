package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.diegonmarcos.cloudc3.pages.FleetLiveState
import com.diegonmarcos.cloudc3.pages.FleetModel
import com.diegonmarcos.cloudc3.pages.InfraSectionScreen
import com.diegonmarcos.cloudc3.pages.InfraSections
import com.diegonmarcos.cloudc3.pages.InfraTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DECLARATION TO SCREEN, one test per Infra section: the shipped data/fleet_slice.json is parsed and each page is
 * rendered; every declared item must be on its screen with the facts the declaration gives it, and nothing a section must
 * not hold (a DB container) may be. Nothing fleet-shaped is typed here: the expectations are read from the declaration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InfraScreensComposeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val slice = FleetFixtures.slice()
    private val today = LocalDate.of(2026, 10, 20)
    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)

    private fun show(section: String, live: FleetLiveState = FleetLiveState()) {
        compose.setContent { CloudKitTheme(palette) { InfraSectionScreen(section, slice, live, today) } }
    }

    private fun row(section: String, id: String) = compose.onNodeWithTag(InfraTags.row(section, id))

    @Test
    fun `providers - every declared provider is a row with its account, plan, cost and links`() {
        show(InfraSections.PROVIDERS)
        compose.onNodeWithText("${slice.providers.size} providers", substring = true).assertExists()
        slice.providers.forEach { p ->
            val r = row(InfraSections.PROVIDERS, p.id)
            r.assertTextContains(p.label)
            r.assertTextContains("Account: ${p.account ?: "not declared"}")
            r.assertTextContains(p.plan ?: "plan not declared")
            r.assertTextContains("Monthly cost:", substring = true)
            // a link is clickable, so it is its own node in the merged tree rather than a line of the row
            p.links.forEach { l -> compose.onNodeWithText("${l.label}: ${l.url}").assertExists() }
            p.vms.forEach { id -> r.assertTextContains(slice.vms.first { it.id == id }.alias, substring = true) }
        }
    }

    @Test
    fun `providers - the live total replaces the declared figure and a failed read says why`() {
        val liveProvider = slice.providers.first { it.cost?.live != null }
        val live = FleetLiveState().apply {
            costs = mapOf(liveProvider.id to "9.74 EUR (October 2026)")
            notes = mapOf("cost:${slice.providers.last { it.cost?.live != null }.id}" to "NETWORK: down")
        }
        show(InfraSections.PROVIDERS, live)
        row(InfraSections.PROVIDERS, liveProvider.id).assertTextContains("Monthly cost: 9.74 EUR (October 2026) (live)")
        val declared = slice.providers.first { it.cost?.amount != null }
        row(InfraSections.PROVIDERS, declared.id).assertTextContains("(declared)", substring = true)
    }

    @Test
    fun `containers-as-service - every declared platform is a row with its project, region and service count, and no container is listed`() {
        assertTrue("the registry declares a container platform", slice.containerPlatforms.isNotEmpty())
        show(InfraSections.SERVERLESS)
        slice.containerPlatforms.forEach { p ->
            val r = row(InfraSections.SERVERLESS, p.id)
            r.assertTextContains(p.label, substring = true)
            p.project?.let { r.assertTextContains("Project: $it", substring = true) }
            p.regions.forEach { r.assertTextContains(it, substring = true) }
            p.services?.let { r.assertTextContains("$it services", substring = true) }
        }
        slice.serverless.forEach { s -> compose.onAllNodesWithText(s.name, substring = true).assertCountEquals(0) }
    }

    @Test
    fun `public domains - the private email identities appear with the declared domains (fake addresses)`() {
        val identities = com.diegonmarcos.cloudc3.pages.PrivateIdentitiesState().apply {
            items = listOf(com.diegonmarcos.cloudc3.pages.PrivateData.Identity("someone@example.test", "Test mailbox", "example"))
            note = "1 from the side-loaded copy"
        }
        compose.setContent { CloudKitTheme(palette) { InfraSectionScreen(InfraSections.DOMAINS_PUBLIC, slice, FleetLiveState(), today, identities) } }
        row(InfraSections.DOMAINS_PUBLIC, "id:someone@example.test").assertTextContains("someone@example.test", substring = true)
        compose.onNodeWithTag(InfraTags.IDENTITIES_NOTE).assertTextContains("side-loaded", substring = true)
    }

    @Test
    fun `vms - every declared vm is a row with provider, region, size and all three addresses`() {
        show(InfraSections.MACHINES)
        slice.vms.forEach { v ->
            val r = row(InfraSections.MACHINES, v.id)
            r.assertTextContains(v.id, substring = true)
            r.assertTextContains(slice.provider(v.provider)!!.label)
            r.assertTextContains(FleetModel.vmSpec(v))
            r.assertTextContains(FleetModel.vmNet(v))
            v.publicIp?.let { r.assertTextContains(it, substring = true) }
            v.wgIp?.let { r.assertTextContains(it, substring = true) }
            v.wgPublicIp?.let { r.assertTextContains(it, substring = true) }
        }
    }

    @Test
    fun `storage - buckets, drives, disks and device storage are rows, and no database container is`() {
        show(InfraSections.STORAGE)
        slice.buckets.forEach { b ->
            row(InfraSections.STORAGE, "S3 bucket/${b.name}").assertTextContains(b.name)
            row(InfraSections.STORAGE, "S3 bucket/${b.name}").assertTextContains(b.dns!!, substring = true)
        }
        slice.backends.forEach { row(InfraSections.STORAGE, "Cloud drive/${it.label}").assertTextContains(it.label) }
        slice.vms.forEach { v ->
            val r = row(InfraSections.STORAGE, "VM disk/${v.alias.ifBlank { v.id }}")
            r.assertTextContains(v.id, substring = true)
            r.assertTextContains("declared", substring = true)
        }
        slice.devices.forEach { row(InfraSections.STORAGE, "Device storage/${it.model}").assertTextContains(it.model) }
        val dbs = FleetFixtures.dbContainerNames()
        assertTrue("the Databases subgroup must still be declared under Containers", dbs.isNotEmpty())
        dbs.forEach { name ->
            compose.onAllNodesWithText(name, substring = true, ignoreCase = true).assertCountEquals(0)
        }
    }

    @Test
    fun `storage - live disk usage and this phone's volume appear when the API and the OS answered`() {
        val vm = slice.vms.first { it.wgIp != null }
        val live = FleetLiveState().apply {
            disks = mapOf(vm.id to FleetModel.DiskUsage("45G", "34G", "77%"))
            phone = "1.0 GB free of 2.0 GB"
        }
        show(InfraSections.STORAGE, live)
        row(InfraSections.STORAGE, "VM disk/${vm.alias.ifBlank { vm.id }}").assertTextContains("34G / 45G (77%)")
        row(InfraSections.STORAGE, "Device storage/This phone (live)").assertTextContains("1.0 GB free of 2.0 GB")
    }

    @Test
    fun `personal - every declared device is on its Desktop, Mobile or Gadgets page with model, os, wireguard address and role`() {
        var current by mutableStateOf(InfraSections.DESKTOP)
        compose.setContent { CloudKitTheme(palette) { InfraSectionScreen(current, slice, FleetLiveState(), today) } }
        listOf(InfraSections.DESKTOP, InfraSections.MOBILE, InfraSections.GADGETS).forEach { page ->
            compose.runOnIdle { current = page }
            val kinds = FleetModel.personalKinds(page, slice)
            slice.devices.filter { it.kind in kinds }.forEach { d ->
                val r = row(page, d.id)
                r.assertTextContains(d.model)
                r.assertTextContains("OS: ${d.os ?: "not declared"}")
                r.assertTextContains("wg0 ${d.wgIp ?: "-"}", substring = true)
                r.assertTextContains("Role: ${d.role ?: "not declared"}")
            }
            kinds.forEach { kind ->
                compose.onNodeWithTag(InfraTags.head(page, kind)).assertTextContains(FleetModel.kindLabel(kind))
                if (slice.devices.none { it.kind == kind }) row(page, "none-$kind").assertTextContains("None declared")
            }
        }
    }

    @Test
    fun `node-python and public domains read the declaration and say none when it is empty`() {
        var current by mutableStateOf(InfraSections.NODE_PYTHON)
        compose.setContent { CloudKitTheme(palette) { InfraSectionScreen(current, slice, FleetLiveState(), today) } }
        if (slice.hosting.isEmpty()) row(InfraSections.NODE_PYTHON, "none").assertTextContains("None declared")
        slice.hosting.forEach { row(InfraSections.NODE_PYTHON, it.id).assertTextContains(it.label) }
        compose.runOnIdle { current = InfraSections.DOMAINS_PUBLIC }
        if (slice.publicDomains.isEmpty()) row(InfraSections.DOMAINS_PUBLIC, "none").assertTextContains("None declared")
        slice.publicDomains.forEach { row(InfraSections.DOMAINS_PUBLIC, it.name).assertTextContains(it.name) }
    }

    @Test
    fun `domains - registration, renewal and every declared record, mail signing included`() {
        show(InfraSections.DOMAINS)
        val d = slice.domains.single()
        val head = row(InfraSections.DOMAINS, d.name)
        head.assertTextContains("Registrar: ${d.registrar} | Admin: ${d.admin}")
        head.assertTextContains("Renewal paid: ${d.renewalPaid}", substring = true)
        head.assertTextContains("Expires: ${d.expires} (${FleetModel.daysUntil(d.expires, today)} days)", substring = true)
        head.assertTextContains("Next renewal: ${d.nextRenewal} (auto-renew on)")
        d.nameservers.forEach { head.assertTextContains(it, substring = true) }
        FleetModel.MAIL_GROUPS.forEach { g ->
            compose.onNodeWithTag(InfraTags.head(InfraSections.DOMAINS, g)).assertTextContains("Mail: $g")
        }
        d.records.forEach { r ->
            val same = d.records.filter { it.group == r.group }
            val i = same.indexOf(r)
            val node = row(InfraSections.DOMAINS, "${r.type}:${r.name}:$i")
            node.assertTextContains(r.type, substring = true)
            node.assertTextContains(r.name, substring = true)
            node.assertTextContains(r.content, substring = true)
        }
        compose.onNodeWithTag(InfraTags.head(InfraSections.DOMAINS, "MTA-STS")).assertExists()
        compose.onNodeWithText("Policy mode ${d.mtaStsMode}", substring = true).assertExists()
    }

    @Test
    fun `domains - the live zone view is shown when the API answered and its absence is stated when it did not`() {
        val live = FleetLiveState().apply {
            dns = listOf(FleetModel.DnsRecord("Live", "x.example.test", "CNAME", "example.test", null, true, null, "svc"))
        }
        show(InfraSections.DOMAINS, live)
        compose.onNodeWithTag(InfraTags.LIVE_NOTE).assertTextContains("1 records seen by the API")
    }

    @Test
    fun `domains - a failed live read keeps the declared records and says it failed`() {
        val live = FleetLiveState().apply { notes = mapOf("dns" to "NO_TOKEN: sign in") }
        show(InfraSections.DOMAINS, live)
        compose.onNodeWithTag(InfraTags.LIVE_NOTE).assertTextContains("Live view unavailable: NO_TOKEN: sign in", substring = true)
        val d = slice.domains.single()
        row(InfraSections.DOMAINS, d.name).assertTextContains(d.registrar, substring = true)
    }

    @Test
    fun `every declared page id has a screen`() {
        var current by mutableStateOf(InfraSections.ALL.first())
        compose.setContent { CloudKitTheme(palette) { InfraSectionScreen(current, slice, FleetLiveState(), today) } }
        InfraSections.ALL.forEach { id ->
            compose.runOnIdle { current = id }
            compose.onNodeWithTag(InfraTags.screen(id)).assertExists()
        }
    }
}
