package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.floatingnav.FloatingNavConfig
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.KdeStatusNotifier
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Quick Actions card (5 icon buttons) and the KDE card (5 buttons + the sub-text info line) drawn by the real
 * BadgeCard from the real notification the badge posts, with that notification rendered by the platform template
 * below it. Evidence: app/build/configs-screens/notify-cards-quickactions.png and notify-cards-kde.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyCardsShotTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun frame(id: String, n: Notification, shot: String, want: List<String>) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply { cancelAll(); notify(7, n) }
        BadgeCardData.override = null
        val b = orderedBadges(ctx).first { it.id == id }
        val d = BadgeCardData.read(ctx, b)
        assertEquals(want, d.actions.map { it.id })
        showKit {
            Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) {
                Text("Notify card", color = Color(0xFF9AA0A6), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                BadgeCard(d)
                Text("Notification (platform template)", color = Color(0xFF9AA0A6), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                AndroidView(modifier = Modifier.fillMaxWidth(), factory = { c ->
                    FrameLayout(c).also { fl -> runCatching { BadgeServices.view(c, n, fl) }.getOrNull()?.let { fl.addView(it) } }
                })
            }
        }
        compose.onNodeWithTag(BadgeCardTags.actions(id), useUnmergedTree = true).assertExists()
        for (a in d.actions) compose.onNodeWithTag(BadgeCardTags.action(id, a.id), useUnmergedTree = true).assertExists().assertHasClickAction()
        snap(shot)
    }

    @Test
    fun `render the Quick Actions and KDE cards next to their notifications`() {
        val cfg = FloatingNavConfig.get()
        val qa = FloatingNavService.quickActionsNotification(ctx, cfg)
        frame("floating_nav_quick_actions", qa, "notify-cards-quickactions.png",
            qa.actions.map { com.diegonmarcos.superapp.notificationcenter.BadgeModel.idOf(it.title.toString()) })
        KdeConnectManager.init(ctx)
        frame("kde_status", KdeStatusNotifier.build(ctx), "notify-cards-kde.png", listOf("share", "desktop", "ping", "connect", "find"))
    }

    @Test
    fun `the Notify page draws the six section subtitles`() {
        BadgeCardData.override = null
        val sections = badgeSections(ctx).filter { it.title.isNotBlank() }
        // #1672: Media leads, then Me Infos, Device Infos, Actions.
        assertEquals(listOf("Media", "Me Infos", "Device Infos", "Actions"), sections.map { it.title })
        showKit { Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCards() } }
        for (s in sections) compose.onNodeWithTag(BadgeCardTags.section(s.id), useUnmergedTree = true).assertExists().assertTextEquals(s.title)
        // #1672: the app lists are no longer page sections (the notification blocks show the real notifications); they
        // are drawn inside Configs, pinned by NotifyPage1672Test.
        for (id in listOf("cloud_publisher", "phone_publisher", "apps_keepalive"))
            compose.onNodeWithTag(BadgeCardTags.section(id), useUnmergedTree = true).assertDoesNotExist()
        // the AppSectionBlock itself still draws both sides
        showKit { Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) {
            for (sec in com.diegonmarcos.superapp.notificationcenter.NotifySections.appSections) AppSectionBlock(sec)
        } }
        compose.onNodeWithTag(BadgeCardTags.sub("cloud_publisher", "cloudy"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(BadgeCardTags.sub("phone_publisher", "phone"), useUnmergedTree = true).assertExists()
        for (sub in listOf("cloudy", "phone")) compose.onNodeWithTag(BadgeCardTags.sub("apps_keepalive", sub), useUnmergedTree = true).assertExists()
        showKit { Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCards() } }
        compose.onNodeWithTag(BadgeCardTags.TITLE, useUnmergedTree = true).assertExists()
        snap("notify-page-sections.png")
    }

    @Test
    fun `the viewer page is read-only - no toggles anywhere`() {
        BadgeCardData.override = null
        showKit { Column(Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCards() } }
        compose.waitForIdle()
        // #1603a: every switch exposes a ToggleableState; the viewer must draw none (control lives in Settings).
        val toggles = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNodes()
        org.junit.Assert.assertTrue("the viewer drew ${toggles.size} toggle(s); it must be read-only", toggles.isEmpty())
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
