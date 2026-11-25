package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.appstore.PhoneAppActions
import org.json.JSONObject
import java.io.File

/**
 * #1566 the fleet-private datasets are not APK assets: CI materialises them into fleet-private-data/ (FLEET_PRIVATE_DIR overrides) and a
 * test that needs one puts it where the production reader looks first.
 */
object FleetPrivateFixtures {
    fun file(name: String): File = File(System.getenv("FLEET_PRIVATE_DIR") ?: "../../fleet-private-data", name)

    /** The install-source map as the Store reads it (PhoneAppActions.sources). */
    fun installSources() {
        PhoneAppActions.testSources = JSONObject(file(PhoneAppActions.SOURCES_FILE).readText())
    }
}
