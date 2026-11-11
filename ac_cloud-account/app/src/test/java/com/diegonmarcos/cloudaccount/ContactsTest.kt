package com.diegonmarcos.cloudaccount

import com.diegonmarcos.cloudaccount.contacts.Contact
import com.diegonmarcos.cloudaccount.contacts.ContactOrigin
import com.diegonmarcos.cloudaccount.contacts.Contacts
import com.diegonmarcos.cloudaccount.contacts.OriginCategory
import com.diegonmarcos.cloudaccount.contacts.sampleContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1625 the contacts model: the Business split (a company name makes a company), the People app-origin filters built
 * from the sources present, the default groups out of the box, and the account-type origin classifier for a future
 * device/import source. Pure functions, no device.
 */
class ContactsTest {
    private val sample = sampleContacts()

    @Test fun businessIsAContactWithACompanyName() {
        assertTrue(Contact("x", "Acme", company = "Acme Ltd").isBusiness)
        assertFalse(Contact("y", "Ada").isBusiness)
        assertFalse("a blank company is not a business", Contact("z", "Ada", company = "  ").isBusiness)
    }

    @Test fun allBusinessAndPeopleSplitTheList() {
        assertEquals(sample.size, Contacts.all(sample).size)
        val biz = Contacts.business(sample)
        val ppl = Contacts.people(sample)
        assertTrue(biz.all { it.isBusiness })
        assertTrue(ppl.none { it.isBusiness })
        assertEquals(sample.size, biz.size + ppl.size)
        assertEquals(setOf("Acme Test Ltd", "Globex Demo Inc", "Initech Fake"), biz.map { it.name }.toSet())
    }

    @Test fun peopleOriginFiltersComeFromTheSourcesPresent() {
        val sources = Contacts.sourcesPresent(sample)
        // only origins carried by PEOPLE (non-company) contacts, in enum order, no duplicates, no business-only origins
        assertEquals(sources, sources.distinct())
        assertTrue(ContactOrigin.WHATSAPP in sources && ContactOrigin.TELEGRAM in sources && ContactOrigin.MAIL in sources)
        assertTrue("business-only origins are not People sources", ContactOrigin.LINKEDIN !in sources)
        // filtering People by a present origin narrows to it; a null origin keeps all People
        assertEquals(Contacts.people(sample), Contacts.peopleByOrigin(sample, null))
        val wa = Contacts.peopleByOrigin(sample, ContactOrigin.WHATSAPP)
        assertTrue(wa.isNotEmpty() && wa.all { it.origin == ContactOrigin.WHATSAPP && !it.isBusiness })
        assertTrue(OriginCategory.SOCIAL in Contacts.categoriesPresent(sample) && OriginCategory.MAIL in Contacts.categoriesPresent(sample))
    }

    @Test fun defaultGroupsExistAndContactsCanBeAssigned() {
        assertEquals(listOf("Friends", "Family", "Job", "Favorites"), Contacts.DEFAULT_GROUPS)
        // the group list always offers the defaults even when nobody is in them
        assertTrue(Contacts.groupNames(emptyList()).containsAll(Contacts.DEFAULT_GROUPS))
        assertTrue(Contacts.inGroup(sample, "Friends").isNotEmpty())
        val c = Contact("n", "New")
        val assigned = Contacts.assign(c, "Family", true)
        assertTrue("Family" in assigned.groups)
        assertFalse("Family" in Contacts.assign(assigned, "Family", false).groups)
        // an extra group a contact carries is surfaced after the defaults
        val extra = Contacts.groupNames(listOf(Contact("e", "E", groups = setOf("Book club"))))
        assertEquals(Contacts.DEFAULT_GROUPS + "Book club", extra)
    }

    @Test fun originClassifierMapsTheRealAccountTypes() {
        assertEquals(ContactOrigin.WHATSAPP, Contacts.classifyOrigin("com.whatsapp"))
        assertEquals(ContactOrigin.TELEGRAM, Contacts.classifyOrigin("org.telegram.messenger"))
        assertEquals(ContactOrigin.MAIL, Contacts.classifyOrigin("com.google"))
        // a more specific type wins over a prefix it shares
        assertEquals(ContactOrigin.MAIL, Contacts.classifyOrigin("com.google.android.gm.exchange"))
        assertEquals(ContactOrigin.PHONE, Contacts.classifyOrigin("vnd.sec.contact.phone"))
        assertEquals(ContactOrigin.PHONE, Contacts.classifyOrigin(""))
        assertEquals(ContactOrigin.PHONE, Contacts.classifyOrigin(null))
        assertEquals(ContactOrigin.OTHER, Contacts.classifyOrigin("com.unknown.app"))
    }
}
