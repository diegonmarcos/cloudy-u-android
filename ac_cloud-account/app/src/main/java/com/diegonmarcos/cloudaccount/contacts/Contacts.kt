package com.diegonmarcos.cloudaccount.contacts

/**
 * #1625 The Cloud Account contacts model: the one place a contact's COMPANY flag and its app/account ORIGIN are
 * decided, so the bottom-nav sections (All, Business, People, Groups) and the People app-origin filters are all
 * pure functions of a contact list — testable without a device and without any colour, Compose or Android type.
 *
 * SOURCE / BACKEND: the Account app has no contacts backend of its own, so [ContactsRepo] is pluggable. On a real
 * phone the default source is empty until a source is connected (device ContactsContract behind the `contacts_read`
 * grant, or an import); [classifyOrigin] already maps the real Android account-type strings, so wiring a source is a
 * small change. [sampleContacts] is obvious-fake demo data used only by tests and previews — nothing is baked into
 * the APK as if it were the owner's.
 */

/** The broad place a contact first came from, used to group the People app-origin filter chips. */
enum class OriginCategory(val label: String) {
    SOCIAL("Social Media"),
    MAIL("Mail"),
    PHONE("Phone"),
    OTHER("Other"),
}

/**
 * The app / account a contact originated from. [accountTypes] are the real Android `RawContacts.ACCOUNT_TYPE`
 * strings [classifyOrigin] recognises, so a device source can be wired later without touching the UI.
 */
enum class ContactOrigin(
    val label: String,
    val category: OriginCategory,
    val accountTypes: List<String> = emptyList(),
) {
    WHATSAPP("WhatsApp", OriginCategory.SOCIAL, listOf("com.whatsapp")),
    TELEGRAM("Telegram", OriginCategory.SOCIAL, listOf("org.telegram.messenger", "org.thunderdog.challegram")),
    SIGNAL("Signal", OriginCategory.SOCIAL, listOf("org.thoughtcrime.securesms")),
    INSTAGRAM("Instagram", OriginCategory.SOCIAL, listOf("com.instagram.android")),
    FACEBOOK("Facebook", OriginCategory.SOCIAL, listOf("com.facebook.messenger", "com.facebook.katana")),
    X("X", OriginCategory.SOCIAL, listOf("com.twitter.android")),
    LINKEDIN("LinkedIn", OriginCategory.SOCIAL, listOf("com.linkedin.android")),
    MAIL("Mail", OriginCategory.MAIL, listOf("com.google", "com.google.android.gm.exchange", "com.android.exchange", "com.microsoft.office.outlook")),
    PHONE("Phone", OriginCategory.PHONE, listOf("vnd.sec.contact.phone", "com.android.contacts", "com.android.localphone", "")),
    DEVICE("Device", OriginCategory.PHONE, listOf("com.android.contacts.sim", "com.android.sim")),
    OTHER("Other", OriginCategory.OTHER),
}

/**
 * One contact. [company] non-blank => a Business contact (the default classifier the ticket asks for: an org/company
 * name makes it a company). [origin] is the app/account it came from (People filters). [groups] are the names of the
 * groups it is assigned to (see [Contacts.DEFAULT_GROUPS]).
 */
data class Contact(
    val id: String,
    val name: String,
    val company: String? = null,
    val origin: ContactOrigin = ContactOrigin.OTHER,
    val groups: Set<String> = emptySet(),
) {
    /** The Business/People split: a contact that carries a company/organization name is a business contact. */
    val isBusiness: Boolean get() = !company.isNullOrBlank()
}

/** All the filtering/grouping the contacts sections draw, as pure functions of a contact list. */
object Contacts {
    /** The default groups that exist out of the box (#1625). Friends / Family / Job, plus Favorites. */
    val DEFAULT_GROUPS: List<String> = listOf("Friends", "Family", "Job", "Favorites")

    /** ALL: every contact. */
    fun all(contacts: List<Contact>): List<Contact> = contacts

    /** BUSINESS: only company/organization contacts. */
    fun business(contacts: List<Contact>): List<Contact> = contacts.filter { it.isBusiness }

