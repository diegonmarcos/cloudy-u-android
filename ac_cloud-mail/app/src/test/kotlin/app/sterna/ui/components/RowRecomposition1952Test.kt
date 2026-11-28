package app.sterna.ui.components

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.ui.JankProbe
import app.sterna.ui.theme.SternaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1952 jank, the part no emulator is needed for: the real [EmailListItem] in a lazy list whose item scope reads a state that a sync
 * writes (the real screen reads its UI state, the selection and the sync status there). Every row on screen is composed ONCE; a sync write
 * (the state ticks) composes none of them again, because the row's parameters are equal and it is skippable; scrolling composes each new row
 * once. The Email handed over is an equal copy each time (a re-query); app/compose-stability.conf declares the mail model stable, so equal rows
 * are skipped. Without that line (the mutation row-recomposes-on-sync) every row recomposes on every write.
 * Mutation: workflow mail-cache-1952-mutation, leg row-recomposes-on-sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowRecomposition1952Test {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @After fun stopCounting() { JankProbe.countRows = false; JankProbe.rowCounts.clear() }

    private val emails = (1..40).map {
        Email(
            id = "e$it", subject = "Subject $it", preview = "Preview $it", receivedAt = "2026-09-24T10:00:00Z",
            from = listOf(EmailAddress(name = "Sender $it", email = "s$it@example.org")),
        )
    }
    private val onClick: () -> Unit = {}
    private var syncTick by mutableIntStateOf(0)

    @Test fun `a sync write composes no row on screen again and scrolling composes each new row once`() {
        JankProbe.rowCounts.clear(); JankProbe.countRows = true
        compose.setContent {
            SternaTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalListMonogram provides false) {
                    Column(Modifier.fillMaxSize()) {
                        Text("synced $syncTick")
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items(emails, key = { it.id }) { e ->
                                val tick = syncTick // the item scope reads the sync state, as the real screen's does
                                if (tick < 0) error("unreachable")
                                // an EQUAL but new Email on every pass: what each re-query of the list hands the rows on screen
                                EmailListItem(email = e.copy(), onClick = onClick)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val first = JankProbe.rowCounts.toMap()
        assertTrue("rows are on screen: $first", first.size >= 3)
        assertTrue("every row on screen composed once: $first", first.values.all { it == 1 })
        repeat(5) { compose.runOnUiThread { syncTick++ }; compose.waitForIdle() }
        assertEquals("five sync writes composed no row again", first, JankProbe.rowCounts.toMap())
        compose.onNodeWithTag("list").performScrollToIndex(39)
        compose.waitForIdle()
        val scrolled = JankProbe.rowCounts.toMap()
        assertTrue("scrolling brought new rows in: ${scrolled.size}", scrolled.size > first.size)
        assertTrue("every row composed once, however far the list scrolled: $scrolled", scrolled.values.all { it == 1 })
    }
}
