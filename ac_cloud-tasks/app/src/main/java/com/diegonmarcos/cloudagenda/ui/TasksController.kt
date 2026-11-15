package com.diegonmarcos.cloudagenda.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.Proposal
import com.diegonmarcos.cloudagenda.data.SampleProject
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskFilter
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.data.TasksPrefs
import com.diegonmarcos.cloudagenda.data.TasksRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the pages read and call. State lives here as Compose state; every write goes to [TasksRepository] on [io] and
 * then re-reads the whole (small) snapshot, so the screen is always what the database holds. The pages themselves
 * are stateless composables over these values, which is what lets the layout-tree tests drive them directly.
 */
class TasksController(
    private val repo: TasksRepository,
    private val prefs: TasksPrefs,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Sample projects (data/projects/projects.json) seeded once, flagged, deletable. */
    private val samples: List<SampleProject> = emptyList(),
) {
    var snapshot by mutableStateOf(Snapshot())
        private set
    var filter by mutableStateOf(prefs.filter)
        private set
    var selectedListId by mutableStateOf(prefs.selectedListId)
        private set
    var agent by mutableStateOf(AgentConfig())
        private set
    var proposals by mutableStateOf<List<Proposal>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    /** First run seeds (idempotent), then everything is read. Safe to call again. */
    fun start() {
        scope.launch { withContext(io) { repo.seedIfEmpty(); repo.seedSamples(samples, java.time.LocalDate.now()) }; reload() }
    }

    private suspend fun reload() {
        val (snap, cfg, props) = withContext(io) { Triple(repo.snapshot(), repo.agentConfig(), repo.proposals()) }
        snapshot = snap
        agent = cfg
        proposals = props
        filter = filter.prune(snap.tags.map { it.id }.toSet()).also { if (it != filter) prefs.filter = it }
        if (snap.list(selectedListId) == null) {
            val first = snap.projects.firstOrNull()?.let { snap.listsOf(it.id).firstOrNull() } ?: snap.lists.firstOrNull()
            selectedListId = first?.id ?: ""
            prefs.selectedListId = selectedListId
        }
        loaded = true
    }

    private fun write(block: (TasksRepository) -> Unit) {
        scope.launch { withContext(io) { block(repo) }; reload() }
    }

    // ── what the page shows ─────────────────────────────────────────────────────────────────────────────

    fun updateFilter(f: TaskFilter) { filter = f; prefs.filter = f }

    fun selectList(id: String) { selectedListId = id; prefs.selectedListId = id }

    // ── projects, lists, tasks ──────────────────────────────────────────────────────────────────────────

    fun addProject(name: String) = write { it.addProject(name) }
    fun renameProject(id: String, name: String) = write { it.renameProject(id, name) }
    fun deleteProject(id: String) = write { it.deleteProject(id) }
    fun addList(projectId: String, name: String) = write { it.addList(projectId, name) }
    fun renameList(id: String, name: String) = write { it.renameList(id, name) }
    fun deleteList(id: String) = write { it.deleteList(id) }

    fun addTask(listId: String, name: String, parentId: String? = null) = write { it.addTask(listId, name, parentId) }
    fun saveTask(t: Task) = write { it.saveTask(t) }
    fun deleteTask(id: String) = write { it.deleteTask(id) }
    fun setStatus(id: String, s: TaskStatus) = write { it.setStatus(id, s) }

    // ── tags ────────────────────────────────────────────────────────────────────────────────────────────

    fun addTag(name: String, color: Long) = write { it.addTag(name, color) }
    fun renameTag(id: String, name: String) = write { it.renameTag(id, name) }
    fun recolorTag(id: String, color: Long) = write { it.recolorTag(id, color) }
    fun deleteTag(id: String) = write { it.deleteTag(id) }

    // ── the mail agent ──────────────────────────────────────────────────────────────────────────────────

    fun saveAgent(cfg: AgentConfig) = write { it.saveAgentConfig(cfg) }
    fun decide(proposalId: String, approve: Boolean, listId: String?) = write { it.decideProposal(proposalId, approve, listId) }
    fun refreshAgent() { scope.launch { reload() } }
}
