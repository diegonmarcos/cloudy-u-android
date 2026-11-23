package com.diegonmarcos.superapp.launcher.pins

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Process
import android.widget.Toast
import com.diegonmarcos.superapp.apps.PhoneAppClassifier
import com.diegonmarcos.superapp.apps.PhoneApp
import com.diegonmarcos.superapp.apps.PhoneFolders

/** #1860 test tags of a pin tile on the Phone page (UiAutomator resource ids and Compose tests). */
object PinnedTags {
    fun tile(key: String) = "pins:tile:$key"
    const val REMOVE = "pins:remove"
}

/** Launch a pin: a SYSTEM pin through LauncherApps.startShortcut, an IPC pin as its web address in the app that added it. The ONE launch path. */
fun launchPin(ctx: Context, pin: PinnedShortcutStore.Pin): Boolean = runCatching {
    when (pin.kind) {
        PinnedShortcutStore.Kind.SYSTEM ->
            (ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps).startShortcut(pin.pkg, pin.id, null, null, Process.myUserHandle())
        PinnedShortcutStore.Kind.IPC ->
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pin.url)).setPackage(pin.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    true
}.getOrElse { Toast.makeText(ctx, "Could not open “${pin.label}”", Toast.LENGTH_SHORT).show(); false }

/**
 * #1860 home shortcuts live on the Phone page, filed into the Phone folders exactly like installed apps. A pin joins the PHONE scope under the
 * synthetic key `pin:<pkg>/<id>` (a ':' is illegal in an Android package name, so it never collides with a real app); the user's folder
 * overrides and assignments (FolderGridBridge, FolderRepository) therefore work on a pin like on an app. The classifier reads the pin's label
 * plus its url host (a SYSTEM pin has no url: its owner package), through the same PhoneAppClassifier keywords as apps; no match = the sink
 * folder ("Others"). Pure (no Context) except [icon] / [launch] / [remove].
 */
object PinnedFolders {
    const val PREFIX = "pin:"

    fun appKey(p: PinnedShortcutStore.Pin) = PREFIX + p.key
    fun isAppKey(k: String) = k.startsWith(PREFIX)

    /** The host of an http(s) url, "" when there is none. */
    fun hostOf(url: String): String =
        url.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()

    /** The folder id the baked taxonomy files [p] under (the sink when nothing matches). */
    fun classify(p: PinnedShortcutStore.Pin, folders: List<PhoneFolders.Folder>): String =
        PhoneAppClassifier.classify(hostOf(p.url).ifEmpty { p.pkg }, p.label, folders)

    fun toApp(p: PinnedShortcutStore.Pin, icon: android.graphics.drawable.Drawable?, user: android.os.UserHandle) =
        PhoneApp(appKey(p), null, p.label, icon, user, p.addedAt)

    /** [grouped] plus every pin in its classified folder (a new map; [grouped] may be a shared cache and is never mutated). */
    fun merge(
        grouped: Map<String, List<PhoneApp>>, pins: List<PinnedShortcutStore.Pin>, folders: List<PhoneFolders.Folder>,
        app: (PinnedShortcutStore.Pin) -> PhoneApp,
    ): Map<String, List<PhoneApp>> {
        if (pins.isEmpty()) return grouped
        val out = LinkedHashMap<String, List<PhoneApp>>(grouped)
        for (p in pins) { val f = classify(p, folders); out[f] = out[f].orEmpty() + app(p) }
        return out
    }

    /** The stored pins as Phone-page apps, with their PNG icon. */
    fun apps(ctx: Context): List<PhoneApp> {
        val me = Process.myUserHandle()
        return PinnedShortcutStore.list(ctx).map { p -> toApp(p, icon(ctx, p), me) }
    }

    fun icon(ctx: Context, p: PinnedShortcutStore.Pin): android.graphics.drawable.Drawable? =
        p.iconFile.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }?.let { BitmapDrawable(ctx.resources, it) }

    fun pinOf(ctx: Context, appKey: String): PinnedShortcutStore.Pin? =
        if (isAppKey(appKey)) PinnedShortcutStore.list(ctx).firstOrNull { appKey(it) == appKey } else null

    /** Launch the pin behind [appKey]; false when it is not a pin (or gone). */
    fun launch(ctx: Context, appKey: String): Boolean = pinOf(ctx, appKey)?.let { launchPin(ctx, it) } ?: false

    fun remove(ctx: Context, appKey: String): Boolean = pinOf(ctx, appKey)?.let { PinnedShortcutStore.remove(ctx, it.key) } ?: false
}
