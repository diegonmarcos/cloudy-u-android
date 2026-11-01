package com.diegonmarcos.cgomniverse

/**
 * Where the app opens: the launcher intent may carry `section` (a declared section id, e.g. store or galaxy) and `world` (a Store
 * world id, opened (or focused) as a tab in Galaxy). The emulator proof uses them to open Store and Galaxy directly (`am start -e section store`).
 * Anything absent or unknown falls back to the declared default page, so a bad extra can never open a page that does not exist.
 */
object Launch {
    const val EXTRA_SECTION = "section"
    const val EXTRA_WORLD = "world"

    /** The deep link `cgomniverse://world/<store id>` names a world the same way the `world` extra does. */
    const val SCHEME = "cgomniverse"
    const val HOST_WORLD = "world"

    fun worldFromLink(scheme: String?, host: String?, path: String?): String? =
        if (scheme == SCHEME && host == HOST_WORLD) path?.trim('/')?.takeIf { it.isNotEmpty() } else null

    data class Start(val section: String, val worldId: String?)

    fun resolve(section: String?, world: String?, sections: Set<String>, worlds: Set<String>, default: String): Start {
        val w = world?.takeIf { it in worlds }
        val s = when {
            w != null -> Decl.SECTION_GALAXY
            section != null && section in sections -> section
            else -> default
        }
        return Start(s, w)
    }
}
