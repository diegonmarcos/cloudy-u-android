package com.diegonmarcos.clouddrive.workspace

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.sync.GitStatusReader
import com.diegonmarcos.clouddrive.sync.GitStorage
import com.diegonmarcos.clouddrive.sync.StatusCache
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.superapp.repoworkspace.IRepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import java.io.File

/**
 * #1855 P1 SERVES libs:repo-workspace: the fleet asks Drive which repositories it holds and where (every volume, nothing moved),
 * and asks it to clone one. The access check is the manifest's: the service is guarded by the CONSTELLATION_DATA signature
 * permission, so the platform refuses any app not signed with the Cloud key; there is deliberately none in code. All the rules
 * are [RepoWorkspaceCatalog]'s (JVM-tested); this class only gathers the roots and the cached statuses.
 */
class RepoWorkspaceService : Service() {

    private fun cached(): Map<String, GitStatusReader.Status> =
        runCatching { StatusCache(File(filesDir, StatusCache.FILE)).load().values.associateBy { it.path } }.getOrDefault(emptyMap())

    private fun rows() = runCatching {
        RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(roots(), runCatching { SharedStore.legacyRoot() }.getOrNull()), cached())
    }.getOrDefault(emptyList())

    private fun roots() = GitStorage.Roots(
        runCatching { SharedStore.root() }.getOrNull(),
        runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull(),
        runCatching { com.diegonmarcos.superapp.usbdrive.StorageVolumes.drives(this).map { File(it.path) } }.getOrDefault(emptyList()),
    )

    private val binder = object : IRepoWorkspace.Stub() {
        override fun version(): Int = RepoWorkspace.VERSION
        override fun listRepos(): Array<String> = rows().map { it.encode() }.toTypedArray()
        override fun findRepo(name: String?): Array<String> = RepoWorkspaceCatalog.find(rows(), name.orEmpty()).map { it.encode() }.toTypedArray()
        override fun requestClone(name: String?): String = RepoWorkspaceCatalog.refusal(name)
        override fun status(path: String?): String = RepoWorkspaceCatalog.statusOf(rows(), path.orEmpty())?.encode().orEmpty()

        // ---- v2 (#1855 P4a): read-only review + validated hand-offs. Nothing here writes to a repository. ----
        override fun changedFiles(path: String?): Array<String> = runCatching {
            val p = path.orEmpty().trimEnd('/')
            if (RepoChanges.reviewRefusal(rows(), p).isNotEmpty()) emptyArray()
            else GitEngine(this@RepoWorkspaceService, File(p)).use { RepoChanges.read(it, File(p)) }.map { it.encode() }.toTypedArray()
        }.getOrDefault(emptyArray())

        override fun diff(path: String?, relpath: String?, maxBytes: Int): String = runCatching {
            val p = path.orEmpty().trimEnd('/')
            if (RepoChanges.reviewRefusal(rows(), p).isNotEmpty() || !RepoWorkspace.validRelPath(relpath)) ""
            else GitEngine(this@RepoWorkspaceService, File(p)).use { e ->
                if (RepoChanges.diffRefusal(RepoChanges.read(e, File(p)), relpath).isNotEmpty()) "" else RepoChanges.diffOf(e, File(p), relpath!!, maxBytes)
            }
        }.getOrDefault("")

        override fun requestCommit(path: String?): String = handOff(path)
        override fun requestPush(path: String?): String = handOff(path)
    }

    private fun registered(): Set<String> =
        runCatching { RepoRegistry(File(filesDir, com.diegonmarcos.clouddrive.GitSyncWorker.REGISTRY_FILE)).load().map { it.path.trimEnd('/') }.toSet() }.getOrDefault(emptySet())

    private fun handOff(path: String?): String = runCatching { RepoChanges.handOffRefusal(rows(), registered(), path) }.getOrElse { "Cloud Drive could not check the repository" }

    override fun onBind(intent: Intent?): IBinder = binder
}
