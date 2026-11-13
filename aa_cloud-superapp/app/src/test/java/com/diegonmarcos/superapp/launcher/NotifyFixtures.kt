package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.boards.HealthAgg
import com.diegonmarcos.superapp.boards.HealthFixtures
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.HealthBadgeModel
import java.time.LocalDate

/** What a phone's badge providers would answer, for the Robolectric renders (no service runs in a unit test). */
internal object NotifyFixtures {
    fun badge(b: BadgeDeclaration.Badge): BadgeCardData.Data {
        fun d(headline: String, sub: String, progress: Float? = null, lines: List<BadgeCardData.Line> = emptyList()) =
            BadgeCardData.Data(b, b.label, headline, sub, progress, lines, true, "", System.currentTimeMillis(), null)
        fun l(a: String, v: String) = BadgeCardData.Line(a, v)
        return when (b.id) {
            "network_mesh" -> d("Mesh up", "wg0 · 5 peers · handshake 12 s ago", null, listOf(l("DNS", "10.0.0.1"), l("Up since", "3h 12m")))
            "network_data" -> d("4.2 GB", "Mobile this month · forecast 11 GB", 0.38f, listOf(l("SIM 1", "3.1 GB"), l("Wi-Fi", "18.4 GB")))
            "ai_usage" -> d("Claude week 41% · resets in 2d 3h", "OpenRouter $37.65 · 24h $2.00", 0.41f, listOf(l("", "Used: 41% (in 4.9 days) | 8%/day avg"), l("", "Left: 59% (2.1 days)    | 28%/day"), l("5h session", "23% · resets 15:30 (in 3h 12m)"), l("OR balance", "$37.65")))
            "battery_status" -> d("78%", "Charging · 42 min to full", 0.78f, listOf(l("Temp", "31.2 °C"), l("Health", "Good")))
            "myhealth" -> HealthBadgeModel.face(Read.Ok(HealthAgg.board(HealthFixtures.seeded(LocalDate.now()), LocalDate.now()))).let {
                d(it.title, it.text, null, listOf(l("", HealthBadgeModel.updated(4 * 60_000L)))) }
            "weather_today" -> d("18° · 💧20%", "Madrid · High 22° Low 11°", null, listOf(l("15h", "21°"), l("18h", "19°")))
            "markets_prices" -> d("EUR/USD 1.0842", "updated 4m ago", null,
                b.instruments.mapIndexed { i, ins -> l(ins.label, String.format(java.util.Locale.US, "%.${ins.decimals}f", 1.0 + i * 41.37)) })
            "kde_status" -> d("Pixel 8 · connected", "Battery 81% · Wi-Fi", 0.81f)
            "media_now_playing" -> d("Blue in Green", "Miles Davis · Kind of Blue", 0.42f)
            "floating_nav_quick_actions" -> d("Quickmarks", "5 pinned · tap to open")
            "presets_state" -> d("Presets · Mode: Focus Mode", "Sandbox: Personal · Theme: Cloud Purple")
            "fleet_alerts" -> d("2 alerts", "gcp-proxy disk at 91 %", null, listOf(l("Backups", "Nightly backup slow")))
            else -> d(b.label, "ready")
        }
    }

    fun install() { BadgeCardData.override = { _, b -> badge(b) } }
    fun uninstall() { BadgeCardData.override = null }
}