    /** PEOPLE: only non-company contacts. */
    fun people(contacts: List<Contact>): List<Contact> = contacts.filter { !it.isBusiness }

    /** PEOPLE filtered by the app/account origin they came from; a null origin means "no filter". */
    fun peopleByOrigin(contacts: List<Contact>, origin: ContactOrigin?): List<Contact> =
        people(contacts).let { ppl -> if (origin == null) ppl else ppl.filter { it.origin == origin } }

    /** The origins actually present among the People, in enum order: the People filter chips are built from these. */
    fun sourcesPresent(contacts: List<Contact>): List<ContactOrigin> {
        val have = people(contacts).map { it.origin }.toSet()
        return ContactOrigin.entries.filter { it in have }
    }

    /** The origin categories present among the People (Social Media, Mail, ...), in category order. */
    fun categoriesPresent(contacts: List<Contact>): List<OriginCategory> {
        val have = people(contacts).map { it.origin.category }.toSet()
        return OriginCategory.entries.filter { it in have }
    }

    /** The members of a named group. */
    fun inGroup(contacts: List<Contact>, group: String): List<Contact> = contacts.filter { group in it.groups }

    /** Every group name that should be shown: the defaults plus any extra a contact is already assigned to. */
    fun groupNames(contacts: List<Contact>): List<String> {
        val extra = contacts.flatMap { it.groups }.toSortedSet().filter { it !in DEFAULT_GROUPS }
        return DEFAULT_GROUPS + extra
    }

    /** Assign/unassign a contact to a group, returning the updated contact (the Groups view's assign action). */
    fun assign(contact: Contact, group: String, on: Boolean): Contact =
        contact.copy(groups = if (on) contact.groups + group else contact.groups - group)

    /**
     * The origin for a device `RawContacts.ACCOUNT_TYPE`, for when a device/import source is wired. Unknown types with
     * an org name still read as a mail/sync account only by their type; the Business split is decided by [Contact.company],
     * not here. Matching is longest-accountType-first so "com.google.android.gm.exchange" wins over "com.google".
     */
    fun classifyOrigin(accountType: String?): ContactOrigin {
        val t = accountType?.trim().orEmpty()
        val byType = ContactOrigin.entries
            .flatMap { o -> o.accountTypes.filter { it.isNotEmpty() && (t == it || t.startsWith("$it.") || t.startsWith("$it:")) }.map { it to o } }
            .maxByOrNull { it.first.length }?.second
        if (byType != null) return byType
        if (t.isEmpty()) return ContactOrigin.PHONE
        return ContactOrigin.OTHER
    }
}

/** The source of contacts for the Account app. Pluggable so a device/import source can replace the empty default. */
interface ContactsRepo {
    fun contacts(): List<Contact>
}

/** The on-device default: empty until a source is connected (see the file header). */
object EmptyContactsRepo : ContactsRepo {
    override fun contacts(): List<Contact> = emptyList()
}

/** A fixed list, for a wired source or for tests/previews. */
class ListContactsRepo(private val items: List<Contact>) : ContactsRepo {
    override fun contacts(): List<Contact> = items
}

/** Obvious-fake demo contacts — tests and @Preview only, never the APK's default source. */
fun sampleContacts(): List<Contact> = listOf(
    Contact("1", "Ada Sample", origin = ContactOrigin.WHATSAPP, groups = setOf("Friends")),
    Contact("2", "Ben Example", origin = ContactOrigin.TELEGRAM, groups = setOf("Friends", "Favorites")),
    Contact("3", "Cara Testing", origin = ContactOrigin.MAIL, groups = setOf("Family")),
    Contact("4", "Dan Placeholder", origin = ContactOrigin.PHONE, groups = setOf("Family")),
    Contact("5", "Eve Signalson", origin = ContactOrigin.SIGNAL),
    Contact("6", "Acme Test Ltd", company = "Acme Test Ltd", origin = ContactOrigin.MAIL, groups = setOf("Job")),
    Contact("7", "Globex Demo Inc", company = "Globex Demo Inc", origin = ContactOrigin.LINKEDIN, groups = setOf("Job")),
    Contact("8", "Initech Fake", company = "Initech", origin = ContactOrigin.PHONE),
)
