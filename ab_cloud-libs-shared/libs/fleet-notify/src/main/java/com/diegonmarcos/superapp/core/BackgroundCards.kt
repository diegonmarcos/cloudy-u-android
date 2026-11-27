package com.diegonmarcos.superapp.core

/**
 * #1965 THE declaration of every "Background Apps" card: which fields and which buttons each app's card carries. One place, read
 * by the apps that build the cards (they use these keys, never their own strings) and by the tests that prove each built card
 * carries every declared field and button (rule 16: a field dropped from a card is a red test, not a silent loss).
 *
 * A field key may repeat (Mail: one "Folder" per 0*-9* category; Store: one "App" per update row). An app not declared here
 * ([OTHER]: the terminals, Drive's mounts, any later keep-alive) shows its existing keep-alive lines as free fields and its
 * own buttons, so it still joins the group with its key state.
 */
object BackgroundCards {
    // Card kinds.
    const val MAIL = "mail"
    const val STORE = "store"
    const val NAV = "nav"
    const val WEBSERVER = "webserver"
    const val OTHER = "other"

    // Button kinds (the hub draws the icon for each, BackgroundApps.iconFor).
    const val SYNC = "sync"
    const val OPEN = "open"
    const val PAUSE = "pause"
    const val STOP = "stop"

    data class Spec(val kind: String, val app: String, val fields: List<String>, val buttons: List<String>)

    object Mail {
        const val UNREAD = "Unread"
        const val FOLDER = "Folder"
        const val LAST_SYNC = "Last sync"
        const val PUSH = "Push"
        const val LATEST = "Latest"
    }

    object Store {
        const val PROGRESS = "Progress"
        const val APP = "App"
        const val SPEED = "Speed"
        const val STATE = "State"
    }

    object Nav {
        const val ROUTE = "Route"
        const val NEXT_TURN = "Next turn"
        const val DISTANCE = "Distance"
        const val ETA = "ETA"
        const val TRACKING = "Tracking"
    }

    object Webserver {
        const val STATE = "State"
        const val URL = "URL"
        const val PORTS = "Ports"
        const val MESH = "Mesh"
        const val CLIENTS = "Clients"
        const val UPTIME = "Uptime"
    }

    val DECLARED: Map<String, Spec> = listOf(
        // Folder: one per category folder 0*-9* that the watched accounts have; Latest only when the privacy setting shows one.
        Spec(MAIL, "Cloud Mail", listOf(Mail.UNREAD, Mail.FOLDER, Mail.LAST_SYNC, Mail.PUSH, Mail.LATEST), listOf(SYNC, OPEN, PAUSE)),
        Spec(STORE, "Cloud Store", listOf(Store.PROGRESS, Store.APP, Store.SPEED, Store.STATE), listOf(STOP, OPEN)),
        // Route / Next turn / Distance / ETA while the Navigation tab runs a route; Tracking always.
        Spec(NAV, "Cloud Nav", listOf(Nav.ROUTE, Nav.NEXT_TURN, Nav.DISTANCE, Nav.ETA, Nav.TRACKING), listOf(OPEN)),
        Spec(WEBSERVER, "Cloud C3 WebServer", listOf(Webserver.STATE, Webserver.URL, Webserver.PORTS, Webserver.MESH, Webserver.CLIENTS, Webserver.UPTIME), listOf(STOP, OPEN)),
        Spec(OTHER, "", emptyList(), listOf(STOP)),
    ).associateBy { it.kind }

    fun spec(kind: String): Spec = DECLARED[kind] ?: DECLARED.getValue(OTHER)

    /** The declared fields [card] does not carry; empty is a complete card. */
    fun missingFields(card: BackgroundApps.Card): List<String> = spec(card.kind).fields - card.keys().toSet()

    /** The declared buttons [card] does not carry. */
    fun missingButtons(card: BackgroundApps.Card): List<String> = spec(card.kind).buttons - card.buttonKinds().toSet()

    /** "2h 05m", "4m 10s", "12s": a webserver uptime. */
    fun uptime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when { h > 0 -> "${h}h %02dm".format(m); m > 0 -> "${m}m %02ds".format(sec); else -> "${sec}s" }
    }

    /**
     * The WebServer's fields: [up] the server answered on [port] ([url] what to open), [ports] / [mesh] the existing status lines,
     * [clients] connected clients (null = not readable on this Android: said so, never a made-up 0), [uptimeMs] since start
     * ([since] the start time, local).
     */
    fun webserverFields(up: Boolean?, url: String, ports: String, mesh: String, clients: Int?, uptimeMs: Long, since: String = ""): List<BackgroundApps.Field> = listOf(
        BackgroundApps.Field(Webserver.STATE, when (up) { true -> "serving"; false -> "starting, port not answering yet"; null -> "starting" }),
        BackgroundApps.Field(Webserver.URL, url),
        BackgroundApps.Field(Webserver.PORTS, ports),
        BackgroundApps.Field(Webserver.MESH, mesh),
        BackgroundApps.Field(Webserver.CLIENTS, clients?.toString() ?: "not readable on this Android"),
        // The card refreshes on events, never on a timer: the start time makes the figure readable later too.
        BackgroundApps.Field(Webserver.UPTIME, uptime(uptimeMs) + if (since.isNotEmpty()) " · since $since" else ""),
    )

    /**
     * Established TCP connections to local [port], from /proc/net/tcp{,6} text ([tables]); null when no table was readable
     * (Android 10+ hides /proc/net from apps: then the card says so).
     */
    fun clientsOn(port: Int, tables: List<String?>): Int? {
        val readable = tables.filterNotNull()
        if (readable.isEmpty()) return null
        val hexPort = ":%04X".format(port)
        return readable.sumOf { t ->
            t.lineSequence().drop(1).count { l ->
                val c = l.trim().split(Regex("\\s+"))
                c.size > 3 && c[1].endsWith(hexPort) && c[3] == "01"   // local address ends with the port, state ESTABLISHED
            }
        }
    }
}
