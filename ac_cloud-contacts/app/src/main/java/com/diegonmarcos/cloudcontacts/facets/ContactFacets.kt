package com.diegonmarcos.cloudcontacts.facets

/**
 * #1671 (the #1625 / #1626 layout, moved here from Cloud Account): the one place Cloud Contacts decides whether a merged
 * person is a BUSINESS (a company / organization) or a PERSON, which app / account a contact ORIGINATED from (the People
 * filter chips: Social Media, Mail, Phone, ...), and which GROUPS (Friends, Family, Job, Favorites by default) a person
 * is in. Pure Kotlin over plain values (no Android, no org.json), so the bottom-nav sections Groups | Business | All |
 * People are all unit-testable functions of the merged list ContactsBridge already builds (libs:contacts MergeEngine).
 *
 * Adapted to Contacts' own model: a person is a merge of several [RawContact][com.diegonmarcos.superapp.contacts.RawContact]
 * sources, each named by a source key ("local", "gmail:<account>", a RawContacts.ACCOUNT_TYPE such as "com.whatsapp",
 * "linkedin", "instagram"), and carries `org` / `title`. So a person has a SET of origins, not one.
 */

/** The broad place a contact came from: the People filter's category chips. */
enum class OriginCategory(val label: String) {
    SOCIAL("Social Media"),
    MAIL("Mail"),
    PHONE("Phone"),
    OTHER("Other"),
}

/**
 * The app / account a contact came from. [keys] are the source keys / Android account types that map to it (matched
 * exactly, or as a prefix followed by "." or ":"; the longest match wins, so "com.google.android.gm.exchange" is not
 * read as plain "com.google").
 */
enum class ContactOrigin(val label: String, val category: OriginCategory, val keys: List<String> = emptyList()) {
    WHATSAPP("WhatsApp", OriginCategory.SOCIAL, listOf("com.whatsapp", "whatsapp")),
    TELEGRAM("Telegram", OriginCategory.SOCIAL, listOf("org.telegram.messenger", "org.thunderdog.challegram", "telegram")),
    SIGNAL("Signal", OriginCategory.SOCIAL, listOf("org.thoughtcrime.securesms", "signal")),
    INSTAGRAM("Instagram", OriginCategory.SOCIAL, listOf("com.instagram.android", "instagram")),
    FACEBOOK("Facebook", OriginCategory.SOCIAL, listOf("com.facebook.messenger", "com.facebook.katana", "facebook")),
    X("X", OriginCategory.SOCIAL, listOf("com.twitter.android", "twitter")),
    LINKEDIN("LinkedIn", OriginCategory.SOCIAL, listOf("com.linkedin.android", "linkedin")),
    MATRIX("Matrix", OriginCategory.SOCIAL, listOf("im.vector.app", "matrix")),
    GMAIL("Gmail", OriginCategory.MAIL, listOf("gmail", "com.google")),
    MAIL("Mail", OriginCategory.MAIL, listOf("com.google.android.gm.exchange", "com.android.exchange", "com.microsoft.office.outlook", "com.microsoft.exchange", "eu.faircode.email", "mail")),
    PHONE("Phone", OriginCategory.PHONE, listOf("local", "vnd.sec.contact.phone", "com.android.contacts", "com.android.localphone")),
    SIM("SIM", OriginCategory.PHONE, listOf("com.android.contacts.sim", "com.android.sim", "vnd.sec.contact.sim")),
    OTHER("Other", OriginCategory.OTHER),
}

/** What the facets need of a merged person: its id, name, org and source keys (ContactsBridge maps a Person onto it). */
data class Facet(val id: String, val name: String, val org: String = "", val sources: List<String> = emptyList())

object ContactFacets {
    /** The groups that exist out of the box (#1625): Friends, Family, Job, plus Favorites. */
    val DEFAULT_GROUPS: List<String> = listOf("Friends", "Family", "Job", "Favorites")

