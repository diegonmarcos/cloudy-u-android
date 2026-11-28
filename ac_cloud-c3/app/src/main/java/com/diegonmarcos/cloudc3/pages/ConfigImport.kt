package com.diegonmarcos.cloudc3.pages

import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #1983 The "this device has no C3 token" reason is an action: it opens Configs > Profile > Config import. The import lives in Cloud Account (the SuperApp's
 * Configs shortcut opens it at section `connect`, page `import-configs`); this is the same OPEN intent, so a phone without a token is one tap from the
 * place that gives it one. Nothing is read or written here.
 */
object ConfigImport {
    const val SECTION = "connect"
    const val PAGE = "import-configs"
    val ACTION get() = "${AccountClient.PKG}.OPEN"
    val PACKAGE get() = AccountClient.PKG
    const val WHERE = "Configs → Profile → Config import"

    /** The extras the OPEN intent carries. */
    fun extras(): Map<String, String> = mapOf("section" to SECTION, "page" to PAGE)

    /** Null when Cloud Account opened at the import page, else the sentence to show (never throws). */
    fun open(ctx: Context): String? = runCatching {
        val i = Intent(ACTION).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras().forEach { (k, v) -> i.putExtra(k, v) }
        ctx.startActivity(i)
        null
    }.getOrElse { "Cloud Account did not open (${it.javaClass.simpleName}): install it, then $WHERE" }
}
