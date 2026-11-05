package com.diegonmarcos.superapp.xray

/**
 * #1504 The declared X-Ray tree: the pages of build.json section `config`, group `observability` (a unit test keeps this list equal to
 * the declaration). Each page maps to where the export covers it: the walked X-Ray sections, or the group of probes that stands for it.
 */
object XrayTree {
    const val WALKED = "X-Ray (walked page)"

    val PAGES = listOf("xray", "logcat", "perms", "app-about", "battery")

    /** page id → how the export covers it. */
    val COVERAGE: Map<String, String> = mapOf(
        "xray" to WALKED,
        "app-about" to "$WALKED: the App & Build and Repo & Releases groups",
        "logcat" to XrayProbes.G_LOGCAT,
        "perms" to XrayProbes.G_PERMS,
        "battery" to XrayProbes.G_BATTERY,
    )

    fun manifest(pages: List<String>, walked: Boolean): List<XSection> = listOf(
        XSection("Report", "Pages and groups walked", SRC_APP,
            pages.joinToString("\n") { "config/$it -> ${COVERAGE[it] ?: "NOT COVERED"}" } +
                if (walked) "" else "\n(the X-Ray page was not available to walk)"),
    )
}
