package com.diegonmarcos.cloudagenda.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.diegonmarcos.cloudagenda.agent.AgentStore
import java.time.LocalDate
import java.util.UUID

/**
 * Every read and write of the tasks database. Synchronous (the controller calls it off the main thread), and every
 * multi-row write is one transaction. Deleting a project, list or task cascades through the foreign keys.
 */
class TasksRepository(private val dbh: TasksDb, private val clock: () -> Long = System::currentTimeMillis) : AgentStore {

    private val db: SQLiteDatabase get() = dbh.writableDatabase

    // ── reading ─────────────────────────────────────────────────────────────────────────────────────────

    fun snapshot(): Snapshot {
        val samples = db.rawQuery("SELECT key FROM meta WHERE key LIKE ?", arrayOf("$SAMPLE_FLAG%")).use { c ->
            c.rows { c.getString(0).removePrefix(SAMPLE_FLAG) }.toSet()
        }
        val projects = db.rawQuery("SELECT id, name, position FROM projects ORDER BY position, created_at, name", null).use { c ->
            c.rows { Project(c.getString(0), c.getString(1), c.getInt(2), c.getString(0) in samples) }
        }
        val lists = db.rawQuery("SELECT id, project_id, name, position FROM lists ORDER BY position, created_at, name", null).use { c ->
            c.rows { TaskList(c.getString(0), c.getString(1), c.getString(2), c.getInt(3)) }
        }
        val tags = db.rawQuery("SELECT id, name, color FROM tags ORDER BY name COLLATE NOCASE", null).use { c ->
            c.rows { Tag(c.getString(0), c.getString(1), c.getLong(2)) }
        }
        val tagLinks = HashMap<String, MutableSet<String>>()
        db.rawQuery("SELECT task_id, tag_id FROM task_tags", null).use { c ->
            while (c.moveToNext()) tagLinks.getOrPut(c.getString(0)) { LinkedHashSet() }.add(c.getString(1))
        }
        val attachments = HashMap<String, MutableList<Attachment>>()
        db.rawQuery("SELECT id, task_id, kind, uri, label FROM attachments ORDER BY position", null).use { c ->
            while (c.moveToNext()) {
                attachments.getOrPut(c.getString(1)) { ArrayList() }
                    .add(Attachment(c.getString(0), AttachmentKind.of(c.getString(2)), c.getString(3), c.getString(4)))
            }
        }
        val tasks = db.rawQuery(
            "SELECT id, list_id, parent_id, name, details, date_target, date_deadline, location, status, position " +
                "FROM tasks ORDER BY position, created_at", null,
        ).use { c ->
            c.rows {
                val id = c.getString(0)
                Task(
                    id = id, listId = c.getString(1), parentId = c.getStringOrNull(2), name = c.getString(3),
                    details = c.getString(4), dateTarget = c.getStringOrNull(5), dateDeadline = c.getStringOrNull(6),
                    location = c.getString(7), status = TaskStatus.of(c.getString(8)), position = c.getInt(9),
                    tagIds = tagLinks[id]?.toSet() ?: emptySet(), attachments = attachments[id]?.toList() ?: emptyList(),
                )
            }
        }
        return Snapshot(projects, lists, tasks, tags)
    }

    // ── projects and lists ──────────────────────────────────────────────────────────────────────────────

    fun addProject(name: String): Project {
        val p = Project(newId(), name.trim(), nextPosition("projects", null, null))
        db.insertOrThrow("projects", null, ContentValues().apply {
            put("id", p.id); put("name", p.name); put("position", p.position); put("created_at", clock())
        })
        return p
    }

