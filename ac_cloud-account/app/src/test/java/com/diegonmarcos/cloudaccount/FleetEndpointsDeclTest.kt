package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #1093 The declaration is the one place the endpoints live, so it is held to its own rules: every declared service
 * survives the parse (nothing is dropped silently), what is baked into the app is the file, each http mesh endpoint has
 * its NAMED cleartext entry in the network security config (never the whole .app TLD), no secret is declared, and the
 * Status page that shows the endpoints is in the navigation.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEndpointsDeclTest {
    private val buildJson = JSONObject(File("../build.json").readText())
    private val declared = buildJson.getJSONObject("fleet_endpoints")
    private val services = FleetEndpointsContract.parseDecl(declared.toString())

    @Test fun everyDeclaredServiceSurvivesTheParse() {
        val ids = declared.getJSONArray("services").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals("a service the contract dropped would silently have no endpoints", ids, services.map { it.id })
        assertTrue(ids.isNotEmpty())
    }

    @Test fun theMailServiceIsStillThere_CloudMailReadsItsKind() {
        assertEquals(listOf("jmap"), FleetEndpointsContract.parseDecl(FleetEndpointsContract.declOfKind(declared.toString(), "mail")).map { it.id })
    }

    @Test fun theServicesTheLibrariesBakeTheirUrlsFromAreDeclared() {
        // #1093 libs:mail (authelia), libs:updater (git-proxy-api), libs:news (news), the Keyboard/Writer (languagetool) bake their URL from
        // these entries by ab_cloud-libs-shared/libs/fleet-urls.gradle; a rename here would silently bake an empty URL.
        val byId = services.associateBy { it.id }
        for (id in listOf("authelia", "git-proxy-api", "news", "languagetool")) assertTrue("$id must stay declared", byId.containsKey(id))
        assertEquals("news-gdelt.mesh.diegonmarcos.com", byId.getValue("news").meshDns)
        assertTrue(byId.getValue("news").hasPublic)
    }

    @Test fun whatIsBakedIntoTheAppIsTheFile() {
        val baked = String(java.util.Base64.getDecoder().decode(BuildConfig.FLEET_ENDPOINTS_B64))
        assertEquals(services, FleetEndpointsContract.parseDecl(baked))
    }

    @Test fun everyServiceNamesItsFleetDeclarationAndAServiceWithoutAPublicUrlIsMeshOnly() {
        val arr = declared.getJSONArray("services")
        for (i in 0 until arr.length()) assertTrue(arr.getJSONObject(i).getString("source").startsWith("cloud-u-containers/"))
        for (s in services.filter { !it.hasPublic }) assertEquals(FleetEndpointsContract.POLICY_MESH_ONLY, s.policy)
        for (s in services) assertTrue("mesh names are .mesh.diegonmarcos.com names", s.meshDns.endsWith(".mesh.diegonmarcos.com"))
    }

    @Test fun noSecretIsDeclared() {
        val text = declared.toString().lowercase()
        for (word in listOf("bearer\":", "token\":", "password", "secret\":", "api_key", "authorization"))
            assertFalse("the declaration holds '$word'", text.contains(word))
    }

    @Test fun everyHttpMeshEndpointHasANamedCleartextEntryAndTheWholeAppTldIsNeverOpened() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
        val cleartext = Regex("<domain-config cleartextTrafficPermitted=\"true\">(.*?)</domain-config>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).flatMap { m -> Regex("<domain[^>]*>([^<]+)</domain>").findAll(m.groupValues[1]).map { it.groupValues[1].trim() } }.toSet()
        for (s in services.filter { it.meshScheme == "http" })
            assertTrue("${s.meshDns} is http on the mesh and not named in network_security_config.xml", s.meshDns in cleartext)
        assertFalse("`app` is a public gTLD: name hosts, never the TLD", "app" in cleartext)
        assertFalse(Regex("<domain includeSubdomains=\"true\">(app|\\*\\.app)</domain>").containsMatchIn(xml))
        assertTrue("the base config stays TLS-only", xml.contains("<base-config cleartextTrafficPermitted=\"false\">"))
    }

    @Test fun theStatusPageThatShowsThemIsInTheNavigation() {
        val status = MainActivity.NAV.section("connect")!!.page("status")!!
        assertEquals(listOf("connections", "apps", "endpoints"), status.pages.map { it.id })
        assertNotNull(MainActivity.NAV.section("connect")!!.page("endpoints"))
    }

    @Test fun theVaultIsDeclaredMtlsAndTheAuthHostIsSsoWithItsAliases() {
        val byId = services.associateBy { it.id }
        assertEquals(FleetEndpointsContract.AUTH_MTLS, byId.getValue("vault").auth)
        assertEquals("vault.diegonmarcos.com", byId.getValue("vault").publicDomain)
        assertEquals("vaultwarden.mesh.diegonmarcos.com", byId.getValue("vault").meshDns)
        assertEquals(FleetEndpointsContract.AUTH_SSO, byId.getValue("authelia").auth)
        assertEquals(listOf("sso.diegonmarcos.com", "account.diegonmarcos.com"), byId.getValue("authelia").aliases)
        for (s in services.filter { it.auth != FleetEndpointsContract.AUTH_BEARER }) assertTrue("${s.id}: a non-bearer service has a public host", s.hasPublic)
    }

    @Test fun noStaleAppTldNameIsDeclaredAnywhere() {
        val text = declared.toString()
        assertFalse("a `.app` host is a public TLD name", Regex("[a-z0-9-]+\\.app\"").containsMatchIn(text))
        assertTrue(services.none { it.meshDns.endsWith(".app") || it.publicDomain.endsWith(".app") })
    }

    @Test fun theSshHostsAreDeclaredForGithubAndOurGiteaOverTheMesh() {
        val hosts = com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.parseSshHosts(declared.toString(), services, "~/.ssh/id_ed25519").associateBy { it.host }
        assertEquals("github.com", hosts.getValue("github.com").hostname); assertEquals(22, hosts.getValue("github.com").port)
        assertEquals("gitea.mesh.diegonmarcos.com", hosts.getValue("gitea").hostname); assertEquals(2222, hosts.getValue("gitea").port)
        assertEquals("git", hosts.getValue("gitea").user)
    }

    @Test fun theSetupPagesAreInTheNavigationAndRenderFromTheDeclarationNotAHandTypedList() {
        val setup = MainActivity.NAV.section("connect")!!.page("fleetsetup")!!
        assertEquals(listOf("services", "drive", "push", "perms", "adb-shell"), setup.pages.map { it.id })  // #1438 Push / Perms / ADB Shell moved here
        val src = File("src/main/java/com/diegonmarcos/cloudaccount/hub/FleetSetupPages.kt").readText()
        assertFalse("Setup names no host: every URL comes from the declaration", Regex("https?://|diegonmarcos\\.com|\\.mesh\\.").containsMatchIn(src))
        assertTrue(src.contains("FleetEndpointsRuntime.services()"))
    }
}
