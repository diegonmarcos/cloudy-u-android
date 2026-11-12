package com.diegonmarcos.superapp.configs

import android.app.Activity
import android.content.Context
import com.diegonmarcos.superapp.perms.AdbLine
import com.diegonmarcos.superapp.perms.AdbScript
import com.diegonmarcos.superapp.perms.PermEntry
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.PermStatusReader
import com.diegonmarcos.superapp.perms.Perms

// #1682 P3: moved verbatim from the SuperApp's configs/PermsCenter.kt so the owner-side ADB page (Perms chain) and the SuperApp's
// Perms page read the same rows. Package kept: no call site changes. Pure over libs:perms, no ADB, no shell.

/** One row's resolved state for the catalogue section (#1274). */
data class PermRowState(val entry: PermEntry, val status: PermStatus)

/** Reads every catalogue entry's state; the page calls it on open and on every resume. */
fun readPermRows(ctx: Context, activity: Activity?): List<PermRowState> {
    val vars = Perms.vars(ctx)
    return Perms.catalogue(ctx).entries.map { PermRowState(it, PermStatusReader.status(ctx, it, activity, vars)) }
}

/** The adb lines for every applicable entry (granted ones too: the block is the full grant, safe to re-run). */
fun adbLinesFor(ctx: Context, rows: List<PermRowState>): List<AdbLine> =
    AdbScript.lines(rows.filter { it.status.state != PermState.NOT_APPLICABLE }.map { it.entry }, Perms.vars(ctx))

