package com.diegonmarcos.superapp.share

/**
 * #1888 "Send to peer" in the share sheet: the pure routing of a SEND / SEND_MULTIPLE to one paired, reachable peer (JVM tested).
 * [ShareToPeerActivity] does the Android half: it dials the peers, asks, and hands each item to SharePlugin.
 */
object PeerShare {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    sealed class Item {
        data class File(val uri: String) : Item()
        data class Text(val text: String) : Item()
        data class Link(val url: String) : Item()
    }

    /** Every stream is a file; a share with no stream carries its EXTRA_TEXT: a link when it is one http(s) URL, else text. */
    fun items(action: String?, streams: List<String>, text: String?): List<Item> = when {
        action != SEND && action != SEND_MULTIPLE -> emptyList()
        streams.isNotEmpty() -> streams.map { Item.File(it) }
        !text.isNullOrBlank() -> listOf(if (isLink(text)) Item.Link(text.trim()) else Item.Text(text))
        else -> emptyList()
    }

    fun isLink(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://")) && t.none { it.isWhitespace() }
    }

    /** What the activity does once it knows who is reachable: send now to the only peer, ask, or say there is none. */
    sealed class Pick {
        data class Auto(val id: String) : Pick()
        data class Ask(val ids: List<String>) : Pick()
        data object None : Pick()
    }

    fun pick(reachable: List<String>): Pick = when (reachable.size) {
        0 -> Pick.None
        1 -> Pick.Auto(reachable[0])
        else -> Pick.Ask(reachable)
    }

    /** Result line per item: "sent name" / "failed name". */
    fun line(name: String, ok: Boolean): String = (if (ok) "sent " else "failed ") + name
}
