package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.adbdebug.ChannelAutoArm
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #1833 whichever app owns ADB, its implicit-trigger gate (ChannelAutoArm) reads and writes the keeper switch the fleet clients see
 * (keeper_get / keeper_set). Before, the webserver owner never installed the host switch, so its gate followed an undeclared ON
 * default while the SuperApp showed the keep-alive OFF, and the pairing notification kept coming.
 */
class OwnerKeepAliveWiringTest {
    @Test fun theOwnerWiresOneKeepAliveSwitch() {
        ChannelAutoArm.hostSwitch = null
        ChannelAutoArm.hostSet = null
        AdbReadinessOwner.wireKeepAlive()
        assertNotNull("the gate must read the owner's keeper switch", ChannelAutoArm.hostSwitch)
        assertNotNull("the owner's page must write the same switch", ChannelAutoArm.hostSet)
    }
}
