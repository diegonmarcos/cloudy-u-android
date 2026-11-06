package com.diegonmarcos.cloudagenda

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.MemoryNotesStore
import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.NotesDecl
import com.diegonmarcos.cloudagenda.data.NotesModel
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksPrefs
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.cloudagenda.ui.TasksController
import com.diegonmarcos.cloudagenda.ui.TasksEnv
import com.diegonmarcos.cloudagenda.ui.TasksHost
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiOutcome
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** A node whose test tag starts with [prefix]: the rows of a list, whose ids are generated. */
fun tagStartsWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

/** Registers the compose test activity with Robolectric first (the libs:bottomnav / Cloud Drive precedent), then the compose rule. */
fun <R : TestRule> composeChain(compose: R): RuleChain = RuleChain.outerRule(TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            base.evaluate()
        }
    }
}).around(compose)

class FakeMailSource(var items: List<MailItem> = emptyList(), var reason: String? = null) : MailSource {
    override fun unavailableReason(): String? = reason
    override fun recent(since: Long, limit: Int, subject: String): List<MailItem> = items.filter { subject.isBlank() || subject.lowercase() in it.subject.lowercase() }
    override fun bodyText(accountId: String, id: String): String = ""
}

/** A chat host that answers "ok" without a network. */
class FakeAiHost : AiHost {
    override var model: String = "test/model"
    override var web: Boolean = false
    override fun newSession(model: String, now: Long) = AiSession("s-$now", "", model, now, emptyList())
    override fun models(): List<AiModel> = emptyList()
    override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome =
        AiOutcome(session.copy(title = text, messages = session.messages + AiMsg("user", text) + AiMsg("assistant", "ok")), null, emptyList())
}

/** The shell's environment over an in-memory database, with everything recorded so a test can assert on it. */
class FakeEnv(context: Context, seed: Boolean = true) : TasksEnv {
    val repo = TasksRepository(TasksDb(context, null))
    val prefs = TasksPrefs(context.getSharedPreferences("tasks-test-" + System.nanoTime(), Context.MODE_PRIVATE))
    // A user who already has a project is never given the sample data (seeding runs only on an empty database).
    init { if (!seed) repo.addProject("Mine") }
    override val controller = TasksController(repo, prefs, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined).also { it.start() }

    var savedAgent: AgentConfig? = null
    var savedSecret: String = ""
    var savedMode: ThemeMode? = null
    val opened = ArrayList<Attachment>()
    val pickedKinds = ArrayList<AttachmentKind>()
    val mailSource = FakeMailSource()

    override var themeMode: ThemeMode = ThemeMode.LIGHT
    override fun saveThemeMode(mode: ThemeMode) { savedMode = mode; themeMode = mode; prefs.themeMode = mode }

    override val host = object : TasksHost {
        override val mail: MailSource = mailSource
        override fun pickDocument(kind: AttachmentKind) { pickedKinds.add(kind) }
        override fun open(a: Attachment) { opened.add(a) }
    }
    override var pickedLink: Attachment? by mutableStateOf(null)
    override fun consumePicked() { pickedLink = null }

    override val mock = MockTasks.load(context)
    override val structure = Structure.load(context)
    override val notes = NotesModel(MemoryNotesStore(), NotesDecl.load(context))
    override val chat = AiChatState(FakeAiHost())
    override val agents: List<AiAgent> = listOf(AiAgent("hermes", "Hermes", "tools", "hermes"))
    override fun loadModels(): List<AiModel> = listOf(AiModel("m/one", "Model one"))
    override val secret: String = ""
    override val secretAvailable: Boolean = true
    override fun saveAgent(config: AgentConfig, secret: String) { savedAgent = config; savedSecret = secret; controller.saveAgent(config) }
    override fun runAgent() {}
}
