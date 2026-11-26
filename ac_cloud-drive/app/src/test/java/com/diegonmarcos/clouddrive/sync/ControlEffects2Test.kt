package com.diegonmarcos.clouddrive.sync

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #1934 Folder fires openInFiles with the clone's path; the Auto-Sync scheduler registration itself: one unique periodic work, at the interval the plan names, replaced (not duplicated) when it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ControlEffects2Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test fun folderFiresOpenInFilesWithTheClonesPath() {
        val seen = mutableListOf<String>()
        val actions = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DriveActions::class.java)) { _, m, a -> if (m.name == "openInFiles") seen += a[0] as String; null } as DriveActions
        SheetEffects.openFolder(actions, "/store/git/repo-x")
        assertEquals(listOf("/store/git/repo-x"), seen)
    }

    private fun works(): List<WorkInfo> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(GitSyncWorker.WORK_NAME).get(10, TimeUnit.SECONDS)

    @Test fun autoSyncRegistersOneUniquePeriodicWorkAtTheDeclaredIntervalAndUpdatesIt() {
        try { WorkManager.initialize(ctx, Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).setTaskExecutor(Executors.newSingleThreadExecutor()).build()) } catch (_: IllegalStateException) { /* already initialised by androidx.startup */ }
        val reg = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE).also { it.parentFile!!.mkdirs() })
        reg.upsert(ManagedRepo(id = "r1", name = "r1", path = "/x/r1", autoSync = true, syncIntervalMinutes = 30))
        GitSyncWorker.schedule(ctx)
        var w = works()
        assertEquals("one unique work", 1, w.size); assertEquals(WorkInfo.State.ENQUEUED, w[0].state)
        assertEquals("the worker ticks at the smallest opted-in period", TimeUnit.MINUTES.toMillis(AutoSyncPlan.tickMinutes(com.diegonmarcos.clouddrive.BuildConfig.GIT_SYNC_INTERVAL_MINUTES, listOf(30L))), w[0].periodicityInfo!!.repeatIntervalMillis)
        reg.upsert(ManagedRepo(id = "r2", name = "r2", path = "/x/r2", autoSync = true, syncIntervalMinutes = 15))
        GitSyncWorker.schedule(ctx)
        w = works()
        assertEquals("replaced, not duplicated", 1, w.size)
        assertEquals(TimeUnit.MINUTES.toMillis(AutoSyncPlan.tickMinutes(com.diegonmarcos.clouddrive.BuildConfig.GIT_SYNC_INTERVAL_MINUTES, listOf(30L, 15L))), w[0].periodicityInfo!!.repeatIntervalMillis)
        assertTrue(w[0].periodicityInfo!!.repeatIntervalMillis >= TimeUnit.MINUTES.toMillis(15))
    }
}
