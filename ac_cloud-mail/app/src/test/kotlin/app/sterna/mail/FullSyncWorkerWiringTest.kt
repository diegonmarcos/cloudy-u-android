package app.sterna.mail

import app.sterna.core.data.mail.AttachmentPolicy
import app.sterna.core.data.mail.FullSyncSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT (the worker needs an Android runtime; the engine it drives is behaviour-tested in
 * core:data FullSyncTest): #1768 the background full sync is scheduled, retried with exponential backoff, and triggered by
 * every event the ticket names; and "Download attachments" is in Configs, defaulted, translated and declared for migration.
 */
class FullSyncWorkerWiringTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "build.json").isFile && File(it, "app").isDirectory }

    private fun src(relative: String) = File(root, "app/src/main/kotlin/app/sterna/$relative").readText()

    @Test fun `the worker schedules periodically, runs the incremental full sync per account and backs off exponentially`() {
        val w = src("mail/OfflineSyncWorker.kt")
        assertTrue("periodic, period from the schedule (15 min without push)", "PeriodicWorkRequestBuilder<OfflineSyncWorker>(FullSyncSchedule.periodMinutes(hasPush), TimeUnit.MINUTES)" in w)
        assertTrue("an updated period replaces the old one in place", "ExistingPeriodicWorkPolicy.UPDATE" in w)
        assertEquals("every request (periodic, now, continuation) backs off exponentially", 3, Regex("""\.setBackoffCriteria\(BackoffPolicy\.EXPONENTIAL""").findAll(w).count())
        assertTrue("the policy, window and size limit reach the engine", "container.mailRepository.fullSync(" in w && "credentials, window, limit, policy," in w)
        assertTrue("battery saver and metered state are read from the device at each step", "DevicePrefetchConditions.read(applicationContext)" in w)
        assertTrue("a stop of the job ends the run safely", "stopRequested = { isStopped }" in w)
        assertTrue("a failed pass retries (WorkManager backs off), a cycle that keeps failing is given up", "Result.retry()" in w && "FullSyncSchedule.MAX_ATTEMPTS" in w)
        assertTrue("an unfinished window queues its own continuation", "continueSoon(applicationContext)" in w)
        assertTrue("IMAP keeps the earlier offline fill", "syncOffline(credentials, window, limit)" in w)
        assertEquals(15L, FullSyncSchedule.periodMinutes(false))
    }

    @Test fun `#1869 the start runs are expedited on Android 12+, with the constraint an expedited job accepts`() {
        for (file in listOf("mail/OfflineSyncWorker.kt", "mail/MailCoreSyncWorker.kt")) {
            val w = src(file)
            val runNow = w.substringAfter("fun runNow(context: Context) {").substringBefore("\n        }\n")
            assertTrue("$file: expedited, normal work when out of quota", "builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)" in runNow)
            assertTrue("$file: only from Android 12 (below it expedited work needs getForegroundInfo)", "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {" in runNow)
        }
        val w = src("mail/OfflineSyncWorker.kt")
        assertTrue("the expedited request carries the network constraint only", ".setConstraints(expeditedConstraints())" in w)
        val expedited = w.substringAfter("private fun expeditedConstraints()").substringBefore(".build()")
        assertTrue("network" , "setRequiredNetworkType(NetworkType.CONNECTED)" in expedited)
        assertTrue("WorkManager rejects battery-not-low on expedited work", "setRequiresBatteryNotLow" !in expedited)
        assertTrue("the periodic job keeps battery-not-low", "setRequiresBatteryNotLow(true)" in w.substringAfter("private fun constraints()").substringBefore("private fun expeditedConstraints()"))
        assertTrue("the inline images are logged per pass", "inline=\${it.inlineImages}" in w)
    }

    @Test fun `every trigger of the ticket starts a run`() {
        val push = src("push/PushService.kt")
        assertTrue("a push state change", Regex("""onAccountChanged[\s\S]*?OfflineSyncWorker\.runNow\(this\)""").containsMatchIn(push))
        assertTrue("push held: the period relaxes", "OfflineSyncWorker.ensureScheduled(this, hasPush = true)" in push)
        assertTrue("push gone: back to every 15 minutes", "OfflineSyncWorker.ensureScheduled(this, hasPush = false)" in push)
        assertTrue("right after an app update", Regex("""ACTION_MY_PACKAGE_REPLACED\)\s*\{\s*runCatching \{ OfflineSyncWorker\.runNow""").containsMatchIn(src("push/BootReceiver.kt")))
        assertTrue("connectivity regain", "OfflineSyncWorker.runNow(ctx)" in src("net/EndpointNetworkWatch.kt"))
        assertTrue("app open", "OfflineSyncWorker.runNow(this)" in src("MainActivity.kt"))
        assertTrue("process start arms the periodic run with the push state", "OfflineSyncWorker.ensureScheduled(this, hasPush = app.sterna.push.PushService.isRunning)" in src("SternaApplication.kt"))
        val vm = src("ui/settings/SettingsViewModel.kt")
        assertEquals("the window, the size limit and the policy each start a run when changed", 3, Regex("""OfflineSyncWorker\.runNow\(getApplication\(\)\)""").findAll(vm).count())
    }

    @Test fun `the Configs section lists Download attachments with all three options, defaulting to Wi-Fi only, and the storage used per account`() {
        val screen = src("ui/settings/SettingsScreen.kt")
        assertTrue("options come from the enum, so Always / Wi-Fi only / Off are all there", "options = app.sterna.core.data.mail.AttachmentPolicy.entries.toList()" in screen)
        assertTrue(Regex("""R\.string\.settings_offline_attach_policy_title""").containsMatchIn(screen))
        assertTrue("per-account attachment storage shown", "R.string.settings_offline_attach_storage" in screen)
        assertTrue("the earlier size limit row stays", "R.string.settings_offline_attachments_title" in screen)
        assertEquals(listOf("ALWAYS", "WIFI_ONLY", "OFF"), AttachmentPolicy.entries.map { it.name })
        assertEquals(AttachmentPolicy.WIFI_ONLY, AttachmentPolicy.DEFAULT)
        assertTrue("the view model starts on the default", "initialValue = app.sterna.core.data.mail.AttachmentPolicy.DEFAULT" in src("ui/settings/SettingsViewModel.kt"))
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        assertTrue("Wi-Fi only is labelled as the default", Regex("""settings_offline_attach_policy_wifi">Wi-Fi only \(default\)""").containsMatchIn(strings))
    }

    @Test fun `the setting is declared for the new-phone migration in the fleet config manifest`() {
        val manifest = File(root.parentFile, "fleet-private-data/fleet-config.json")
        if (!manifest.isFile) return // a partial checkout; CI has the whole tree
        val text = manifest.readText()
        assertTrue("datastore:settings documents offline_attachment_policy as migrating config", "offline_attachment_policy: Download attachments Always / Wi-Fi only / Off, default Wi-Fi only" in text)
        assertTrue("the key name in code is the one declared", app.sterna.core.data.settings.OFFLINE_ATTACHMENT_POLICY_KEY == "offline_attachment_policy")
    }
}
