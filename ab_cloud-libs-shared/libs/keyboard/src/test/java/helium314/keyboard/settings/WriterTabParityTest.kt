package helium314.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1698 the keyboard's Writer tab IS cloud-writer: its first top sub-tabs are cloud-writer's bottom bar (ac_cloud-writer
 * build.json ui.bottom_nav), same ids, same order, same labels, each drawn by libs:writer-ui's WriterSurface through the
 * host/<id> route; Voice Transcription and Dictionary follow.
 */
class WriterTabParityTest {
    private fun writerBuildJson(): String? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "ac_cloud-writer/build.json") }.firstOrNull { it.isFile }?.readText()

    @Test fun writerTabStartsWithCloudWritersBottomBarInOrder() {
        val want = listOf("enhancer" to "Enhancer", "drafter" to "Drafter", "home" to "Home", "translator" to "Translator", "grammar" to "Grammar")
        writerBuildJson()?.let { json ->
            val bar = Regex("\"bottom_nav\"\\s*:\\s*\\[([^]]*)]").find(json)!!.groupValues[1]
                .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            assertEquals(want.map { it.first }, bar)
        }
        val firstFive = KbTab.WRITER.pages.take(5)
        assertEquals(want.map { SettingsDestination.hostRoute(it.first) }, firstFive.map { it.route })
        assertEquals(want.map { it.second }, firstFive.map { it.label })
        val labels = KbTab.WRITER.pages.map { it.label }
        assert(labels.indexOf("Voice Transcription") > 4 && labels.indexOf("Dictionary") > 4)
    }
}
