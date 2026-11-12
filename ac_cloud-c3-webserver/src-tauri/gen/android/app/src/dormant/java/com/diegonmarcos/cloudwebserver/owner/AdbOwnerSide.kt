package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * #1682 P2b the INACTIVE stand-in of the ADB owner side, compiled into the shipped build while
 * ab_cloud-libs-shared/libs/shell-client/adb-owner.json names another app (today the SuperApp). It links no ADB lib, starts
 * nothing and draws nothing: the ADB Shell tab keeps opening the owner's page. src/owner holds the real one (CI verify builds it).
 */
object AdbOwnerSide {
    const val active: Boolean = false
    const val why: String = "the ADB owner is another app (adb-owner.json); this app links no ADB channel"
    fun install(ctx: Context) {}
    @Composable fun Page() {}
}
