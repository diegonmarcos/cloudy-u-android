package helium314.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1698 the keyboard's Writer tab IS cloud-writer: its first top sub-tabs are cloud-writer's bottom bar (libs:writer-ui
 * writer-config.json ui.bottom_nav; the standalone app was retired in #1707), same ids, same order, same labels, each drawn by libs:writer-ui's WriterSurface through the
 * host/<id> route; Voice Transcription and Dictionary follow.
 */
class WriterTabParityTest {
    private fun writerBuildJson(): String? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "ab_cloud-libs-shared/libs/writer-ui/writer-config.json") }.firstOrNull { it.isFile }?.readText()

    @Test fun writerTabStartsWithCloudWritersBottomBarInOrderMinusHome() {
        val bar = listOf("enhancer" to "Enhancer", "drafter" to "Drafter", "home" to "Home", "translator" to "Translator", "grammar" to "Grammar")
        writerBuildJson()?.let { json ->
            val declared = Regex("\"bottom_nav\"\\s*:\\s*\\[([^]]*)]").find(json)!!.groupValues[1]
                .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            assertEquals(bar.map { it.first }, declared) // cloud-writer itself keeps its Home
        }
        // the keyboard's Writer is that bar without Home (explicit removal; its content moved under Voice Transcription)
        val want = bar.filter { it.first != "home" }
        val firstFour = KbTab.WRITER.pages.take(4)
        assertEquals(want.map { SettingsDestination.hostRoute(it.first) }, firstFour.map { it.route })
        assertEquals(want.map { it.second }, firstFour.map { it.label })
        val labels = KbTab.WRITER.pages.map { it.label }
        assert(labels.indexOf("Voice Transcription") > 3)
        assertEquals("Configs Writer is the very last page of Writer", "Configs Writer", labels.last())
    }

    /** #1698 Writer > Tools opens cloud-writer's tool activities by class name: the keyboard declares and ships every one (it crashed without). */
    @Test fun keyboardHostsEveryCloudWriterToolActivity() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "ac_cloud-keyboard/app").isDirectory } ?: return
        val manifest = File(root, "ac_cloud-keyboard/app/src/main/AndroidManifest.xml").readText()
        val shells = File(root, "ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudwriter/WriterToolShells.kt").readText()
        for (a in listOf("TextEnhanceActivity", "TranslationActivity", "GrammarCheckActivity", "AiRoutingActivity", "RoutesActivity")) {
            assert(manifest.contains("com.diegonmarcos.cloudwriter.$a")) { "manifest lacks $a" }
            assert(shells.contains("class $a ")) { "no shell $a" }
        }
    }
}
