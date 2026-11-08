package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiAttachment
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiComposerDecl
import com.diegonmarcos.superapp.ai.AiColors
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiOutcome
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.ForumTopic
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A minimal in-memory host so a chat can be seeded with messages for a render test; no network. */
class FakeHost(override var model: String = "fake/model", override var web: Boolean = false) : AiHost {
    override fun newSession(model: String, now: Long) = AiSession("s-$model", "", model, now, emptyList())
    override fun models(): List<AiModel> = emptyList()
    override fun agents(): List<AiAgent> = listOf(AiAgent("claude", "Claude", "Anthropic agent"), AiAgent("hermes", "Hermes", "Open agent"))
    override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome =
        AiOutcome(session, null, emptyList())
}

/** The forum test zone, a fixed "now", and helpers to build dated messages. */
object ForumFixture {
    val zone: ZoneId = ZoneId.of("UTC")
    val now: Long = millis("2026-09-20", "14:00")

    fun millis(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun chatWith(messages: List<AiMsg>, sending: Boolean = false, title: String = "", id: String? = null): AiChatState =
        AiChatState(FakeHost()).also {
            it.session = it.session.copy(id = id ?: it.session.id, title = title, updated = now, messages = messages)
            it.sending = sending
        }

    /** The seeded topics: General (router) with a two-day conversation, plus two expert topics. */
    fun topics(sending: Boolean = false): List<ForumTopic> = listOf(
        ForumTopic(
            id = "si", label = "General", badge = "#", senderName = "SI",
            chat = chatWith(
                listOf(
                    AiMsg("user", "Good morning, what is on today?", at = millis("2026-09-19", "09:00")),
                    AiMsg("assistant", "Three meetings and the release.\nWant the agenda?", meta = "SI · fake/model", at = millis("2026-09-19", "09:01")),
                    AiMsg("user", "Yes please, and the link https://example.com", at = millis("2026-09-20", "13:58")),
                    AiMsg("assistant", "Here it is.", meta = "SI · fake/model", at = millis("2026-09-20", "13:59")),
                ),
                sending = sending,
            ),
        ),
        ForumTopic(id = "math", label = "Math", badge = "Mat", senderName = "Math",
            chat = chatWith(listOf(AiMsg("user", "Prove it", at = millis("2026-09-20", "10:00"))))),
        ForumTopic(id = "physics", label = "Physics", badge = "Phy", chat = chatWith(emptyList())),
    )

    /** The composer declaration of the app (assets/ai/composer.json): every control the shared design has. */
    val composerDecl = AiComposerDecl(
        "cloud-superintel",
        listOf("plus", "task", "specialist", "agent", "model", "effort", "mode", "skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more"),
        repos = listOf("cloudy-u-android", "cloudy-t-infra"),
    )

    /** A real PNG on disk, so the inline image path (decode, draw) runs for real: image in (the user's) and image out (the answer's). */
    fun image(id: String): AiAttachment {
        val f = File.createTempFile("si-$id", ".png")
        val bmp = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF2D2350.toInt())
        canvas.drawCircle(160f, 100f, 60f, Paint().apply { color = 0xFFB794F4.toInt() })
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f.deleteOnExit()
        return AiAttachment(id, "$id.png", "image/png", f.path, f.length())
    }

    /**
     * The rich topics (personas): SI with a markdown answer, an image in and an image out with the agent · model badge, plus two experts.
     * Message indices: 0 user, 1 assistant, 2 user + image in, 3 assistant + image out.
     */
    fun richTopics(): List<ForumTopic> = listOf(
        ForumTopic(
            id = "si", label = "General", badge = "#", senderName = "SI",
            chat = chatWith(
                listOf(
                    AiMsg("user", "Plan the release and compare two models.", at = millis("2026-09-20", "13:40")),
                    AiMsg("assistant", "**Plan**\n\n- freeze Thursday\n- ship Friday", meta = "SI · anthropic/claude", at = millis("2026-09-20", "13:41")),
                    AiMsg("user", "Describe this picture", attachments = listOf(image("in")), at = millis("2026-09-20", "13:58")),
                    AiMsg("assistant", "Here is a sketch.", meta = "SI · openrouter/image", attachments = listOf(image("out")), at = millis("2026-09-20", "13:59")),
                ),
                title = "Release planning", id = "s-release",
            ),
        ),
        ForumTopic(id = "math", label = "Math", badge = "Mat", senderName = "Math",
            chat = chatWith(listOf(AiMsg("user", "Prove it", at = millis("2026-09-20", "10:00"))), title = "Proofs", id = "s-proofs")),
        ForumTopic(id = "physics", label = "Physics", badge = "Phy", chat = chatWith(emptyList(), title = "", id = "s-fresh")),
    )

    /** The sessions the left rail lists (the user's conversations), newest first; [currentId] is the open one. */
    fun sessionList(): List<AiSession> = listOf(
        AiSession("s-release", "Release planning", "fake/model", now, emptyList()),
        AiSession("s-proofs", "Proofs", "fake/model", now - 3_600_000, emptyList()),
        AiSession("s-energy", "Energy storage", "fake/model", now - 86_400_000, emptyList()),
        AiSession("s-law", "Contract review", "fake/model", now - 172_800_000, emptyList()),
    )

    private fun dark() = AiColors(
        text = Color(0xFFECE8F7), text2 = Color(0xFFA79FC2), accent = Color(0xFFB794F4), negative = Color(0xFFFF8A80),
        field = Color(0xFF221B3B), tile = Color(0xFF17122A), tileBorder = Color(0xFF3A3157), chatBar = Color(0xFF17122A),
        glassBorder = Color(0xFF3A3157), userBubble = Color(0xFF2D2350), onUserBubble = Color(0xFFECE8F7),
        onAi = Color(0xFF14082B), ai = SolidColor(Color(0xFFB794F4)),
    )

    private fun light() = AiColors(
        text = Color(0xFF1A1626), text2 = Color(0xFF5B5570), accent = Color(0xFF7C4DD6), negative = Color(0xFFC0392B),
        field = Color(0xFFFFFFFF), tile = Color(0xFFF1EEF9), tileBorder = Color(0xFFDCD6EC), chatBar = Color(0xFFF6F4FC),
        glassBorder = Color(0xFFDCD6EC), userBubble = Color(0xFFE7DEFB), onUserBubble = Color(0xFF201433),
        onAi = Color(0xFFFFFFFF), ai = SolidColor(Color(0xFF7C4DD6)),
    )

    fun theme(dark: Boolean): AiTheme = AiTheme(colors = if (dark) dark() else light())
    fun adminColor(dark: Boolean): Color = if (dark) Color(0xFF7EE2A8) else Color(0xFF2E7D32)
    fun windowColor(dark: Boolean): Color = if (dark) Color(0xFF0F0B1A) else Color(0xFFFFFFFF)
}
