package com.diegonmarcos.superapp.system
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.apps.PhoneAppsFragment

import android.content.Context
import androidx.work.WorkManager

/**
 * Central pause/resume switch for background work in the SuperApp.
 *
 * Triggered by the active launcher theme's `background_pause` feature
 * flag (build.json::ui.launcher_themes[*].features.background_pause).
 * A theme that sets it true → on theme application MainActivity calls
 * [applyForTheme] and we tear down every battery-hungry subsystem we
 * own: Maps location tracker, ntfy / message foreground services,
 * WorkManager periodic jobs, Phone-tab cache warm-up threads. When the
 * user flips to a theme that does not set it the same call resumes
 * them by no-op (each producer re-arms on its own next onCreate /
 * onResume cycle).
 *
 * Resilient to missing modules: every call site is wrapped in a
 * runCatching block so the orchestrator never crashes the launcher
 * on a build that doesn't include a particular module (e.g. CI
 * builds without libs:maps).
 */
object BackgroundOrchestrator {

    /** Apply the power-save policy expressed by the active theme.
     *  Called from MainActivity.onResume and on every theme change. */
    fun applyForTheme(context: Context, features: LauncherThemes.Features) {
        if (features.backgroundPause) pauseAll(context) else resumeAll(context)
    }

    /** Pause everything battery-hungry. WG explicitly excluded. */
    private fun pauseAll(context: Context) {
        // #1690 no tracker to stop here: the SuperApp runs no location tracker (Cloud Nav owns it).
        cancelPeriodicWork(context)
        // Phone-tab warm-up is a one-shot thread fired from
        // MainActivity.onCreate — nothing to stop after it completes,
        // but we DON'T fire it again on a background_pause theme (the
        // launcher's PhoneAppsFragment.warmUp now reads features
        // before kicking off and bails on power-save).
    }

    private fun resumeAll(context: Context) {
        // Subsystems re-arm on their own normal lifecycle hooks; we
        // don't have to explicitly start anything here. Keeping the
        // hook so future producers (e.g. an analytics ping batcher)
        // have somewhere to land their resume logic.
    }

    /** Cancel every WorkManager periodic / one-shot job we've enqueued
     *  by tag. Producers that want their work to survive a
     *  background_pause theme can simply enqueue under a different tag. */
    private fun cancelPeriodicWork(context: Context) = runCatching {
        val wm = WorkManager.getInstance(context.applicationContext)
        TAGGED_JOBS.forEach { tag ->
            runCatching { wm.cancelAllWorkByTag(tag) }
        }
    }

    /** Tags we recognise. Keep in sync with whatever the updater /
     *  background producers enqueue under. Adding a new tag is one
     *  entry here — no other site needs to know about it. */
    private val TAGGED_JOBS = setOf(
        "superapp-updater-check",
        "superapp-ntfy-poll",
        "superapp-analytics-batch",
    )
}
