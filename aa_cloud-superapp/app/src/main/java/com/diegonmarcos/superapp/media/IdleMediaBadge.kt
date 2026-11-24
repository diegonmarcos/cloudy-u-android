package com.diegonmarcos.superapp.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import com.diegonmarcos.superapp.network.BtLinks

/**
 * #1894 the Media badge when nothing plays: it stays on the Notify page and in the Notification Center and shows the buds status (connected,
 * battery per bud and case where the device reports it), three buttons (the buds' own app, else Bluetooth device settings; the last-used media
 * player; Play for the last track, Tidal or Spotify) and that last track (title, artist, artwork) from [LastMediaSession]. Pure model: the
 * Android parts (reading the Bluetooth link, sending the actions) are [read] and [run]. While something plays the badge is built exactly as
 * before (this model is not used), so nothing is removed.
 */
internal object IdleMediaBadge {
    enum class Button { BUDS, OPEN_PLAYER, PLAY_LAST }

    data class Buds(val name: String, val connected: Boolean, val batteryText: String)

    data class Model(
        val headline: String, val sub: String, val art: Bitmap?, val buds: Buds, val buttons: List<Button>,
        /** The player the Open button launches and Play resumes; null when no track was ever played. */
        val playerPkg: String?, val playEnabled: Boolean,
    )

    /** The earbuds among [links]: a link whose name maps to a known buds app, else one that reports a left/right/case battery. Null when none is connected. */
    fun pickBuds(links: List<BtLinks.Link>): BtLinks.Link? =
        links.firstOrNull { MusicBadgeLayout.budsPackages(it.name).isNotEmpty() }
            ?: links.firstOrNull { it.battery.left >= 0 || it.battery.right >= 0 || it.battery.case >= 0 }

    fun budsOf(links: List<BtLinks.Link>, lastName: String?): Buds {
        val b = pickBuds(links)
        return if (b != null) Buds(b.name, true, BtLinks.batteryText(b.battery))
        else Buds(lastName.orEmpty().ifBlank { "Buds" }, false, "—")
    }

    /** The idle badge from the persisted last session ([last], null if never played) and the connected Bluetooth [links]. */
    fun model(last: LastMediaSession.Last?, links: List<BtLinks.Link>, lastBudsName: String? = null): Model {
        val buds = budsOf(links, lastBudsName)
        val sub = last?.artist?.takeIf { it.isNotBlank() } ?: if (last == null) "Nothing played yet" else ""
        return Model(
            headline = last?.title ?: "Media", sub = sub, art = last?.art, buds = buds,
            buttons = listOf(Button.BUDS, Button.OPEN_PLAYER, Button.PLAY_LAST),
            playerPkg = last?.pkg, playEnabled = last != null,
        )
    }

    /** The buds line: "Buds · connected · L 80% · R 75% · Case 40%" or "Buds · not connected". */
    fun budsLine(b: Buds): String =
        if (b.connected) "${b.name} · connected" + (if (b.batteryText != "—") " · ${b.batteryText}" else "") else "${b.name} · not connected"

    fun label(b: Button): String = when (b) { Button.BUDS -> "Buds"; Button.OPEN_PLAYER -> "Player"; Button.PLAY_LAST -> "Play" }

    /** Android: the connected links now (empty without Bluetooth permission). */
    fun read(ctx: Context): Model = model(
        LastMediaSession.load(ctx), runCatching { BtLinks.read(ctx).links }.getOrDefault(emptyList()),
        ctx.applicationContext.getSharedPreferences("idle_media_badge", Context.MODE_PRIVATE).getString("buds", null),
    ).also { m -> if (m.buds.connected) ctx.applicationContext.getSharedPreferences("idle_media_badge", Context.MODE_PRIVATE).edit().putString("buds", m.buds.name).apply() }

    /** Android: do what a button says. Never throws. */
    fun run(ctx: Context, m: Model, b: Button) {
        when (b) {
            Button.BUDS -> MusicBadgeLayout.openBudsApp(ctx, m.buds.name)
            Button.OPEN_PLAYER -> {
                val intent = m.playerPkg?.let { runCatching { ctx.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
                    ?: Intent(Settings.ACTION_SOUND_SETTINGS)
                runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Button.PLAY_LAST -> runCatching { MediaHub.send(ctx, MediaHub.Action.PLAY) }
        }
    }
}
