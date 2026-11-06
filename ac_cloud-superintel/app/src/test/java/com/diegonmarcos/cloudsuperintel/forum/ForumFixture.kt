package com.diegonmarcos.cloudsuperintel.forum

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiColors
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiOutcome
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.ForumTopic
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A minimal in-memory host so a chat can be seeded with messages for a render test; no network. */
class FakeHost(override var model: String = "fake/model", override var web: Boolean = false) : AiHost {
    override fun newSession(model: String, now: Long) = AiSession("s-$model", "", model, now, emptyList())
    override fun models(): List<AiModel> = emptyList()
    override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome =
        AiOutcome(session, null, emptyList())
}

/** The forum test zone, a fixed "now", and helpers to build dated messages. */
object ForumFixture {
    val zone: ZoneId = ZoneId.of("UTC")
    val now: Long = millis("2026-09-20", "14:00")

    fun millis(date: String, time: String): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun chatWith(messages: List<AiMsg>, sending: Boolean = false): AiChatState =
        AiChatState(FakeHost()).also {
            it.session = it.session.copy(updated = now, messages = messages)
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
