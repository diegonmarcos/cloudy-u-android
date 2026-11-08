package com.diegonmarcos.superapp.notificationcenter

import org.json.JSONArray
import org.json.JSONObject

/**
 * #515 — `build.json::ui.notification_center`, resolved ONCE for everybody.
 *
 * Three things used to disagree about what a badge is: the Push pane drew all
 * eight producers because it had no way to tell a shade badge from a channel
 * inventory entry, `App.onCreate` hardcoded the one service it happened to
 * know about, and nothing at all knew which producers had to come back after
 * an update. Three readers, three answers, and the gap between them is exactly
 * where Quickmarks, Media and Alerts went missing: all three are owned by
 * FloatingNavService, which had no restart path, while KdeStatusService had
 * one because someone wrote its start call into App.onCreate by hand.
 *
 * So this is the only place that reads the declaration. [badges] is what the
 * pane renders, [restartServices] is what [BadgeRestartReceiver] re-ensures,
 * and both are DERIVED from the same list — adding a badge is a build.json
 * edit and nothing else.
 *
 * Deliberately free of `Context` and `BuildConfig`: callers hand it the JSON
 * text. That is what lets the unit test drive it with a declaration of its own
 * and assert on the RESOLVED set rather than grepping a file for a word.
 */
object BadgeDeclaration {

    /** One customization control, as declared. The pane maps `type` to a
     *  widget; it never knows which badge it is drawing. */
    data class Option(
        val key: String,
        val type: String,
        val label: String,
        val default: String,
        val options: List<String>,
    )

    /**
     * One thing a badge quotes. #517: the Markets badge's four instruments are
     * DATA — swapping WTI (`CL=F`) for Brent (`BZ=F`), reordering them or
     * changing a caption is an edit to `build.json` and no Kotlin at all.
     *
     * [label] is not cosmetic. Yahoo's `BRL=X` is the USD->BRL cross — reais
     * per dollar, ~5.x — so the caption has to run the same way round as the
     * number or a correct 5.14 reads as a broken 0.19. [url] is declared
     * rather than built from [symbol] because the ticker page is Yahoo's URL
     * shape, not ours, and a string-built URL is a silent 404 the day they
     * change it.
     */
    data class Instrument(
        val symbol: String,
        val label: String,
        val decimals: Int,
        val url: String,
    )

    /**
     * #1068 — how the Notify page DRAWS this badge as a One UI live card: [kind] picks the card body (`level` a battery-style
     * progress, `quotes` the instruments as value tiles, `alerts` the fleet alert count and latest, `media` a transport line,
     * `text` the posted notification's own lines), [accent] tints its header glyph and progress, and [rank] is its place on the page (lower first; ties keep declaration order). Data, not Kotlin: a badge
     * added to the declaration appears on the page as a card with no edit anywhere else.
     */
    data class Card(val kind: String = "text", val accent: String = "", val rank: Int = Int.MAX_VALUE)

    data class Badge(
        val id: String,
        val label: String,
        val subtitle: String,
        val icon: String,
        val surface: String,
        val channel: String,
        val owner: String,
        val shows: String,
        val enabled: Boolean,
        /** Does this producer put a PERSISTENT entry in the notification
         *  centre? The one field that splits a badge from plumbing. */
        val isBadge: Boolean,
        /** Must it survive an app update and a reboot? */
        val persistent: Boolean,
        /** Service to (re-)ensure, fully qualified. Blank = nothing to start. */
        val service: String,
        /** Grants this badge cannot post without — so a dead badge can say WHY. */
        val requires: List<String>,
        val notBadgeReason: String,
        val customization: List<Option>,
        /** What this badge quotes, in declaration order. Empty for every badge
         *  that is not quoting anything. */
        val instruments: List<Instrument> = emptyList(),
        val card: Card = Card(),
        /** Retired ids (and old deep-link names) that still resolve to this badge. */
        val aliases: List<String> = emptyList(),
    )

    /**
     * #777 — one shade group (`ui.notification_center.groups`): the bundle its
     * [members] (producer ids) are posted under. [alerts] marks the fleet
     * alerts group, so nothing has to recognise it by name.
     */
    data class Group(
        val id: String,
        val label: String,
        val members: List<String>,
        val alerts: Boolean,
        val maxInShade: Int,
    )

