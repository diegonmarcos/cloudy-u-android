package com.diegonmarcos.capitalfinterm

import java.util.Locale
import org.json.JSONObject

/** #1722 one tile of the market monitor: a symbol of the quote source, its label and how many decimals its price is shown with. */
data class MonitorDecl(val symbol: String, val label: String, val decimals: Int)

/** One red function-menu button of the command line: its id, its number ("96)") and its word. */
data class MenuDecl(val id: String, val number: String, val label: String)

/** The terminal chrome's declaration (data/terminal.json): the monitor's tiles, the menu buttons, the words of the command line and of the status line. */
data class TerminalDecl(val monitor: List<MonitorDecl>, val menu: List<MenuDecl>, val command: Map<String, String>, val status: Map<String, String>) {
    fun commandWord(k: String): String = command[k].orEmpty()
    fun statusWord(k: String): String = status[k].orEmpty()
}

/** #1722 the terminal chrome: pure rules (no Compose, no network). */
object Terminal {
    const val FILE = "terminal.json"
    const val MENU_FUNCTIONS = "functions"
    const val MENU_SEARCH = "search"
    const val MENU_SOURCES = "sources"

    private fun words(o: JSONObject?): Map<String, String> = if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.optString(it) }

    /** The declaration; null when the body is not JSON (the shell then draws no monitor and no menu). A tile without a symbol or a button without an id is left out. */
    fun parse(json: String): TerminalDecl? = runCatching {
        val o = JSONObject(json)
        val m = o.optJSONArray("monitor")
        val b = o.optJSONArray("menu")
        TerminalDecl(
            monitor = if (m == null) emptyList() else (0 until m.length()).mapNotNull { m.optJSONObject(it) }.filter { it.optString("symbol").isNotBlank() }
                .map { MonitorDecl(it.optString("symbol"), it.optString("label").ifBlank { it.optString("symbol") }, it.optInt("decimals", 2)) },
            menu = if (b == null) emptyList() else (0 until b.length()).mapNotNull { b.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
                .map { MenuDecl(it.optString("id"), it.optString("number"), it.optString("label")) },
            command = words(o.optJSONObject("command")), status = words(o.optJSONObject("status")),
        )
    }.getOrNull()

    /** A tile's price with its declared decimals and thousands separators; the dash when the source has not answered. */
    fun price(q: Quote?, decimals: Int): String = if (q == null || !q.last.isFinite()) FinFormat.DASH else String.format(Locale.US, "%,.${decimals.coerceIn(0, 6)}f", q.last)

    /** The message of an unknown mnemonic: the declared sentence with the typed word. */
    fun unknown(decl: TerminalDecl?, word: String): String = (decl?.commandWord("unknown")?.ifBlank { null } ?: "No function {word}.").replace("{word}", word)
}
