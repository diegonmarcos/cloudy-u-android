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

    private fun init() { try { WorkManager.initialize(ctx, Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).setTaskExecutor(Executors.newSingleThreadExecutor()).build()) } catch (_: IllegalStateException) { /* already initialised by androidx.startup */ } }
    private fun live(): List<WorkInfo> = works().filter { !it.state.isFinished }
    private fun registry() = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE).also { it.parentFile!!.mkdirs() })

    @Test fun autoSyncRegistersOneUniquePeriodicWorkAtTheDeclaredIntervalAndUpdatesIt() {
        init(); val reg = registry()
        reg.upsert(ManagedRepo(id = "r1", name = "r1", path = "/x/r1", autoSync = true, syncIntervalMinutes = 30))
        GitSyncWorker.schedule(ctx)
        var w = live()
        assertEquals("one unique work", 1, w.size); assertEquals(WorkInfo.State.ENQUEUED, w[0].state)
        assertEquals("the worker ticks at the smallest opted-in period", TimeUnit.MINUTES.toMillis(AutoSyncPlan.tickMinutes(com.diegonmarcos.clouddrive.BuildConfig.GIT_SYNC_INTERVAL_MINUTES, listOf(30L))), w[0].periodicityInfo!!.repeatIntervalMillis)
        reg.upsert(ManagedRepo(id = "r2", name = "r2", path = "/x/r2", autoSync = true, syncIntervalMinutes = 15))
        GitSyncWorker.schedule(ctx)
        w = live()
        assertEquals("replaced, not duplicated", 1, w.size)
        assertEquals(TimeUnit.MINUTES.toMillis(AutoSyncPlan.tickMinutes(com.diegonmarcos.clouddrive.BuildConfig.GIT_SYNC_INTERVAL_MINUTES, listOf(30L, 15L))), w[0].periodicityInfo!!.repeatIntervalMillis)
        assertTrue(w[0].periodicityInfo!!.repeatIntervalMillis >= TimeUnit.MINUTES.toMillis(15))
    }

    @Test fun turningAutoSyncOffCancelsTheWorkAndTurningItOnEnqueuesItAgain() {
        init(); val reg = registry()
        reg.upsert(ManagedRepo(id = "r1", name = "r1", path = "/x/r1", autoSync = true, syncIntervalMinutes = 15))
        GitSyncWorker.schedule(ctx)
        assertEquals(1, live().size)
        // the last repository leaves Auto-Sync: nothing to tick for, the unique work is cancelled
        reg.upsert(ManagedRepo(id = "r1", name = "r1", path = "/x/r1", autoSync = false, syncIntervalMinutes = 15))
        GitSyncWorker.schedule(ctx)
        assertTrue("no live work while no repository is opted in: ${works()}", live().isEmpty())
        assertTrue(works().all { it.state == WorkInfo.State.CANCELLED })
        // a repository turns it on again: enqueued at the declared interval
        reg.upsert(ManagedRepo(id = "r1", name = "r1", path = "/x/r1", autoSync = true, syncIntervalMinutes = 60))
        GitSyncWorker.schedule(ctx)
        val w = live()
        assertEquals(1, w.size); assertEquals(WorkInfo.State.ENQUEUED, w[0].state)
        assertEquals(TimeUnit.MINUTES.toMillis(AutoSyncPlan.tickMinutes(com.diegonmarcos.clouddrive.BuildConfig.GIT_SYNC_INTERVAL_MINUTES, listOf(60L))), w[0].periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun withNoRepositoryAtAllNothingIsEnqueued() {
        init(); registry()
        GitSyncWorker.schedule(ctx)
        assertTrue(live().isEmpty())
    }
}
