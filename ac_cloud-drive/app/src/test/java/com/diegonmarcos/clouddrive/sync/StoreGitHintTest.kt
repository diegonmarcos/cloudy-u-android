package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1436 the phone's three cld.termux failure lines each carry what to do in the Logs line. */
class StoreGitHintTest {
    @Test fun rootfsFallbackSaysItRepairsItself() {
        val h = StoreGit.hint("cloud-rootfs: rootfs.tar.zst is missing and no unpacked rootfs matches 5e65d6f0\ncloud-rootfs: starting the plain Termux shell instead")
        assertTrue(h, "repaired automatically" in h && "reinstall cloud-lib-rootfs-termux" in h)
    }

    @Test fun missingGitSaysPkgInstall() {
        assertTrue(StoreGit.hint("/bin/sh: git: inaccessible or not found").contains("pkg install git"))
    }

    @Test fun readOnlyRootSaysUpdate() {
        assertTrue(StoreGit.hint("mkdir: cannot create directory '/root': Read-only file system").contains("update Cloud Drive and cld.termux"))
    }

    @Test fun anUnrelatedFailureGetsNoHint() {
        assertEquals("", StoreGit.hint("fatal: repository 'https://x/y' not found"))
    }
}
