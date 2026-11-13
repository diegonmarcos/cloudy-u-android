package com.diegonmarcos.cloudwebserver.owner

import android.content.Context
import androidx.compose.runtime.Composable
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.ui.DRow
import com.diegonmarcos.cloudwebserver.ui.DenseCard

/**
 * #1682 P2b the INACTIVE stand-in of the ADB owner side, compiled into the shipped build while
 * 1_cicd/src/data/adb-owner.json names another app (today the SuperApp). It links no ADB lib, starts
 * nothing and draws nothing: the ADB Shell tab keeps opening the owner's page. src/owner holds the real one (CI verify builds it).
 */
object AdbOwnerSide {
    const val active: Boolean = false
    const val why: String = "the ADB owner is another app (adb-owner.json); this app links no ADB channel"
    fun install(ctx: Context) {}
    val FIELD_HOME: Map<String, String> = emptyMap()
    @Composable fun Page(page: String? = null) {}

    /** #1723 Home's ADB shell status table: this build links no ADB channel, so one tappable row names the owner and opens the ADB Shell. */
    @Composable
    fun HomeStatus(tick: Int, open: (String, String?) -> Unit) {
        DenseCard("ADB shell", "c3_home_adb") {
            DRow("Owner", why, tag = "c3_home_adb_owner") { open(Decl.SECTION_ADB_SHELL, Decl.PAGE_ADB_STATUS) }
        }
    }
}
