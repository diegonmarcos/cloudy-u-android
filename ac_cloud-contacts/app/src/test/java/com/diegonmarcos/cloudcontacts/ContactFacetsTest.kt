package com.diegonmarcos.cloudcontacts

import com.diegonmarcos.cloudcontacts.facets.ContactFacets
import com.diegonmarcos.cloudcontacts.facets.ContactOrigin
import com.diegonmarcos.cloudcontacts.facets.Facet
import com.diegonmarcos.cloudcontacts.facets.OriginCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1671 the Groups | Business | All | People model of Cloud Contacts: the Business / People split over merged people,
 * the People app-origin filters (per origin and per category), the default groups and their persisted assignment.
 * Obvious-fake data, pure functions, no device.
 */
class ContactFacetsTest {
    private val sample = listOf(
        Facet("p1", "Ada Sample", org = "Nimbus Labs", sources = listOf("local", "gmail:ada@example.com")),
        Facet("p2", "Ben Example", org = "Kepler Systems", sources = listOf("linkedin")),
        Facet("p3", "Cara Testing", sources = listOf("com.whatsapp")),
        Facet("p4", "Dan Placeholder", sources = listOf("org.telegram.messenger", "instagram")),
        Facet("b1", "Acme Test Ltd", sources = listOf("local")),
        Facet("b2", "Globex Demo", org = "Globex Demo", sources = listOf("com.google")),
        Facet("b3", "", org = "Initech", sources = listOf("com.microsoft.office.outlook")),
    )

    @Test fun businessIsAnOrganizationNotAPersonWithAnEmployer() {
        assertTrue(ContactFacets.isBusiness(Facet("x", "Acme Ltd")))
        assertTrue(ContactFacets.isBusiness(Facet("x", "Acme S.A.")))
        assertTrue(ContactFacets.isBusiness(Facet("x", "Globex", org = "Globex")))
        assertTrue(ContactFacets.isBusiness(Facet("x", "  ", org = "Initech")))
        assertFalse("a person with an employer is a person", ContactFacets.isBusiness(Facet("x", "Ada Sample", org = "Nimbus Labs")))
        assertFalse(ContactFacets.isBusiness(Facet("x", "Ana", org = "Banana Co")))
        assertFalse(ContactFacets.isBusiness(Facet("x", "Maria Sa")))
        assertFalse(ContactFacets.isBusiness(Facet("x", "Ada")))
    }

    @Test fun allBusinessAndPeopleSplitTheList() {
        assertEquals(sample, ContactFacets.all(sample))
        val biz = ContactFacets.business(sample).map { it.id }
        val ppl = ContactFacets.people(sample).map { it.id }
        assertEquals(listOf("b1", "b2", "b3"), biz)
        assertEquals(listOf("p1", "p2", "p3", "p4"), ppl)
        assertTrue((biz intersect ppl.toSet()).isEmpty())
        assertEquals(sample.size, biz.size + ppl.size)
    }

    @Test fun originsMapContactsSourceKeys() {
        assertEquals(ContactOrigin.PHONE, ContactFacets.classifyOrigin("local"))
        assertEquals(ContactOrigin.PHONE, ContactFacets.classifyOrigin(""))
        assertEquals(ContactOrigin.PHONE, ContactFacets.classifyOrigin(null))
        assertEquals(ContactOrigin.GMAIL, ContactFacets.classifyOrigin("gmail:ada@example.com"))
        assertEquals(ContactOrigin.GMAIL, ContactFacets.classifyOrigin("com.google"))
        assertEquals("the longer key wins", ContactOrigin.MAIL, ContactFacets.classifyOrigin("com.google.android.gm.exchange"))
        assertEquals(ContactOrigin.WHATSAPP, ContactFacets.classifyOrigin("com.whatsapp"))
        assertEquals(ContactOrigin.TELEGRAM, ContactFacets.classifyOrigin("org.telegram.messenger"))
        assertEquals(ContactOrigin.LINKEDIN, ContactFacets.classifyOrigin("linkedin"))
        assertEquals(ContactOrigin.INSTAGRAM, ContactFacets.classifyOrigin("instagram"))
        assertEquals(ContactOrigin.SIM, ContactFacets.classifyOrigin("com.android.contacts.sim"))
        assertEquals(ContactOrigin.OTHER, ContactFacets.classifyOrigin("com.unknown.app"))
        assertEquals(listOf(ContactOrigin.GMAIL, ContactOrigin.PHONE), ContactFacets.origins(sample[0]))
    }

    @Test fun peopleOriginFiltersComeFromThePeoplePresent() {
        val present = ContactFacets.originsPresent(sample)
        assertEquals(listOf(ContactOrigin.WHATSAPP, ContactOrigin.TELEGRAM, ContactOrigin.INSTAGRAM, ContactOrigin.LINKEDIN, ContactOrigin.GMAIL, ContactOrigin.PHONE), present)
        assertFalse("a business-only origin is no People chip", ContactOrigin.MAIL in present)
        assertEquals(listOf(OriginCategory.SOCIAL, OriginCategory.MAIL, OriginCategory.PHONE), ContactFacets.categoriesPresent(sample))
        assertEquals(ContactFacets.people(sample), ContactFacets.peopleBy(sample))
        assertEquals(listOf("p3"), ContactFacets.peopleBy(sample, origin = ContactOrigin.WHATSAPP).map { it.id })
        assertEquals(listOf("p2", "p3", "p4"), ContactFacets.peopleBy(sample, category = OriginCategory.SOCIAL).map { it.id })
        assertEquals(listOf("p1"), ContactFacets.peopleBy(sample, category = OriginCategory.MAIL).map { it.id })
        assertTrue("no business in a People filter", ContactFacets.peopleBy(sample, category = OriginCategory.PHONE).none { it.id.startsWith("b") })
    }

    @Test fun defaultGroupsExistAndAssignmentRoundTrips() {
        assertEquals(listOf("Friends", "Family", "Job", "Favorites"), ContactFacets.DEFAULT_GROUPS)
        assertEquals(ContactFacets.DEFAULT_GROUPS, ContactFacets.groupNames(emptyMap()))
        var a = ContactFacets.assign(emptyMap(), "p1", "Friends", true)
        a = ContactFacets.assign(a, "p1", "Job", true)
        a = ContactFacets.assign(a, "p3", "Book club", true)
        a = ContactFacets.assign(a, "p1", "Friends", false)
        a = ContactFacets.assign(a, "p2", "  ", true)
        assertEquals(mapOf("p1" to setOf("Job"), "p3" to setOf("Book club")), a)
        assertEquals(ContactFacets.DEFAULT_GROUPS + "Book club", ContactFacets.groupNames(a))
        assertEquals(listOf("p1"), ContactFacets.inGroup(sample, a, "Job").map { it.id })
        assertEquals(a, ContactFacets.decode(ContactFacets.encode(a)))
        assertEquals("an id left in no group is dropped", emptyMap<String, Set<String>>(), ContactFacets.assign(mapOf("p1" to setOf("Job")), "p1", "Job", false))
        assertEquals(emptyMap<String, Set<String>>(), ContactFacets.decode(null))
        assertEquals(mapOf("ok" to setOf("Family")), ContactFacets.decode("garbage\n\tnoid\nok\tFamily\n"))
    }
}
