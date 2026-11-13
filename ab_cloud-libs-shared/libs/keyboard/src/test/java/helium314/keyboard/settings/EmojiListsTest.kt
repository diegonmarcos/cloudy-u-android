// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiListsTest {
    private val assets = File("src/main/assets")

    @Test fun everyCategoryFileOfTheKeyboardIsListed() {
        val files = File(assets, "emoji").listFiles().orEmpty().map { it.name.removeSuffix(".txt") }.filter { it != "minApi" }.toSet()
        assertEquals(files, EmojiLists.categoryFiles.toSet())
    }

    @Test fun loadsEveryCategoryFromTheRealAssets() {
        val cats = EmojiLists.load { p -> File(assets, p).readLines() }
        assertEquals(EmojiLists.categoryFiles, cats.map { it.file })
        cats.forEach { assertTrue(it.file, it.emojis.size > 10 && it.emojis.none { e -> e.isBlank() }) }
        assertEquals(emptyList<String>(), EmojiLists.missing(cats))
    }

    @Test fun aMissingFileIsLeftOutNeverACrash() {
        val cats = EmojiLists.load { p -> if (p.contains("FLAGS")) throw java.io.FileNotFoundException(p) else listOf("a", "", "b\tvariant") }
        assertEquals(listOf("FLAGS"), EmojiLists.missing(cats))
        assertEquals(listOf("a", "b"), cats.first().emojis)
        assertEquals("Food and drink", EmojiLists.label("FOOD_AND_DRINK"))
    }
}
