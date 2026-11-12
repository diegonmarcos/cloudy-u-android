package com.diegonmarcos.superapp.configs

import android.content.Context
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test

/** #1682 P3 step 4b: the host hooks start as "no keeper" (off, a switch is a no-op) and an installed host replaces them. */
class AdbOwnerHostTest {
    @After fun reset() { AdbOwnerHost.keeper = AdbOwnerHost.NoKeeper }

    @Test fun defaultIsNoKeeper() = assertSame(AdbOwnerHost.NoKeeper, AdbOwnerHost.keeper)

    @Test fun installedHostReplacesTheDefault() {
        val host = object : AdbOwnerHost.Keeper {
            override fun enabled(ctx: Context) = true
            override fun setEnabled(ctx: Context, on: Boolean) {}
        }
        AdbOwnerHost.keeper = host
        assertSame(host, AdbOwnerHost.keeper)
    }
}
