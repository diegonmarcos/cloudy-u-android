package com.diegonmarcos.superapp.configs

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import com.diegonmarcos.superapp.ui.KitPageHarness
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #535 — the persistent-badges view of Configs ▸ Launcher ▸ Notify (#580; the
 * Push tab before it) renders the badge AS POSTED, not a sentence about it, and
 * gives it a button that does what the badge's own tap does.
 *
 * Driven through the real view ([BadgePanes.badges]) against the SHIPPED declaration
 * ([BadgeServices.declared]): a notification is posted on a declared badge
 * channel exactly as its producer would, and the assertions read the rendered
 * view tree. Expected strings are read back off the posted Notification, so
 * nothing here passes on a value this file typed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgePanesLiveBadgeTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before
    fun logToStdout() { org.robolectric.shadows.ShadowLog.stream = System.out }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    /** Draws the pane under the dense theme and returns the window's view tree (the notification's own RemoteViews live in it). */
    private fun render(): View {
        // Scrollable like the page that hosts it: the pane is taller than the test screen.
        showKit {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                BadgePanes.badges {}
            }
        }
        compose.waitForIdle()
        return compose.activity.window.decorView
    }

    @org.junit.Test
    fun `the Config pane shows the same taxonomy as the shade - app sections with Cloudy and Phone`() {
        render()
        val texts = composeTexts()
        for (label in listOf("Me Infos", "Media", "Device Infos", "Apps Push Notifications", "Apps Keep Alive Badges", "Actions"))
            org.junit.Assert.assertTrue("$label missing from the pane", label in texts)
        val order = listOf("Me Infos", "Media", "Device Infos", "Apps Push Notifications", "Apps Keep Alive Badges", "Actions").map { texts.indexOf(it) }
        org.junit.Assert.assertEquals("sections in the redesigned order", order.sorted(), order)
        org.junit.Assert.assertTrue(texts.count { it == "Cloudy" } == 2 && texts.count { it == "Phone" } == 2)
    }

    /** Every text the pane draws in Compose. */
    private fun composeTexts(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    private fun tapPaneButton(label: String) {
        compose.onAllNodesWithTag("pane-button:$label")[0].performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** A badge that needs no grant, so its state is decided by its service and
     *  its notification alone — the two things these tests control. */
    private fun badge(): BadgeDeclaration.Badge =
        BadgeDeclaration.badges(BadgeServices.declared)
            .first { it.channel.isNotBlank() && it.requires.isEmpty() && it.service.isNotBlank() }

    private fun ownerRunning(b: BadgeDeclaration.Badge) {
        shadowOf(ctx.getSystemService(ActivityManager::class.java)).setServices(listOf(
            ActivityManager.RunningServiceInfo().apply { service = ComponentName(ctx.packageName, b.service) },
        ))
    }

    private fun stateRow(texts: List<String>, b: BadgeDeclaration.Badge, state: Int) =
        texts.filter { it.startsWith(ctx.getString(state) + " ·") }

    private fun post(b: BadgeDeclaration.Badge): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(b.channel, b.label, NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, FragmentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, b.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${b.label} as posted")
            .setContentText("value ${System.nanoTime()}")
            .setContentIntent(tap)
            .build()
        nm.notify(b.id.hashCode(), n)
        return n
    }

    @Test
    fun `a posted badge renders its own title and text and an Open button that fires its tap`() {
        val b = badge()
        val n = post(b)
        val view = render()
        val texts = all(view).filterIsInstance<TextView>().map { it.text.toString() } + composeTexts()

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue("posted title '$title' not rendered; pane shows $texts", texts.contains(title))
        // The platform template drew it, not a TextView this fragment made: only
        // the template's title carries android.R.id.title.
        assertTrue(
            "title '$title' is not in the notification template's own title view — the pane drew it itself",
            all(view).any { it.id == android.R.id.title && (it as? TextView)?.text?.toString() == title },
        )
        assertTrue("posted text '$text' not rendered; pane shows $texts", texts.any { it.contains(text) })
        if (b.shows.isNotBlank())
            assertFalse("the pane still prints the declared description", texts.any { it.contains(b.shows) })

        assertTrue("no Open button for the posted badge",
            compose.onAllNodesWithTag("pane-button:${ctx.getString(R.string.push_open)}").fetchSemanticsNodes().isNotEmpty())
        tapPaneButton(ctx.getString(R.string.push_open))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val app = shadowOf(ctx as Application)
        // The harness's own activity launch may sit in the queue ahead of the tap: look at everything started.
        val started = generateSequence { app.nextStartedActivity }.toList()
        assertTrue(
            "Open did not fire the notification's own contentIntent; started $started",
            started.any { it.component?.className == FragmentActivity::class.java.name },
        )
    }

    /** Owner running, nothing posted — the swiped-away badge. The previous
     *  pane called this LIVE because it only asked whether the service ran. */
    @Test
    fun `a running owner with nothing posted reads not-in-the-shade`() {
        val b = badge()
        ownerRunning(b)
        render()
        val texts = composeTexts()
        assertTrue(
            "${b.id}: service running, nothing posted, yet the pane said LIVE: $texts",
            stateRow(texts, b, R.string.push_state_live).isEmpty(),
        )
        assertTrue(
            "${b.id}: expected NOT IN THE SHADE: $texts",
            stateRow(texts, b, R.string.push_state_dead).isNotEmpty(),
        )
    }

    /** The control for the test above: running AND posted is LIVE, so that
     *  one cannot pass on a pane that never says LIVE at all. */
    @Test
    fun `a running owner with its badge posted reads LIVE`() {
        val b = badge()
        ownerRunning(b)
        post(b)
        render()
        val texts = composeTexts()
        assertTrue("${b.id}: running and posted, not LIVE: $texts",
            stateRow(texts, b, R.string.push_state_live).isNotEmpty())
    }

    /** Launch all — the way back from a badge that was cleared: every declared
     *  badge that may launch has its owning service started. */
    @Test
    fun `Launch all starts the owner of every launchable badge`() {
        val b = badge()
        render()
        assertTrue("no Launch all button",
            compose.onAllNodesWithTag("pane-button:${ctx.getString(R.string.push_launch_all)}").fetchSemanticsNodes().isNotEmpty())
        tapPaneButton(ctx.getString(R.string.push_launch_all))
        val app = shadowOf(ctx as Application)
        val started = generateSequence { app.nextStartedService }.mapNotNull { it.component?.className }.toSet()
        assertTrue("${b.id}: its owner ${b.service} was not started; started $started", b.service in started)
    }

    /** "Keep it pinned" is read at post time: on by default, and switching it
     *  off is what a producer sees. It was decoration for six of seven badges. */
    @Test
    fun `pinned follows the Keep it pinned switch`() {
        val b = badge()
        assertTrue("${b.id} is declared persistent, so it pins by default", BadgeServices.pinned(ctx, b.id))
        BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, false)
        assertFalse("${b.id}: switched off, still pinned", BadgeServices.pinned(ctx, b.id))
    }
}
