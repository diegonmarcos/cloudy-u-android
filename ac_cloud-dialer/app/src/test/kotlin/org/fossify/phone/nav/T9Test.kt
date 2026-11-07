package org.fossify.phone.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class T9Test {
    private val spec = KeypadSpec.parse(File("../../data/fleet/app/com.diegonmarcos.comms.dialer/keypad.json").readText())
    private fun names(typed: String, all: List<T9Contact>) = T9.search(typed, all, spec).map { it.contact.name }

    @Test fun lettersUnderTheDigits() {
        val letters = spec.keys.associate { it.digit to it.letters }
        assertEquals("ABC", letters["2"]); assertEquals("DEF", letters["3"]); assertEquals("GHI", letters["4"])
        assertEquals("JKL", letters["5"]); assertEquals("MNO", letters["6"]); assertEquals("PQRS", letters["7"])
        assertEquals("TUV", letters["8"]); assertEquals("WXYZ", letters["9"]); assertEquals("+", letters["0"])
        assertEquals("", letters["1"])
        assertEquals(4, spec.rows.size); assertTrue(spec.rows.all { it.size == 3 })
        assertEquals('5', spec.letterToDigit['j']); assertEquals('9', spec.letterToDigit['z']); assertEquals(26, spec.letterToDigit.size)
    }

    @Test fun nameSpellsDigitsPerWordAndFoldsAccents() {
        assertEquals(listOf("5646", "76484"), T9.words("John Smith", spec.letterToDigit))
        assertEquals(listOf("2662"), T9.words("\u00c1nna", spec.letterToDigit))
        assertEquals(listOf("5646", "76484"), T9.words("  john-smith ", spec.letterToDigit))
    }

    private val all = listOf(
        T9Contact("John Smith", "+34 600 111 222"),
        T9Contact("Jon Doe", "+34 600 333 444", lastMillis = 50, count = 1),
        T9Contact("Mary Jones", "+34 611 555 666", lastMillis = 90, count = 7),
        T9Contact("Ana", "+34 622 000 000"),
        T9Contact("Bob", "+34 5646 77"),
    )

    @Test fun wordStartAndFullNameMatch() {
        assertEquals(listOf("John Smith", "Bob"), names("5646", all))
        assertTrue("word start: Smith", "John Smith" in names("76484", all))
        assertEquals(T9Tier.EXACT, T9.search("564676484", all, spec).first { it.contact.name == "John Smith" }.tier)
        assertTrue("name substring", "John Smith" in names("6467", all))
        assertTrue(names("999", all).isEmpty())
    }

    @Test fun numberSubstringMatches() {
        assertEquals(listOf("Jon Doe"), names("333", all))
        assertEquals(listOf("Mary Jones"), names("5556", all))
        assertTrue("two digits never match inside a number", names("55", all).isEmpty())
    }

    @Test fun rankedExactPrefixWordStartSubstringThenRecentThenFrequent() {
        val cs = listOf(
            T9Contact("Ann", "111"),
            T9Contact("Anna", "222"),
            T9Contact("Zed Ann", "333", lastMillis = 5),
            T9Contact("Joann", "444"),
            T9Contact("Annie", "555", lastMillis = 900, count = 1),
            T9Contact("Anne", "666", lastMillis = 900, count = 9),
        )
        val hits = T9.search("266", cs, spec)
        assertEquals(listOf(T9Tier.EXACT, T9Tier.PREFIX, T9Tier.PREFIX, T9Tier.PREFIX, T9Tier.WORD_START, T9Tier.SUBSTRING), hits.map { it.tier })
        assertEquals(listOf("Ann", "Anne", "Annie", "Anna", "Zed Ann", "Joann"), hits.map { it.contact.name })
    }

    @Test fun severalNamesAndNumberExactAndLimit() {
        val many = (1..40).map { T9Contact("Ann $it", "70000$it") }
        assertEquals(spec.search.resultsLimit, T9.search("266", many, spec).size)
        val two = listOf(T9Contact("Sam", "100"), T9Contact("Pam", "200"), T9Contact("Rao", "300"))
        assertEquals(setOf("Sam", "Pam", "Rao"), names("7", two).toSet())
        assertEquals(T9Tier.EXACT, T9.search("100", two, spec).first().tier)
        assertEquals("Sam", T9.search("100", two, spec).first().contact.name)
    }

    @Test fun duplicatesCollapseAndNothingTypedIsNothing() {
        val d = listOf(T9Contact("Ann", "+1 555"), T9Contact("ann", "1555"))
        assertEquals(1, T9.search("266", d, spec).size)
        assertTrue(T9.search("", d, spec).isEmpty())
        assertTrue(T9.search("*#", d, spec).isEmpty())
    }
}
