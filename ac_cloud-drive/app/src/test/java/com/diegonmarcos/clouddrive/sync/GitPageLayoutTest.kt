package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Git page's layout contract, read off the source: one table, a sticky header below the sign-in cards, the bulk bar first, a closable menu. */
class GitPageLayoutTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val src = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt").readText()

    @Test fun `the Git page keeps only the table and the sign-in cards live in Configs`() {
        assertTrue("the cards are drawn only in the SETUP part", src.contains("if (setup) page.ways.forEach"))
        assertTrue("the host cards are drawn only in the SETUP part", src.contains("if (setup) Declarations.gitHosts.forEach"))
        assertTrue("the table is drawn only outside the SETUP part", src.contains("if (!setup) tableBlock()"))
        assertTrue("the hero and the pull actions are SETUP-only", Regex("if \\(setup\\) item \\{\\s+Row\\(Modifier\\.fillMaxWidth\\(\\)\\.testTag\\(DriveTags\\.SYNC_HERO\\)").containsMatchIn(src) && src.contains("if (setup) item {\n            Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_GIT_PULL_ALL)"))
        val cfg = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/configs/ConfigsScreen.kt").readText()
        assertTrue(cfg.contains("\"git\" -> GitReposScreen(") && cfg.contains("part = GitPart.SETUP"))
        val sync = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/SyncScreen.kt").readText()
        assertFalse("the Git page itself never asks for the SETUP part", sync.contains("GitPart.SETUP"))
    }

    @Test fun `the page scrolls in the shared frame, one bounded list, never a nested scroller`() {
        assertTrue(src.contains("GitPageFrame("))
        assertFalse("the screen must not open its own LazyColumn", src.contains("LazyColumn("))
        val frame = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitPageFrame.kt").readText()
        assertTrue(frame.contains("windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))"))
        assertEquals("exactly one list in the frame", 1, Regex("LazyColumn\\(").findAll(frame).count())
        assertFalse("no hardcoded dp in the frame", Regex("\\d\\.dp").containsMatchIn(frame))
    }

    @Test fun `the column header is sticky and is the single header`() {
        // #1315 ONE sticky block: the declared toolbar, the job line, the search + Filters, the quick rail, the summary and the column header.
        assertTrue("the top block must be a stickyHeader", src.contains("stickyHeader(key = \"table-top\")"))
        assertEquals("exactly one header emission", 1, Regex("RepoTableHeader\\(sortColumn").findAll(src).count())
    }

    @Test fun `there is one table, no public and private groups`() {
        assertFalse(src.contains("personalGroups"))
        assertFalse(src.contains("GIT_GROUP_PRIVATE"))
        assertEquals("one rows block", 1, Regex("items\\(sortedEntries").findAll(src).count())
    }

    @Test fun `the declared top bar comes before the rows and dispatches every declared kind`() {
        val block = src.substring(src.indexOf("fun LazyListScope.tableBlock()"))
        assertTrue(block.indexOf("GitTopBar(page.topBar") in 0 until block.indexOf("items(sortedEntries"))
        assertTrue(block.indexOf("Text(summaryLine") in 0 until block.indexOf("items(sortedEntries"))
        // #1315 no Kotlin list of bulk commands: every kind of ui.sync.git.top_bar has a branch, nothing else does.
        for (k in Declarations.TOP_KINDS) assertTrue(k, src.contains("\"$k\" ->"))
        assertFalse(src.contains("RepoActions.Bulk"))
    }

    @Test fun `a tap toggles the menu through the pure rule and the menu has a close control`() {
        assertTrue(src.contains("expanded = RepoTable.toggle(expanded, e.key)"))
        assertTrue(src.contains("onClose = { expanded = \"\" }"))
        // #1315 the detail is a bottom sheet: the row items carry no GitRepoOpsBox / GitRepoNotClonedBox, so a tap never changes the list's height.
        val items = src.substring(src.indexOf("items(sortedEntries, key = { it.key })"), src.indexOf("Hairline()", src.indexOf("items(sortedEntries, key = { it.key })")))
        assertFalse(items.contains("GitRepoOpsBox(") || items.contains("GitRepoNotClonedBox("))
        assertTrue(src.contains("DriveTags.SYNC_GIT_DETAIL_SHEET"))
    }

    private val dir = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync")
    private val fsync = File(dir, "FsyncPage.kt").readText()
    private val mounts = File(dir, "MountsPage.kt").readText()
    private val tableUi = File(dir, "RepoTableUi.kt").readText() + File(root, "../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui/RepoTableUi.kt").readText()

    @Test fun `#1195 the actions bar sits above the column header and the header is the sticky item right below it`() {
        val block = src.substring(src.indexOf("fun LazyListScope.tableBlock()"))
        val sticky = block.indexOf("stickyHeader(key = \"table-top\")")
        val bar = block.indexOf("GitTopBar(page.topBar")
        val header = block.indexOf("RepoTableHeader(sortColumn")
        val rows = block.indexOf("items(sortedEntries")
        // #1315 the declared toolbar, then the column header, both INSIDE the one sticky block, then the rows.
        assertTrue("sticky block, toolbar, header, rows", sticky in 0 until bar && bar < header && header < rows)
        assertFalse("no second plain item above the block", block.substring(0, sticky).contains("item(key = \"table-bulk\")"))
        val topBar = File(dir, "GitTopBar.kt").readText()
        assertTrue("the bar carries the Settings gear", topBar.contains("AutoSettingsSheet(AutoChecks.Page.GIT, prefs)"))
    }

    @Test fun `#1195 git fsync and mounts share one actions bar with the settings sheet and one tap-to-open row`() {
        // #1315 the Git bar is the declared GitTopBar (sync/GitTopBar.kt); Fsync and Mounts keep the shared PageActionsBar of TablePage.kt.
        assertTrue(File(dir, "TablePage.kt").readText().contains("fun PageActionsBar(") && src.contains("GitTopBar(page.topBar"))
        assertFalse("the Git table has no second, enum-driven bar", tableUi.contains("PageActionsBar("))
        assertEquals("one Fsync bar (#1216: folders only)", 1, Regex("PageActionsBar\\(").findAll(fsync).count())
        assertEquals("two Mounts bars (rclone, fuse)", 2, Regex("PageActionsBar\\(").findAll(mounts).count())
        assertTrue(File(dir, "TablePage.kt").readText().contains("AutoSettingsSheet(page, prefs)"))
        assertEquals(1, Regex("fun AutoSettingsSheet\\(").findAll(File(dir, "TablePage.kt").readText()).count())
        // #1216 Fsync's tap-to-open row is the Unison pair card (onClick = onToggle, wired to TapPanel.toggle in FsyncPage); the Backup TapCard left with the Backup page.
        assertTrue(fsync.contains("onToggle = { open = TapPanel.toggle(open, it) }") && mounts.contains("TapCard(") && File(dir, "UnisonScreens.kt").readText().contains("onClick = onToggle"))
        for (f in listOf(fsync, mounts)) assertTrue("tap again closes the panel", f.contains("TapPanel.toggle(open"))
        assertEquals("one rule for the toggle", "if (open == tapped) \"\" else tapped", File(dir, "TablePage.kt").readText().lines().first { it.contains("fun toggle") }.substringAfter("=").trim())
    }

    @Test fun `#1195 a repository that is not cloned opens its panel on a tap`() {
        assertFalse("the row is clickable whether or not it is cloned", src.contains(".clickable(enabled = repo != null"))
        // #1315 the not-cloned panel lives in the detail sheet, behind the one `e.cloned == null` branch.
        val sheet = src.substring(src.indexOf("DriveTags.SYNC_GIT_DETAIL_SHEET"))
        assertTrue(sheet.contains("if (e.cloned == null) {") && sheet.contains("GitRepoNotClonedBox("))
        for (needle in listOf("R.string.repo_act_open_web", "R.string.sync_clone_into_store", "R.string.repo_act_copy_url", "RepoActions.PRS", "RepoActions.needsClone(id)", "R.string.repo_not_cloned_why")) assertTrue(needle, src.contains(needle))
    }

    @Test fun `#1195 the pages open without disk, process or network work in their composables`() {
        // StrictMode-style, read off the source: the composables only read the caches; every walk, probe and request is in a coordinator behind withContext(IO).
        val banned = listOf("walkTopDown", "walkFileTree", "listFiles(", "HttpURLConnection", ".readText(", "ProcessBuilder", "Runtime.getRuntime")
        for ((name, text) in mapOf("GitReposScreen" to src, "FsyncPage" to fsync, "MountsPage" to mounts, "RepoTableUi" to tableUi, "TablePage" to File(dir, "TablePage.kt").readText())) {
            banned.forEach { assertFalse("$name must not call $it on the main thread", text.contains(it)) }
        }
        assertFalse("the seed report is read off the main thread", src.contains("remember { StoreSeedWorker.lastReport"))
        val coord = File(dir, "GitSyncCoordinator.kt").readText()
        assertTrue("sizes are walked on IO", coord.contains("withContext(Dispatchers.IO) {\n                    val dirs = registry.load()"))
        assertTrue("opening the Git page loads the registry and runs only the plan", coord.contains("runPlan(AutoChecks.plan(autoSettings(), AutoChecks.Trigger.OPEN))"))
        val pageOpen = coord.substring(coord.indexOf("fun onPageOpened()"), coord.indexOf("fun onTick()"))
        assertFalse("no unconditional FULL refresh on open", Regex("(^|\\s)refresh\\(").containsMatchIn(pageOpen))
        assertFalse("opening the page starts NO status read: the table renders from the persisted cache, checks run only on an explicit owner action", pageOpen.contains("statusReader.request(") || pageOpen.contains("lightCheckAll(") || pageOpen.contains("fullCheckAll("))
        for (f in listOf(fsync, mounts)) assertTrue("the deep probes sit behind the settings", f.contains("AutoRefreshEffects("))
        assertTrue(File(dir, "RepoDiskSize.kt").readText().contains("check(!StartupGuard.onMainThread())"))
    }

    @Test fun `#1195 the size column is in the table header and cells`() {
        assertTrue(tableUi.contains("RepoColumn.SIZE_DISK -> R.string.repo_col_size_disk"))
        assertTrue(tableUi.contains("RepoColumn.SIZE_DISK to GitTable.size(r.sizeDiskBytes)"))
        assertTrue(src.contains("diskSizes[File(it.path).absolutePath]?.bytes"))
    }

    private val driveWindow = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/RepoStatusWindow.kt").readText()
    // #1408 the window's layout is libs:git-ui's; Drive's file resolves the facts into it
    private val libWindow = File(root, "../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui/GitStatusWindowUi.kt").readText()
    private val window = driveWindow + libWindow
    private val strings = File(root, "app/src/main/res/values/strings.xml").readText()
    private val rowFn = src.substring(src.indexOf("private fun GitRepoRow("), src.indexOf("private fun lightOf("))

    @Test fun `#1238 the row has the status icon cell first, no GitHub or Not Cloned text and no Clone button`() {
        assertTrue("the status cell comes before the name column", rowFn.indexOf("RepoStatusCell(icons, name, onOpenStatus)") in 0 until rowFn.indexOf("RepoNameCell(name)"))
        assertFalse("no Not cloned text under the row", rowFn.contains("git_not_cloned"))
        assertFalse("the listing label (GitHub) is not drawn under the row", rowFn.contains("source: String") || rowFn.contains("Text(source"))
        assertFalse("the Clone button left the row", rowFn.contains("sync_clone_into_store") || rowFn.contains("onClone"))
        assertFalse("the dot is no longer inside the name cell", rowFn.contains("StatusDot("))
        assertFalse("the Not cloned string is gone", strings.contains("name=\"git_not_cloned\""))
        assertTrue(src.contains("onOpenStatus = { statusFor = e.key }"))
        assertTrue(tableUi.contains("fun RepoStatusCell(") && tableUi.contains("testTag(k.tagStatusCell)"))
    }

    @Test fun `#1452 the row is ONE line - no text under the name or under the lights, the message is a column`() {
        assertTrue("the row is the shared one-line row", rowFn.contains("RepoTableLine(scroll, Modifier.testTag(DriveTags.SYNC_GIT_ROW)"))
        assertFalse("no caption inside the status lights", rowFn.contains("levelCaption"))
        assertFalse("no second line under the name", rowFn.contains("Column(") || rowFn.contains("maxLines = 2") || rowFn.contains("sync_glance_gone") || rowFn.contains("statusErrorText"))
        assertTrue("the Status Message is a table column after the name", tableUi.contains("RepoMessageCell(r.message, r.messageProblem)") && tableUi.contains("RepoColumn.MESSAGE -> R.string.repo_col_message"))
        assertTrue("the note under a row went into the message", !src.contains("e.note.isNotBlank()"))
        val line = tableUi.substring(tableUi.indexOf("fun RepoTableLine("), tableUi.indexOf("fun RepoNameCell("))
        assertTrue("the whole line scrolls on the shared state (nothing pinned)", line.contains("horizontalScroll(scroll)") && line.contains("heightIn(min = k.rowHeight)"))
        val folder = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/FolderTableUi.kt").readText()
        assertTrue("the folder tables draw the same line, the outcome in the message cell", folder.contains("RepoTableLine(scroll,") && folder.contains("RepoMessage.ofFolder(") && !folder.contains("run.lines()"))
    }

    @Test fun `#1238 the LFS column is declared, labelled, printed from the registry and never hardcoded`() {
        assertTrue(tableUi.contains("RepoColumn.LFS -> R.string.repo_col_lfs"))
        assertTrue(tableUi.contains("RepoColumn.LFS to repoLfsWord(r.lfs)") && tableUi.contains("GitTable.lfs(lfs, "))
        assertTrue("the row reads the registry flag", src.contains("Declarations.repoSot.lfsOf(name)"))
        val vis = tableUi.indexOf("RepoColumn.VISIBILITY to"); val lfs = tableUi.indexOf("RepoColumn.LFS to")
        assertTrue("LFS is the cell right after Pvt/Pub", vis in 0 until lfs && tableUi.substring(vis, lfs).lines().size == 2)
        for (f in listOf(src, tableUi, window)) assertFalse("no repo name is hardcoded as LFS", f.contains("cloud_db_LFS") || f.contains("cloudy_db_LFS"))
    }

    @Test fun `#1238 the status window is full screen, shows what the app knows, and holds Clone repo as its last section`() {
        assertTrue(libWindow.contains("usePlatformDefaultWidth = false") && libWindow.contains("fillMaxSize()") && libWindow.contains("k.tagStatusWindow"))
        for (needle in listOf("statusFacts(", "R.string.repo_meta_k_path", "R.string.repo_meta_k_remote", "R.string.repo_meta_k_last_sync", "R.string.repo_meta_k_lfs", "R.string.repo_meta_k_visibility", "R.string.repo_meta_k_upstream", "RepoStatus.block(")) assertTrue(needle, window.contains(needle))
        assertTrue("the window draws the status block", libWindow.contains("GitStatusBlockView(") && driveWindow.contains("R.string.sync_clone_into_store"))
        val actions = libWindow.indexOf("R.string.repo_status_window_actions")
        assertTrue("Clone is after the metadata table", libWindow.indexOf("RepoMetaTable(") in 0 until libWindow.indexOf("GitCloneAction.CLONE ->"))
        assertTrue("Clone is in the actions section, the last one", actions in 0 until libWindow.indexOf("GitCloneAction.CLONE ->"))
        assertTrue(driveWindow.contains("RepoStatus.cloneAction(repo != null, cloning)") && libWindow.contains("k.tagStatusClone"))
        assertTrue("the screen shows the window for the tapped row and passes the row's own clone", src.contains("RepoStatusWindow(") && src.contains("onClone = e.onClone,") && src.contains("onCheckWorktree = { e.cloned?.let { coordinator.checkWorktree(it) } }, onClose = { statusFor = \"\" }"))
        assertFalse("no hardcoded dp in the window", Regex("\\d\\.dp").containsMatchIn(window))
    }
}
