package app.sterna.ui.errors

import com.diegonmarcos.superapp.fleetendpoints.errors.ErrorLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1925 every error banner and snackbar exposes See log, Export to Markdown and Copy all; each check has a mutant. */
class ErrorBanners1925Test {
    private fun src(rel: String): String {
        val f = listOf("src/main/kotlin/app/sterna/$rel", "app/src/main/kotlin/app/sterna/$rel", "ac_cloud-mail/app/src/main/kotlin/app/sterna/$rel")
            .map(::File).first { it.exists() }
        return f.readText()
    }

    private fun fn(code: String, name: String): String {
        val i = code.indexOf("fun $name(")
        require(i >= 0) { "$name missing" }
        return code.substring(i, minOf(code.length, i + 1800)).substringBefore("\n/**").substringBefore("\n@Composable")
    }

    private fun bannersExposeActions(inbox: String): Boolean =
        "ErrorActionsWithLog()" in fn(inbox, "SyncErrorBanner") && "ErrorActionsWithLog()" in fn(inbox, "OutboxFailureBanner")

    @Test fun actionLabelsArePinned() {
        assertEquals(listOf("See log", "Export to Markdown", "Copy all"), ErrorActions.ALL)
    }

    @Test fun bannersExposeTheActions() {
        val inbox = src("ui/inbox/InboxScreen.kt")
        assertTrue(bannersExposeActions(inbox))
        assertTrue("the sync-failed centre scene", "notice == RefreshNotice.ERROR" in inbox && "ErrorActionsWithLog()" in inbox.substringAfter("notice == RefreshNotice.ERROR"))
    }

    @Test fun mutantBannerWithoutActionsIsCaught() {
        val inbox = src("ui/inbox/InboxScreen.kt")
        val head = inbox.substringBefore("fun SyncErrorBanner(")
        val tail = inbox.substring(head.length)
        assertFalse(bannersExposeActions(head + tail.replaceFirst("ErrorActionsWithLog()", "")))
        val outboxAt = inbox.indexOf("fun OutboxFailureBanner(")
        assertFalse(bannersExposeActions(inbox.substring(0, outboxAt) + inbox.substring(outboxAt).replaceFirst("ErrorActionsWithLog()", "")))
    }

    @Test fun errorSnackbarsOfInboxAndSenderOfferSeeLog() {
        for (f in listOf("ui/inbox/InboxScreen.kt", "ui/sender/MailBySenderScreen.kt")) {
            val s = src(f)
            assertTrue(f, "showError(m)" in s && "ErrorLogDialog" in s)
        }
        assertTrue("actionLabel = ErrorActions.SEE_LOG" in src("ui/errors/ErrorLogUi.kt"))
    }

    @Test fun syncFailureIsRecordedWithOperationAndAccount() {
        val vm = src("ui/inbox/InboxViewModel.kt")
        assertEquals(2, Regex("ErrorLog\\.shared\\.record\\(\"sync\", currentAccountLabel\\(\\)").findAll(vm).count())
        assertTrue("ErrorLog.shared.record(\"send\"" in src("send/OutboxWorker.kt"))
    }

    @Test fun seeLogExportAndCopyReadTheSameContent() {
        val l = ErrorLog()
        l.log("t", "hello")
        assertEquals(ErrorActions.content(l), ErrorActions.content(l))
        assertTrue(ErrorActions.markdown(l).contains("hello"))
        assertTrue(ErrorActions.markdown(l).startsWith("# "))
    }

    @Test fun clientCapturesEveryExchange() {
        assertTrue("ErrorLog.shared.interceptor()" in File(listOf("core/jmap", "../core/jmap", "ac_cloud-mail/core/jmap").map { "$it/src/main/kotlin/app/sterna/core/jmap/JmapClient.kt" }.first { File(it).exists() }).readText())
    }
}
