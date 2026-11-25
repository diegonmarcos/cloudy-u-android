package com.diegonmarcos.superapp.jsoncanvas

/** JSON Canvas colours: "1".."6" are the presets (red, orange, yellow, green, cyan, purple), anything else must be #RGB or #RRGGBB. Result is 0xAARRGGBB. */
object CanvasColors {
    val presets: Map<String, Long> = mapOf(
        "1" to 0xFFFB464CL, "2" to 0xFFE9973FL, "3" to 0xFFE0DE71L,
        "4" to 0xFF44CF6EL, "5" to 0xFF53DFDDL, "6" to 0xFFA882FFL,
    )

    fun valid(c: String): Boolean = resolve(c) != null

    fun resolve(c: String?): Long? {
        if (c == null) return null
        presets[c]?.let { return it }
        if (!c.startsWith("#")) return null
        val h = c.substring(1)
        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (h.length) {
            6 -> 0xFF000000L or h.toLong(16)
            3 -> { val r = h.substring(0, 1).repeat(2); val g = h.substring(1, 2).repeat(2); val b = h.substring(2, 3).repeat(2); 0xFF000000L or "$r$g$b".toLong(16) }
            else -> null
        }
    }
}
