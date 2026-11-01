package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI spec 4.3 / 4.4 + #1399: a tab opens on its Homepage, a quote is pushed, back unwinds quote -> Homepage -> home tab -> exit. */
class FinNavStackTest {
    @Test fun aTabOpensOnItsHomepage() {
        assertEquals(listOf("list"), FinNavStack.initial())
        assertEquals(Pages.PAGE_HOME, FinNavStack.selectedPage(FinNavStack.initial()))
    }

    @Test fun theSelectedSubPageIsTheShownOne() {
        assertEquals("yield-curves", FinNavStack.selectedPage(FinNavStack.open(FinNavStack.initial(), "yield_curves")))
        assertEquals("holdings", FinNavStack.selectedPage(FinNavStack.openPage("holdings")))
    }

    @Test fun backUnwindsInTheDeclaredOrder() {
        val s0 = FinNavStack.initial()
        val s1 = FinNavStack.open(s0, "crypto")
        val s2 = FinNavStack.quote(s1, "BTC")
        assertEquals(listOf("list", "dash:crypto", "quote:BTC"), s2)
        assertEquals(s2, FinNavStack.quote(s2, "BTC"))
        assertEquals("BTC", FinNavStack.symbolOf(s2.last()))
        assertEquals("crypto", FinNavStack.dashboardOf(s2))
        assertEquals(s1, FinNavStack.back(s2, isHomeTab = false))
        assertEquals(s0, FinNavStack.back(s1, isHomeTab = false))
        assertEquals(s0, FinNavStack.back(FinNavStack.openPage("holdings"), isHomeTab = false))
        assertEquals(FinNavStack.HOME, FinNavStack.back(s0, isHomeTab = false))
        assertNull(FinNavStack.back(FinNavStack.initial(), isHomeTab = true))
    }
}