    fun renameProject(id: String, name: String) {
        db.update("projects", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id))
    }

    fun deleteProject(id: String) {
        db.delete("projects", "id = ?", arrayOf(id))
        db.delete("meta", "key = ?", arrayOf(SAMPLE_FLAG + id))
    }

    fun addList(projectId: String, name: String): TaskList {
        val l = TaskList(newId(), projectId, name.trim(), nextPosition("lists", "project_id", projectId))
        db.insertOrThrow("lists", null, ContentValues().apply {
            put("id", l.id); put("project_id", projectId); put("name", l.name); put("position", l.position); put("created_at", clock())
        })
        return l
    }

    fun renameList(id: String, name: String) {
        db.update("lists", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id))
    }

    fun deleteList(id: String) {
        db.delete("lists", "id = ?", arrayOf(id))
    }

    // ── tasks and subtasks ──────────────────────────────────────────────────────────────────────────────

    /** A new task (or, with [parentId], a subtask) with just a name; the editor fills in the rest. */
    fun addTask(listId: String, name: String, parentId: String? = null): Task {
        val t = Task(
            id = newId(), listId = listId, parentId = parentId, name = name.trim(),
            position = nextPosition("tasks", if (parentId == null) "list_id" else "parent_id", parentId ?: listId),
        )
        db.insertOrThrow("tasks", null, taskValues(t, created = true))
        return t
    }

    /** Writes every field of [t], its attachments and its tag links; inserts the row when it is new. */
    fun saveTask(t: Task) {
        db.transaction {
            val updated = update("tasks", taskValues(t, created = false), "id = ?", arrayOf(t.id))
            if (updated == 0) insertOrThrow("tasks", null, taskValues(t, created = true))
            delete("attachments", "task_id = ?", arrayOf(t.id))
            t.attachments.forEachIndexed { i, a ->
                insertOrThrow("attachments", null, ContentValues().apply {
                    put("id", a.id); put("task_id", t.id); put("kind", a.kind.id); put("uri", a.uri); put("label", a.label); put("position", i)
                })
            }
            delete("task_tags", "task_id = ?", arrayOf(t.id))
            t.tagIds.forEach { tag ->
                insertWithOnConflict("task_tags", null, ContentValues().apply { put("task_id", t.id); put("tag_id", tag) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun setStatus(id: String, status: TaskStatus) {
        db.update("tasks", ContentValues().apply { put("status", status.id); put("updated_at", clock()) }, "id = ?", arrayOf(id))
    }

    fun deleteTask(id: String) {
        db.delete("tasks", "id = ?", arrayOf(id))
    }

    // ── tags (many-to-many with tasks) ──────────────────────────────────────────────────────────────────

    /** Creates the tag, or returns the existing one of that name (names are unique, case-insensitively). */
    fun addTag(name: String, color: Long): Tag {
        val n = name.trim()
        db.rawQuery("SELECT id, name, color FROM tags WHERE name = ? COLLATE NOCASE", arrayOf(n)).use { c ->
            if (c.moveToFirst()) return Tag(c.getString(0), c.getString(1), c.getLong(2))
        }
        val t = Tag(newId(), n, color)
        db.insertOrThrow("tags", null, ContentValues().apply { put("id", t.id); put("name", t.name); put("color", t.color) })
        return t
    }

    /** False when another tag already has that name. */
    fun renameTag(id: String, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        db.rawQuery("SELECT id FROM tags WHERE name = ? COLLATE NOCASE AND id <> ?", arrayOf(n, id)).use { if (it.moveToFirst()) return false }
        db.update("tags", ContentValues().apply { put("name", n) }, "id = ?", arrayOf(id))
        return true
    }

    fun recolorTag(id: String, color: Long) {
        db.update("tags", ContentValues().apply { put("color", color) }, "id = ?", arrayOf(id))
    }

    /** Removes the tag from every task it was on. */
    fun deleteTag(id: String) {
        db.delete("tags", "id = ?", arrayOf(id))
    }

    // ── first-run seed ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds "Project Zero" (lists Step 0 and Step 1, 3 tasks per list, 2 subtasks per task) the first time the app
     * runs. Idempotent: it runs only when there is no project AND the marker is absent, so a user who later deletes
     * everything is not given the sample data back. Returns true when it seeded.
     */
    fun seedIfEmpty(): Boolean = db.transaction {
        val projects = rawQuery("SELECT COUNT(*) FROM projects", null).use { it.moveToFirst(); it.getInt(0) }
        val marked = rawQuery("SELECT 1 FROM meta WHERE key = ?", arrayOf(SEED_KEY)).use { it.moveToFirst() }
        if (projects > 0 || marked) {
            if (!marked) insertWithOnConflict("meta", null, ContentValues().apply { put("key", SEED_KEY); put("value", "existing") }, SQLiteDatabase.CONFLICT_IGNORE)
            return@transaction false
        }
        val project = addProject("Project Zero")
        for (step in 0..1) {
            val list = addList(project.id, "Step $step")
            for (n in 1..3) {
                val task = addTask(list.id, "Step $step - Task $n")
                for (s in 1..2) addTask(list.id, "Step $step - Task $n - Subtask $s", parentId = task.id)
            }
        }
        insertWithOnConflict("meta", null, ContentValues().apply { put("key", SEED_KEY); put("value", "seeded") }, SQLiteDatabase.CONFLICT_IGNORE)
        true
    }

    /**
     * Seeds each sample project (assets/projects.json) once, flagged sample so the user can delete it. Idempotent: a meta marker
     * per sample id is written with the project, so a second call changes nothing and a deleted sample is not given back. Unlike
     * [seedIfEmpty] it also runs when the user already has projects. Dates are offsets from [today]. Returns the names it seeded.
     */
    fun seedSamples(samples: List<SampleProject>, today: LocalDate): List<String> = db.transaction {
        val seeded = ArrayList<String>()
        for (s in samples) {
            val marker = SAMPLE_SEED + s.id
            if (rawQuery("SELECT 1 FROM meta WHERE key = ?", arrayOf(marker)).use { it.moveToFirst() }) continue
            val project = addProject(s.name)
            val first = SampleProjects.firstDay(s, today)
            for (l in s.lists) {
                val list = addList(project.id, l.name)
                l.tasks.forEachIndexed { i, t ->
                    val start = first.plusDays(t.start.toLong())
                    saveTask(Task(
                        id = newId(), listId = list.id, name = t.name, details = t.details, dateTarget = start.toString(),
                        dateDeadline = start.plusDays((t.days.coerceAtLeast(1) - 1).toLong()).toString(), location = t.location, status = t.status, position = i,
                    ))
                }
            }
            for (key in listOf(marker, SAMPLE_FLAG + project.id)) {
                insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", "1") }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            seeded += s.name
        }
        seeded
    }

    // ── the mail agent: declaration and proposals ───────────────────────────────────────────────────────

    override fun agentConfig(): AgentConfig = db.rawQuery(
        "SELECT enabled, source, account, jmap_url, jmap_auth, mailboxes, frequency_minutes, agent, model, extract_events, " +
            "extract_bookings, extract_invoices, extract_deadlines, target_list_id, last_run_at, last_status, last_detail " +
            "FROM agent_config WHERE id = 1", null,
    ).use { c ->
        if (!c.moveToFirst()) AgentConfig() else AgentConfig(
            enabled = c.getInt(0) != 0, source = MailSourceKind.of(c.getString(1)), account = c.getString(2), jmapUrl = c.getString(3),
            jmapAuth = JmapAuth.of(c.getString(4)), mailboxes = c.getString(5), frequencyMinutes = c.getInt(6), agent = c.getString(7),
            model = c.getString(8), extractEvents = c.getInt(9) != 0, extractBookings = c.getInt(10) != 0,
            extractInvoices = c.getInt(11) != 0, extractDeadlines = c.getInt(12) != 0, targetListId = c.getString(13),
            lastRunAt = c.getLong(14), lastStatus = c.getString(15), lastDetail = c.getString(16),
        )
    }

    /** Saves the declaration. The last-run columns belong to the worker and are left alone. */
    fun saveAgentConfig(a: AgentConfig) {
        val v = ContentValues().apply {
            put("id", 1); put("enabled", if (a.enabled) 1 else 0); put("source", a.source.id); put("account", a.account.trim())
            put("jmap_url", a.jmapUrl.trim()); put("jmap_auth", a.jmapAuth.id); put("mailboxes", a.mailboxes.trim())
            put("frequency_minutes", a.frequencyMinutes); put("agent", a.agent); put("model", a.model.trim())
            put("extract_events", if (a.extractEvents) 1 else 0); put("extract_bookings", if (a.extractBookings) 1 else 0)
            put("extract_invoices", if (a.extractInvoices) 1 else 0); put("extract_deadlines", if (a.extractDeadlines) 1 else 0)
            put("target_list_id", a.targetListId)
        }
        db.transaction {
            val n = update("agent_config", v, "id = 1", null)
            if (n == 0) insertOrThrow("agent_config", null, v)
        }
    }

    override fun recordAgentRun(at: Long, status: String, detail: String) {
        db.transaction {
            val v = ContentValues().apply { put("last_run_at", at); put("last_status", status); put("last_detail", detail) }
            val n = update("agent_config", v, "id = 1", null)
            if (n == 0) insertOrThrow("agent_config", null, v.apply { put("id", 1) })
        }
    }

    /** Stores new proposals; one already known for the same mail and title (in any state) is skipped. Returns how many were new. */
    override fun addProposals(drafts: List<ProposalDraft>): Int = db.transaction {
        var added = 0
        for (d in drafts) {
            val id = insertWithOnConflict("proposals", null, ContentValues().apply {
                put("id", newId()); put("kind", d.kind.id); put("title", d.title); put("details", d.details)
                put("date_target", d.dateTarget); put("date_deadline", d.dateDeadline); put("location", d.location)
                put("source_ref", d.sourceRef); put("source_subject", d.sourceSubject); put("confidence", d.confidence)
                put("status", "pending"); put("created_at", clock())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (id != -1L) added++
        }
        added
    }

    fun proposals(status: ProposalStatus = ProposalStatus.PENDING): List<Proposal> = db.rawQuery(
        "SELECT id, kind, title, details, date_target, date_deadline, location, source_ref, source_subject, confidence, status, created_at, task_id " +
            "FROM proposals WHERE status = ? ORDER BY created_at DESC, title", arrayOf(status.id),
    ).use { c ->
        c.rows {
            Proposal(
                id = c.getString(0), kind = ProposalKind.of(c.getString(1)) ?: ProposalKind.EVENT, title = c.getString(2), details = c.getString(3),
                dateTarget = c.getStringOrNull(4), dateDeadline = c.getStringOrNull(5), location = c.getString(6),
                sourceRef = c.getString(7), sourceSubject = c.getString(8), confidence = c.getDouble(9),
                status = ProposalStatus.of(c.getString(10)), createdAt = c.getLong(11), taskId = c.getString(12),
            )
        }
    }

    /**
     * The user's decision. Approving creates the task in [listId] (and links the mail it came from); rejecting only
     * records the decision. Nothing is ever created without this call. Returns the new task, or null for a rejection.
     */
    fun decideProposal(id: String, approve: Boolean, listId: String?): Task? = db.transaction {
        val p = proposals(ProposalStatus.PENDING).firstOrNull { it.id == id } ?: return@transaction null
        var created: Task? = null
        if (approve) {
            val list = listId ?: return@transaction null
            val base = addTask(list, p.title)
            val attachments = if (p.sourceRef.startsWith("mail:")) listOf(Attachment(newId(), AttachmentKind.MAIL, p.sourceRef, p.sourceSubject)) else emptyList()
            created = base.copy(details = p.details, dateTarget = p.dateTarget, dateDeadline = p.dateDeadline, location = p.location, attachments = attachments)
            saveTask(created)
        }
        update("proposals", ContentValues().apply {
            put("status", if (approve) ProposalStatus.APPROVED.id else ProposalStatus.REJECTED.id)
            put("decided_at", clock()); put("task_id", created?.id ?: "")
        }, "id = ?", arrayOf(id))
        created
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private fun taskValues(t: Task, created: Boolean) = ContentValues().apply {
        put("id", t.id); put("list_id", t.listId); put("parent_id", t.parentId); put("name", t.name); put("details", t.details)
        put("date_target", t.dateTarget); put("date_deadline", t.dateDeadline); put("location", t.location)
        put("status", t.status.id); put("position", t.position)
        if (created) put("created_at", clock())
        put("updated_at", clock())
    }

    private fun nextPosition(table: String, column: String?, value: String?): Int {
        val sql = if (column == null) "SELECT COALESCE(MAX(position), -1) + 1 FROM $table"
        else "SELECT COALESCE(MAX(position), -1) + 1 FROM $table WHERE $column = ?"
        return db.rawQuery(sql, if (column == null) null else arrayOf(value)).use { it.moveToFirst(); it.getInt(0) }
    }

    companion object {
        const val SEED_KEY = "seed_project_zero"
        const val SAMPLE_SEED = "seed_sample_"
        const val SAMPLE_FLAG = "sample_project:"
        fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

private inline fun <T> Cursor.rows(read: () -> T): List<T> {
    val out = ArrayList<T>()
    while (moveToNext()) out.add(read())
    return out
}

private inline fun <T> SQLiteDatabase.transaction(body: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val r = body()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}
