package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.diegonmarcos.superapp.R

/**
 * "Install this app" for a package the device does not have.
 *
 * WHY THIS IS ONE OBJECT AND NOT AN `if` INSIDE A TILE. Install is not one
 * thing. A fleet app is a direct APK URL pulled by libs:updater through the
 * constellation path; a third-party app is a store intent. Those are different
 * mechanisms with different failure modes, and the caller that drew the tile
 * has no business knowing which is which. Callers ask for an install and get
 * back [Outcome]; the routing lives here, so the NEXT surface that wants a
 * not-installed tile inherits it instead of reimplementing the fork.
 *
 * WHY IT RETURNS A MESSAGE INSTEAD OF SHOWING ONE. A TAP THAT DOES NOTHING IS
 * WORSE THAN A TILE THAT WAS NOT THERE. This repository has already shipped
 * the opposite: the updater was caught handing `installApk` a URL that 404'd
 * and reporting nothing at all, and a PackageInstaller session that committed
 * 267 MB and produced no install, no error and no change on screen. So there
 * is no silent path out of [start] — every branch produces a string, and
 * [Outcome.started] tells the caller whether it is news or a refusal. A caller
 * that ignores the return value is visible as such at the call site.
 *
 * NOT COVERED HERE, ON PURPOSE: a download that BEGINS and then fails (the
 * 404 case) is the updater's own lifecycle. ApkInstallWorker turns any throw
 * into `UpdateProgress.State.Failed(...)` and PackageInstallerReceiver drives
 * the terminal state from the real installer callback — both asserted by
 * test-install-status-surfaced.sh. Re-reporting it here would be a second
 * opinion racing the first.
 */
object AppInstall {

    /** @property started true when an install/store hand-off actually began.
     *  @property message what to put in front of the user. Never blank. */
    data class Outcome(val started: Boolean, val message: String)

    /**
     * Begin installing [pkg], routed by what kind of app it is.
     *
     * [label] is only for the message — it is the curated name, because a
     * package that is not installed has no launcher label to read.
     */
    fun start(context: Context, pkg: String, label: String): Outcome {
        // ── fleet app → the constellation updater ────────────────────────
        // Matched on every id ui.external_apps knows for the app, the same
        // set Sections.constellationPackages builds, so a tile naming a fork
        // or a resigned stock id routes to the fleet path and not to a store
        // that has never heard of it.
        val fleet = Sections.externalApps().firstOrNull { app ->
            pkg == app.hubPackage || pkg == app.altPackage ||
                pkg == app.installPackage || pkg in app.forks.values
        }
        if (fleet != null) {
            if (fleet.installApkUrl.isBlank() || fleet.installPackage.isBlank()) {
                return Outcome(
                    false,
                    context.getString(R.string.phone_install_no_source, label),
                )
            }
            com.diegonmarcos.superapp.extinstall.ExtAppInstalls.start(
                context, fleet.installPackage, fleet.label, fleet.installApkUrl,
            )
            return Outcome(
                true,
                context.getString(R.string.phone_install_downloading, label),
            )
        }

        // ── #1675 a fleet package by the FLEET MANIFEST (not only ui.external_apps) → Cloud Store's queue ──
        // Never a market:// or file intent for a fleet app: Cloud Store, the one installer of record, fetches (or reuses
        // its cache) and installs it with one PackageInstaller session.
        if (com.diegonmarcos.superapp.updater.install.StoreHandoff.isFleetPackage(pkg)) {
            val h = com.diegonmarcos.superapp.updater.install.StoreHandoff.handOff(context, pkg)
            return if (h != null) Outcome(true, h.message ?: label)
            else Outcome(false, context.getString(R.string.phone_install_no_source, label))
        }

        // ── third-party app → the store ──────────────────────────────────
        // Tried in order and DECIDED BY startActivity THROWING, not by
        // resolveActivity returning null: from API 30 package visibility hides
        // an unqueried target from resolveActivity, so a probe would report
        // "no store" on a device that has one and would need a <queries>
        // manifest block to say otherwise. Catching the throw needs no
        // manifest and cannot be wrong about what is actually installed.
        val targets = listOf(
            "market://details?id=$pkg",
            "https://play.google.com/store/apps/details?id=$pkg",
        )
        for (target in targets) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching { context.startActivity(intent); true }
                .getOrDefault(false)
            if (launched) {
                return Outcome(
                    true,
                    context.getString(R.string.phone_install_opening_store, label),
                )
            }
        }

        // Neither a fleet download nor any store could be reached. This is the
        // branch that used to be a tap into nothing.
        return Outcome(
            false,
            context.getString(R.string.phone_install_unavailable, label),
        )
    }
}
