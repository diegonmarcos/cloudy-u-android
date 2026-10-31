package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A battery row's name: system role, else label · package; never a bare number or a cut label. */
class BatteryLabelsTest {

    @Test fun `root, system and kernel-side uids get their role`() {
        assertEquals("Android system (root, uid 0)", BatteryLabels.display(0, null, emptyList()))
        assertEquals("Android system (uid 1000)", BatteryLabels.display(1000, "Android System", listOf("android")))
        assertEquals("Phone / radio (uid 1001)", BatteryLabels.display(1001, null, listOf("com.android.phone")))
        assertEquals("Android system service (uid 1066)", BatteryLabels.display(1066, null, emptyList()))
        assertEquals("Isolated process (uid 99123)", BatteryLabels.display(99_123, null, emptyList()))
    }

    @Test fun `the shell uid is the SuperApp's privileged channel, in every user`() {
        assertEquals("Cloud SuperApp privileged channel · adb shell (uid 2000)", BatteryLabels.display(2000, "Shell", listOf("com.android.shell")))
        assertEquals("Cloud SuperApp privileged channel · adb shell (uid 2000)", BatteryLabels.system(1_002_000))
    }

    @Test fun `an app shows label and package, a shared uid counts the others`() {
        assertEquals("Cloud Mail · app.sterna", BatteryLabels.display(10_221, "Cloud Mail", listOf("app.sterna")))
        assertEquals("com.x.y", BatteryLabels.display(10_222, "com.x.y", listOf("com.x.y")))
        assertEquals("com.x.y", BatteryLabels.display(10_222, " ", listOf("com.x.y")))
        assertEquals("Samsung · com.sec.a (+2)", BatteryLabels.display(10_300, "Samsung", listOf("com.sec.a", "com.sec.b", "com.sec.c")))
        assertNull(BatteryLabels.system(10_221))
    }

    @Test fun `a uid nothing owns says so`() {
        assertEquals("Unknown app (uid 10999)", BatteryLabels.display(10_999, null, emptyList()))
    }
}
