package com.diegonmarcos.cloudagenda.data

/** A task's (and a subtask's) status. [id] is what the database and the persisted filter store. */
enum class TaskStatus(val id: String, val label: String) {
    ACTION("action", "Action"),
    WAITING("waiting", "Waiting"),
    HOLD("hold", "Hold"),
    COMPLETED("completed", "Completed"),
    ARCHIVED("archived", "Archived");

    companion object {
        /** An unknown id (a newer build wrote it) reads as Action, never as a crash. */
        fun of(id: String?): TaskStatus = entries.firstOrNull { it.id == id } ?: ACTION
    }
}

/** What an attachment points at. The content is never stored here, only the link. */
enum class AttachmentKind(val id: String, val label: String) {
    MAIL("mail", "Mail"),
    IMAGE("image", "Image"),
    FILE("file", "File");

    companion object {
        fun of(id: String?): AttachmentKind = entries.firstOrNull { it.id == id } ?: FILE
    }
}

/** [sample] = a seeded sample project (SampleProjects) the user can delete; the flag lives in the meta table, not in the schema. */
data class Project(val id: String, val name: String, val position: Int, val sample: Boolean = false)

data class TaskList(val id: String, val projectId: String, val name: String, val position: Int)

/** [color] is an ARGB int in a Long so it survives SQLite's signed INTEGER without sign games. */
data class Tag(val id: String, val name: String, val color: Long)

/** A link to something that lives in another app: [uri] is the link, [label] what the row shows. */
data class Attachment(val id: String, val kind: AttachmentKind, val uri: String, val label: String)

/**
 * A task or a subtask: ONE shape. A subtask is a task whose [parentId] is set, so it carries every field a
 * task does (name, details, both dates, location, attachments, status, tags). Dates are ISO yyyy-MM-dd text
 * (no time zone to get wrong), null when unset.
 */
data class Task(
    val id: String,
    val listId: String,
    val parentId: String? = null,
    val name: String,
    val details: String = "",
    val dateTarget: String? = null,
    val dateDeadline: String? = null,
    val location: String = "",
    val status: TaskStatus = TaskStatus.ACTION,
    val position: Int = 0,
    val tagIds: Set<String> = emptySet(),
    val attachments: List<Attachment> = emptyList(),
)

/** Everything the Tasks page draws, read in one pass. Small by nature, so the page filters it in memory. */
data class Snapshot(
    val projects: List<Project> = emptyList(),
    val lists: List<TaskList> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
) {
    fun listsOf(projectId: String): List<TaskList> = lists.filter { it.projectId == projectId }
    fun topLevel(listId: String): List<Task> = tasks.filter { it.listId == listId && it.parentId == null }
    fun subtasksOf(taskId: String): List<Task> = tasks.filter { it.parentId == taskId }
    fun task(id: String?): Task? = if (id == null) null else tasks.firstOrNull { it.id == id }
    fun list(id: String?): TaskList? = if (id == null) null else lists.firstOrNull { it.id == id }
    fun project(id: String?): Project? = if (id == null) null else projects.firstOrNull { it.id == id }
    fun tag(id: String): Tag? = tags.firstOrNull { it.id == id }
}

/** One top-level task with the subtasks that are shown under the current filter. */
data class TaskNode(val task: Task, val subtasks: List<Task>)