    /** The declared groups, in declared (= default) order. A group with no id
     *  or no members is dropped: it could only ever post an empty summary. A
     *  member named by two groups stays in the FIRST — one notification has
     *  one group key. */
    fun groups(json: String): List<Group> {
        val arr = runCatching { JSONObject(json) }.getOrNull()?.optJSONArray("groups") ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val members = strings(o.optJSONArray("members")).filter { seen.add(it) }
            if (id.isBlank() || members.isEmpty()) continue
            out += Group(
                id = id,
                label = o.optString("label", id),
                members = members,
                alerts = o.optBoolean("alerts", false),
                maxInShade = o.optInt("max_in_shade", 20).coerceIn(1, 40),
            )
        }
        return out
    }

    /** Parse the whole declaration. A producer with no id or no label is
     *  dropped rather than drawn half-blank — that is a parse error, not a
     *  producer, and a half-blank row on a settings page is invisible. */
    fun parse(json: String): List<Badge> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("producers") ?: return emptyList()
        val out = mutableListOf<Badge>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val label = o.optString("label")
            if (id.isBlank() || label.isBlank()) continue
            out += Badge(
                id = id,
                label = label,
                subtitle = o.optString("subtitle", ""),
                icon = o.optString("icon", ""),
                surface = o.optString("surface", ""),
                channel = o.optString("channel", ""),
                owner = o.optString("owner", ""),
                shows = o.optString("shows", ""),
                enabled = o.optBoolean("enabled", false),
                isBadge = o.optBoolean("badge", false),
                persistent = o.optBoolean("persistent", false),
                service = o.optString("service", ""),
                requires = strings(o.optJSONArray("requires")),
                notBadgeReason = o.optString("not_badge_reason", ""),
                customization = options(o.optJSONArray("customization")),
                instruments = instruments(o.optJSONArray("instruments")),
                card = o.optJSONObject("card")?.let { Card(it.optString("kind", "text"), it.optString("accent", ""), it.optInt("rank", Int.MAX_VALUE)) } ?: Card(),
                aliases = strings(o.optJSONArray("aliases")),
            )
        }
        return out
    }

    /** The badge [id] names: its own id, else the badge that declares [id] as a retired alias. */
    fun resolve(all: List<Badge>, id: String): Badge? =
        all.firstOrNull { it.id == id } ?: all.firstOrNull { id in it.aliases }

    /** What Configs ▸ Panel ▸ Push renders — one box in section 1 and one
     *  customization menu in section 2 per entry, in declaration order. */
    fun badges(all: List<Badge>): List<Badge> = all.filter { it.isBadge }

    /**
     * The owner's page order: [Card.rank] ascending, ties in declaration order
     * (#1068). The render honours this so once `card.rank` is set in build.json
     * the sequence is exact and never map/hash/arbitrary. [declared] is the full
     * declaration (it fixes the tie order); [subset] is the slice to order (a
     * group's members, or every badge). Ranks default to [Int.MAX_VALUE], so an
     * unranked set keeps pure declaration order.
     */
    fun rankOrder(declared: List<Badge>, subset: List<Badge>): List<Badge> {
        val idx = declared.withIndex().associate { (i, b) -> b.id to i }
        return subset.sortedWith(compareBy({ it.card.rank }, { idx[it.id] ?: Int.MAX_VALUE }))
    }

    /**
     * The services that must be running for every persistent badge, resolved
     * and de-duplicated — FloatingNavService owns three badges and must be
     * started once, not three times.
     *
     * A badge the owner has switched off is not in here: "declared off" is a
     * decision, and re-ensuring it would override the owner on every reboot.
     */
    fun restartServices(all: List<Badge>): List<String> =
        all.filter { it.isBadge && it.persistent && it.enabled && it.service.isNotBlank() }
            .map { it.service }
            .distinct()

    /** Badges a given service is responsible for — the pane uses this to
     *  attribute one dead service to every badge it took down with it. */
    fun badgesOf(all: List<Badge>, service: String): List<Badge> =
        all.filter { it.isBadge && it.service == service }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    /** An instrument with no symbol, no caption or no ticker page is dropped
     *  for the same reason a half-blank producer is: a badge row captioned
     *  nothing, or one whose tap goes nowhere, is worse than one row fewer. */
    private fun instruments(a: JSONArray?): List<Instrument> {
        if (a == null) return emptyList()
        val out = mutableListOf<Instrument>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol")
            val label = o.optString("label")
            val url = o.optString("url")
            if (symbol.isBlank() || label.isBlank() || url.isBlank()) continue
            out += Instrument(
                symbol = symbol,
                label = label,
                // Coerced, not trusted: String.format with a negative
                // precision throws, and that would take the whole badge down
                // over a typo in one row of data.
                decimals = o.optInt("decimals", 2).coerceIn(0, 8),
                url = url,
            )
        }
        return out
    }

    private fun options(a: JSONArray?): List<Option> {
        if (a == null) return emptyList()
        val out = mutableListOf<Option>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            out += Option(
                key = key,
                type = o.optString("type", "toggle"),
                label = o.optString("label", key),
                // Declared defaults are booleans for toggles and strings for
                // choices; normalising to text here keeps one storage shape.
                default = o.opt("default")?.toString() ?: "",
                options = strings(o.optJSONArray("options")),
            )
        }
        return out
    }
}
