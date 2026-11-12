package com.diegonmarcos.superapp.system

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * Screen-lock orchestrator — wraps [DevicePolicyManager] so callers
 * (the home-screen double-tap detector + Configs/About status row)
 * don't have to know about ComponentName, Intent boilerplate, or the
 * "is this admin already active?" check. Single source of truth for:
 *   • the admin component identity
 *   • activation request flow (system dialog)
 *   • lock-now invocation
 *   • status string for the Permissions UI
 *
 * Backed by [LockScreenAdminReceiver] + res/xml/device_admin_policy.xml
 * (force-lock scope ONLY — see the policy file for the why).
 *
 * Permission cost to the user: ONE entry in
 *   Settings → Security → Device admin apps
 * which lists "Lock screen on home double-tap" as the only granted
 * capability. No wipe, no reset, no warnings.
 */
object ScreenLocker {

    /** ComponentName of [LockScreenAdminReceiver]. Stable across
     *  process restarts — only depends on `ctx.packageName`. */
    fun componentName(ctx: Context): ComponentName =
        ComponentName(ctx, LockScreenAdminReceiver::class.java)

    private fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True iff the user has confirmed the Device Admin grant
     *  dialog. False covers both "never granted" and "granted then
     *  revoked from Settings". Safe to call before activation. */
    fun isActive(ctx: Context): Boolean =
        runCatching { dpm(ctx).isAdminActive(componentName(ctx)) }.getOrDefault(false)

    /** Lock the screen NOW. Tries the two paths in this order:
     *
     *  1. **Accessibility-service** (preferred) —
     *     [AccessibilityService.performGlobalAction] with
     *     [AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN]. Requires
     *     API 28+ AND the user-granted accessibility-service bind.
     *     Behaves like pressing the power button: Smart Lock
     *     (Trusted Device for a paired watch, Trusted Place for
     *     home, Voice Match) and biometric unlock STAY ACTIVE on
     *     next wake. This is what a launcher SHOULD do.
     *
     *  2. **DevicePolicyManager.lockNow** (fallback) — only used
     *     when accessibility isn't granted. Trips the strong-auth-
     *     required bit on most builds, so Smart Lock + biometric
     *     are temporarily disabled and the user must enter the PIN
     *     on next unlock. Still useful as a guaranteed fallback for
     *     users who can't / won't grant accessibility.
     *
     *  Returns true on success, false when NEITHER path is
     *  available. The caller surfaces a Toast on false so the user
     *  knows where to enable it (we point them at the
     *  accessibility row first since that's the better UX). */
    fun lock(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val svc = LockScreenAccessibilityService.instance
            if (svc != null) {
                val ok = runCatching {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        return runCatching {
            if (!isActive(ctx)) return false
            dpm(ctx).lockNow()
            true
        }.getOrDefault(false)
    }

    /** Open the system Device Admin add-dialog targeted at our own
     *  receiver. Activity-scoped because the system dialog needs a
     *  task to attach to. The user sees a single-policy ("force
     *  lock") summary + an explanation we control. */
    fun requestActivation(activity: Activity) {
        // #1515 the add-admin intent is libs:perms' catalogue entry device_admin (it carries our receiver); only the
        // explanation text is added here.
        openPermsSettings(activity, "device_admin") {
            it.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(activity))
            it.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows the Cloud SuperApp to lock the screen on a home double-tap. " +
                    "Only one capability is granted (force-lock). Revoke any time in " +
                    "Settings → Security → Device admin apps.",
            )
        }
    }

    /**
     * #1515 opens the settings page of catalogue entry [id] through libs:perms' own ladder (PermsSettings.intents: the
     * exact page, its fallbacks, App info), first one that answers wins; [extra] decorates each intent. Never throws:
     * false when no page opened or the entry is unknown on this build.
     */
    fun openPermsSettings(ctx: Context, id: String, extra: (Intent) -> Unit = {}): Boolean {
        val e = runCatching { com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).entries.firstOrNull { it.id == id } }.getOrNull()
            ?: return false
        val intents = runCatching { com.diegonmarcos.superapp.perms.PermsSettings.intents(ctx, e) }.getOrDefault(emptyList())
        return intents.any { i -> runCatching { extra(i); ctx.startActivity(i) }.isSuccess }
    }

    /** Open Settings → Security → Device admin apps directly when
     *  admin is already active and the user wants to revoke it from
     *  the same Configs/About row that surfaced it. */
    fun openSystemDeviceAdminSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Open Settings → Accessibility so the user can enable our
     *  LockScreenAccessibilityService — the preferred path because
     *  it preserves Smart Lock + biometric. */
    fun openSystemAccessibilitySettings(ctx: Context) {
        openPermsSettings(ctx, "accessibility")
    }

    /** Open Settings → Apps → Cloud SuperApp (the per-app info
     *  screen). This is the page that on Samsung One UI 13+ hosts
     *  the ⋮ menu → "Allow restricted settings" toggle — the
     *  required first step before Accessibility / NotificationListener
     *  toggles become tappable for sideloaded APKs. Without it,
     *  tapping our service in the Accessibility list silently does
     *  nothing because the toggle is system-disabled. */
    fun openAppInfo(ctx: Context) {
        com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, ctx.packageName)
    }

    /** True iff our accessibility service is enabled in the system's
     *  ENABLED_ACCESSIBILITY_SERVICES setting. Independent of
     *  whether it's currently bound (the bind can flap; the setting
     *  is the persistent "user intent" signal). */
    fun isAccessibilityEnabled(ctx: Context): Boolean =
        LockScreenAccessibilityService.isEnabled(ctx)

    /** Status string for Configs/About → Permissions →
     *  "Lock-screen accessibility (preferred)". */
    fun statusStringAccessibility(ctx: Context): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
            "⚠ API ${Build.VERSION.SDK_INT} < 28 — accessibility lock not supported"
        isAccessibilityEnabled(ctx) ->
            "✓ Granted — Smart Lock + biometric preserved on double-tap"
        else ->
            "⚠ Not granted — tap action below to enable (PREFERRED — keeps Smart Lock alive)"
    }

    /** Status string for Configs/About → Permissions →
     *  "Device admin (lock — fallback)". Mirrors the shape of the
     *  other specialAccess* helpers. */
    fun statusString(ctx: Context): String =
        if (isActive(ctx)) "✓ Granted — used only as fallback when accessibility is off"
        else               "⚠ Not granted — fallback path; only enable if accessibility refuses"

    /** The double-tap-to-lock action for the home screens ([com.diegonmarcos.superapp.ui.lockOnDoubleTap]).
     *  If neither accessibility nor Device Admin is granted yet, the double-tap surfaces a Toast pointing
     *  the user at the right Configs/About row instead of silently doing nothing — the feature would
     *  otherwise feel broken. */
    fun lockOrExplain(ctx: Context) {
        if (!lock(ctx)) {
            Toast.makeText(
                ctx,
                "Enable Accessibility (preferred) or Device Admin in " +
                    "Configs → About → Permissions → Special access — " +
                    "accessibility keeps Smart Lock + watch unlock alive",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
