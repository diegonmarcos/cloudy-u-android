package com.diegonmarcos.superapp.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1756 — All Apps folder tiles draw the label without its section prefix character; the data keeps it
 * (it is the section key PhoneTaxonomyLayout.bucket groups by).
 */
class PhoneFolderDisplayLabelTest {
    private val prefixes = listOf("=", "@", ".", "~", "+", "%", "*", "#", "_")

    @Test fun stripsEverySectionPrefix() {
        assertEquals("Browser", PhoneTaxonomyLayout.displayLabel("=Browser", prefixes))
        assertEquals("Mail", PhoneTaxonomyLayout.displayLabel("@Mail", prefixes))
        assertEquals("Drive", PhoneTaxonomyLayout.displayLabel(".Drive", prefixes))
        assertEquals("Dev", PhoneTaxonomyLayout.displayLabel("_Dev", prefixes))
        assertEquals("Search", PhoneTaxonomyLayout.displayLabel("+Search", prefixes))
        assertEquals("Social", PhoneTaxonomyLayout.displayLabel("%Social", prefixes))  // #1902 Services \u00b7 Leisure
    }

    @Test fun stripsOnlyTheLeadingCharacter() {
        assertEquals("Wi.Fi=Tools", PhoneTaxonomyLayout.displayLabel("_Wi.Fi=Tools", prefixes))
    }

    @Test fun leavesAnUnclaimedLabelAlone() {
        assertEquals("A", PhoneTaxonomyLayout.displayLabel("A", prefixes))
        assertEquals("=Browser", PhoneTaxonomyLayout.displayLabel("=Browser", emptyList()))
    }

    @Test fun theDataLabelStillBucketsBySectionKey() {
        val sections = listOf("Primary" to "=", "Setup" to "_")
        val g = PhoneTaxonomyLayout.bucket(sections, listOf("=Browser", "_Dev")) { it }
        assertEquals(listOf("Primary", "Setup"), g.map { it.title })
        assertEquals(listOf("=Browser"), g[0].folders)
    }
}
