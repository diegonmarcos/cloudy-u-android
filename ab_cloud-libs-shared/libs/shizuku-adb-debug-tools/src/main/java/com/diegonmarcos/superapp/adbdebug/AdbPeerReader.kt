package com.diegonmarcos.superapp.adbdebug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.core.FleetConfig

/** Reads the channel state of every app in [AdbPeers.FLEET]: this app's directly, the others' through their [AdbStatusProvider]. */
object AdbPeerReader {

    private const val OWN_TTL_MS = 5_000L
    private data class Probe(val pkg: String, val at: Long, val link: PeerLink)
    @Volatile private var ownProbe: Probe? = null

    /** This app's own link, probed at most every [OWN_TTL_MS] (the probe opens a socket and may run a shell `id`). */
    fun ownLink(ctx: Context, now: Long = System.currentTimeMillis()): PeerLink {
        val app = ctx.applicationContext
        ownProbe?.takeIf { it.pkg == app.packageName && now - it.at in 0..OWN_TTL_MS }?.let { return it.link }
        val link = runCatching { AdbPeers.linkOf(ChannelState.connState(ChannelReader.read(app))) }.getOrDefault(PeerLink.DOWN)
        ownProbe = Probe(app.packageName, now, link)
        return link
    }

    fun own(ctx: Context, peer: AdbPeer): PeerStatus = PeerStatus(peer, ownLink(ctx), PairingClaim.held, PairingClaim.since)

    /** One peer's status over its provider; any failure (not installed, no permission, process dead) is [PeerLink.UNREACHABLE]. */
    fun remote(ctx: Context, peer: AdbPeer): PeerStatus {
        val b = runCatching { ctx.contentResolver.call(Uri.parse("content://${peer.authority}"), AdbPeers.METHOD_STATUS, null, null) }.getOrNull()
        if (b == null || !b.containsKey(AdbPeers.KEY_LINK)) return PeerStatus(peer, PeerLink.UNREACHABLE)
        return PeerStatus(peer, AdbPeers.parseLink(b.getString(AdbPeers.KEY_LINK)), b.getBoolean(AdbPeers.KEY_PAIRING), b.getLong(AdbPeers.KEY_PAIRING_SINCE))
    }

    /** Every app in the fleet, this one first-hand. Blocking (binder calls and a probe): never on the main thread. */
    fun read(ctx: Context, peers: List<AdbPeer> = AdbPeers.FLEET): List<PeerStatus> =
        peers.map { if (it.applicationId == ctx.packageName) own(ctx, it) else remote(ctx, it) }

    /** Who else holds the pairing window, or null. Blocking, like [read]. */
    fun pairingHeldElsewhere(ctx: Context): PeerStatus? =
        AdbPeers.pairingHeldElsewhere(ctx.packageName, peers(ctx), System.currentTimeMillis())

    private fun peers(ctx: Context): List<PeerStatus> =
        AdbPeers.FLEET.filter { it.applicationId != ctx.packageName }.map { remote(ctx, it) }
}

/**
 * This app's channel state for the OTHER app (the SuperApp's home status shows one dot per app) and its pairing claim.
 * Read-only: [AdbPeers.METHOD_STATUS] is the one method and nothing a caller sends is written. Exported behind
 * CONSTELLATION_DATA (signature) and re-checked in call(), because the framework does not permission-check call() by
 * itself (the FleetBearerProvider pattern). No key, token, port or endpoint leaves the app: link state and a claim only.
 */
class AdbStatusProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED) return Bundle()
        if (method == WdKeeper.METHOD_CLAIM) return WdKeeper.claimBundle(ctx)   // #1467 leader election
        if (method != AdbPeers.METHOD_STATUS) return Bundle()
        return Bundle().apply {
            putString(AdbPeers.KEY_LINK, AdbPeerReader.ownLink(ctx).name)
            putBoolean(AdbPeers.KEY_PAIRING, PairingClaim.held)
            putLong(AdbPeers.KEY_PAIRING_SINCE, PairingClaim.since)
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