    /** Name endings that mark an organization even when the org field is empty ("Acme Ltd" saved as a name). */
    private val COMPANY_SUFFIX = Regex(
        """(?i)(^|[\s,.])(ltd|limited|inc|incorporated|llc|llp|gmbh|s\.a|s\.l|s\.a\.s|srl|plc|corp|corporation|company|pty)\.?$""",
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /**
     * BUSINESS: the contact IS a company, not a person who works at one. In Contacts' model a person's `org` is very
     * often their employer (every LinkedIn connection carries one), so `org` alone does not make a business: the
     * contact is a business when it has no person name of its own (blank, or the same as its org), or
     * when its name carries a company form (Ltd, Inc, GmbH, S.A., ...).
     */
    fun isBusiness(f: Facet): Boolean {
        val name = norm(f.name)
        val org = norm(f.org)
        if (COMPANY_SUFFIX.containsMatchIn(f.name.trim())) return true
        if (org.isEmpty()) return false
        return name.isEmpty() || name == org
    }

    fun all(list: List<Facet>): List<Facet> = list
    fun business(list: List<Facet>): List<Facet> = list.filter { isBusiness(it) }
    fun people(list: List<Facet>): List<Facet> = list.filter { !isBusiness(it) }

    /** The origin of one source key ("local", "gmail:me@x", "com.whatsapp", "linkedin", ...). Blank = the phone. */
    fun classifyOrigin(source: String?): ContactOrigin {
        val t = source?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return ContactOrigin.PHONE
        return ContactOrigin.entries
            .flatMap { o -> o.keys.filter { k -> t == k || t.startsWith("$k.") || t.startsWith("$k:") }.map { it to o } }
            .maxByOrNull { it.first.length }?.second ?: ContactOrigin.OTHER
    }

    /** Every origin of a person (one per distinct source), in enum order. */
    fun origins(f: Facet): List<ContactOrigin> {
        val have = f.sources.map { classifyOrigin(it) }.toSet().ifEmpty { setOf(ContactOrigin.OTHER) }
        return ContactOrigin.entries.filter { it in have }
    }

    /** PEOPLE narrowed to one origin or one category; null = no filter. */
    fun peopleBy(list: List<Facet>, origin: ContactOrigin? = null, category: OriginCategory? = null): List<Facet> =
        people(list).filter { p -> (origin == null || origin in origins(p)) && (category == null || origins(p).any { it.category == category }) }

    /** The origins present among the People, in enum order: the People origin chips are built from these. */
    fun originsPresent(list: List<Facet>): List<ContactOrigin> {
        val have = people(list).flatMap { origins(it) }.toSet()
        return ContactOrigin.entries.filter { it in have }
    }

    /** The categories present among the People (Social Media, Mail, Phone, Other), in category order. */
    fun categoriesPresent(list: List<Facet>): List<OriginCategory> {
        val have = originsPresent(list).map { it.category }.toSet()
        return OriginCategory.entries.filter { it in have }
    }

    /** The group names to show: the defaults first, then any other group someone is assigned to (sorted). */
    fun groupNames(assigned: Map<String, Set<String>>): List<String> =
        DEFAULT_GROUPS + assigned.values.flatten().toSortedSet().filter { it !in DEFAULT_GROUPS }

    /** The members of [group] among [list]. */
    fun inGroup(list: List<Facet>, assigned: Map<String, Set<String>>, group: String): List<Facet> =
        list.filter { group in assigned[it.id].orEmpty() }

    /** Assign / unassign [id] to [group]; an id left in no group is dropped from the map. A blank group is ignored. */
    fun assign(assigned: Map<String, Set<String>>, id: String, group: String, on: Boolean): Map<String, Set<String>> {
        val g = group.trim()
        if (g.isEmpty() || id.isEmpty()) return assigned
        val next = if (on) assigned[id].orEmpty() + g else assigned[id].orEmpty() - g
        return if (next.isEmpty()) assigned - id else assigned + (id to next)
    }

    /**
     * The persisted form of the group assignment: one line per person, `id<TAB>group<US>group...`. Plain text so the
     * store needs no JSON library; a malformed line is skipped, never thrown on.
     */
    fun encode(assigned: Map<String, Set<String>>): String =
        assigned.entries.filter { it.value.isNotEmpty() && '\t' !in it.key && '\n' !in it.key }.sortedBy { it.key }
            .joinToString("\n") { (id, gs) -> id + "\t" + gs.filter { '\n' !in it && '\u001F' !in it }.sorted().joinToString("\u001F") }

    fun decode(text: String?): Map<String, Set<String>> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Set<String>>()
        for (line in text.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val gs = line.substring(tab + 1).split('\u001F').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (gs.isNotEmpty()) out[line.substring(0, tab)] = gs
        }
        return out
    }
}
