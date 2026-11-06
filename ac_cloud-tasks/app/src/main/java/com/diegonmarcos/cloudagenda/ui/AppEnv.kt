package com.diegonmarcos.cloudagenda.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.cloudagenda.agent.AgentRunners
import com.diegonmarcos.cloudagenda.agent.AgentScheduler
import com.diegonmarcos.cloudagenda.agent.CloudMailProvider
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.cloudagenda.data.AgentConfig
import com.diegonmarcos.cloudagenda.data.AgentSecrets
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.MockTask
import com.diegonmarcos.cloudagenda.data.MockTasks
import com.diegonmarcos.cloudagenda.data.NotesDecl
import com.diegonmarcos.cloudagenda.data.NotesModel
import com.diegonmarcos.cloudagenda.data.PrefsNotesStore
import com.diegonmarcos.cloudagenda.data.Structure
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksPrefs
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.cloudagenda.links.Links
import com.diegonmarcos.cloudagenda.links.newAttachment
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiModel

/**
 * The real [TasksEnv]: the database, the prefs, Cloud Mail's agent provider, the fleet AI gateway, the secret store
 * and the hand-offs to Cloud Account / Cloud Mail / Cloud Drive, all built once for the activity.
 * [launchPicker] starts the system document picker for an Image or File link and reports back through [picked].
 */
class AppEnv(private val activity: ComponentActivity, private val launchPicker: (AttachmentKind) -> Unit) : TasksEnv {
    private val app = activity.applicationContext
    private val repo = TasksRepository(TasksDb.shared(app))
    private val prefs = TasksPrefs(app)
    private val links = Links(app, BuildConfig.MAIL_PACKAGE, BuildConfig.DRIVE_PACKAGE)
    private val secrets by lazy { AgentSecrets(app) }
    private val aiConfig by lazy { AiConfig.load(app) }

    private var pendingKind = AttachmentKind.FILE
    override var pickedLink by mutableStateOf<Attachment?>(null)
        private set

    override val controller = TasksController(repo, prefs, activity.lifecycleScope).also { it.start() }

    override val themeMode: ThemeMode get() = prefs.themeMode
    override fun saveThemeMode(mode: ThemeMode) { prefs.themeMode = mode }

    override val host = object : TasksHost {
        override val mail: MailSource = CloudMailProvider(app, BuildConfig.MAIL_PACKAGE)
        override fun pickDocument(kind: AttachmentKind) { pendingKind = kind; launchPicker(kind) }
        override fun open(a: Attachment) = links.open(a)
    }

    /** The picker's answer: the link joins the open editor's draft. */
    fun picked(uri: android.net.Uri) {
        links.keep(uri)
        pickedLink = newAttachment(pendingKind, uri.toString(), links.nameOf(uri))
    }

    override fun consumePicked() { pickedLink = null }

    override val mock: List<MockTask> by lazy { MockTasks.load(app) }
    override val structure: Structure by lazy { Structure.load(app) }
    override val notes: NotesModel by lazy { NotesDecl.load(app).let { NotesModel(PrefsNotesStore(app, it), it) } }

    override val chat: AiChatState get() = TasksAi.chat(app, repo)
    override val agents: List<AiAgent> by lazy { AiAgents.load(app) }
    override fun loadModels(): List<AiModel> = AiGatewayBackend(aiConfig, BuildConfig.AI_GATEWAY_URL).models()

    override val secret: String get() = secrets.secret
    override val secretAvailable: Boolean get() = secrets.available

    override fun saveAgent(config: AgentConfig, secret: String) {
        controller.saveAgent(config)
        secrets.secret = secret
        AgentScheduler.apply(app, config)
    }

    override fun runAgent() { AgentRunners.build(app).run() }

}
