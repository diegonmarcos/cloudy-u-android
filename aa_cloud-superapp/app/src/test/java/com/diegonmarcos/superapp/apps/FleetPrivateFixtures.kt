package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime
import java.io.File

/**
 * #1566 the fleet-private datasets are not APK assets: CI materialises them into fleet-private-data/ (FLEET_PRIVATE_DIR overrides) and a
 * test that needs one puts it where the production reader looks first (the seam each reader documents). Text, so every read parses a fresh copy.
 */
object FleetPrivateFixtures {
    fun file(name: String): File = File(System.getenv("FLEET_PRIVATE_DIR") ?: "../../fleet-private-data", name)

    /** The install-source map, the background policy and the Super Saving declaration, as the app reads them. */
    fun install() {
        PhoneAppActions.testSources = file(PhoneAppActions.SOURCES_FILE).readText()
        BgHost.testConfig = file("bg-policy.json").readText()
        SuperSavingRuntime.testDecl = file("super-saving.json").readText()
        com.diegonmarcos.superapp.perms.Perms.testCatalogue = file("perms-catalogue.json").readText()
    }
}
