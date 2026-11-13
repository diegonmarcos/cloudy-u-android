package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.profile.revealIconOffered
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1693 MOVED from Cloud Mail (RevealIconOfferedTest): the secret field is Cloud Account's now (App credentials), and the rule is the same.
 * [revealIconOffered] is the whole of "is there an eye on this field": a secret with something typed in it, nothing else.
 */
class RevealIconOfferedTest {
    @Test fun `a secret field with something typed in it offers the eye`() =
        assertTrue("there is text under the dots and no other way to check it before saving", revealIconOffered(true, "hunter2"))

    @Test fun `a secret field holding only a space still offers the eye`() =
        assertTrue("the rule is isNotEmpty, not isNotBlank: this is about what is DISPLAYED, and the eye is the only way to see a stray space", revealIconOffered(true, " "))

    @Test fun `an empty secret field offers no eye at all`() =
        assertFalse("an eye on an empty field is a control that cannot do anything when pressed (#118)", revealIconOffered(true, ""))

    @Test fun `a plain field never offers the eye, whatever it holds`() {
        assertFalse(revealIconOffered(false, "https://mail.example.org"))
        assertFalse(revealIconOffered(false, ""))
    }
}
