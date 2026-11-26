package com.diegonmarcos.clouddrive.sync

import android.content.ClipboardManager
import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.cloudlib.gitcore.CloneOptions
import com.diegonmarcos.cloudlib.gitcore.GitStep
import com.diegonmarcos.cloudlib.gitcore.SyncOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #1934 the device-touching controls of the sheet, EXECUTED: the clipboard content of Copy URL / Copy command, the URL Open on Web goes to, the Auto-Sync interval the worker ticks at. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlEffectsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun clip(): String = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()

    @Test fun copyUrlPutsTheCloneUrlOnTheClipboard() {
        val page = Declarations.sync.git
        val url = page.cloneUrl("repo-x", Declarations.REMOTE_HTTPS)
        SheetEffects.copy(ctx, "repo-x", url)
        assertEquals(url, clip()); assertTrue(url, url.startsWith("https://github.com/") && url.endsWith("/repo-x.git"))
    }

    @Test fun copyCommandPutsThePreviewOnTheClipboard() {
        val preview = GitStep.preview(SyncOptions(direction = "pull", strategy = "merge", winner = "remote").steps())
        SheetEffects.copy(ctx, "preview", preview)
        assertEquals("git pull --no-rebase -X theirs", clip())
        val clone = GitStep.preview(listOf(CloneOptions(workspace = "bare").step("https://x/r.git", "d/r.git")))
        SheetEffects.copy(ctx, "preview", clone); assertEquals("git clone --bare https://x/r.git d/r.git", clip())
    }

    @Test fun openOnWebGoesToTheRepositoryPage() {
        val page = Declarations.sync.git
        assertEquals("https://github.com/" + page.owner + "/repo-x", SheetEffects.webUrl(page, "repo-x"))
    }

    @Test fun autoSyncIntervalsAreFlooredAtWorkManagersPeriodAndTheWorkerTicksAtTheSmallest() {
        val declared = listOf(5L, 15L, 30L, 60L)
        assertEquals(listOf(15L, 15L, 30L, 60L), declared.map { AutoSyncPlan.minutes(it, 15) })
        assertEquals(true to 15L, AutoSyncPlan.toggled(true, 5, 15))
        assertEquals(30L, AutoSyncPlan.tickMinutes(60, listOf(30L, 60L)))
        assertEquals(15L, AutoSyncPlan.tickMinutes(60, listOf(5L)))
        assertEquals(null, AutoSyncPlan.nextRunMinutes(false, 15, 0, 0, null))
    }
}
