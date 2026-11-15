package com.diegonmarcos.cloudagenda.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudagenda.data.ProjectConfigs
import com.diegonmarcos.cloudagenda.data.ProjectSelection

/**
 * #1757 the Home selector's state: which projects feed Inbox, Backlog, Tasks and Library. Compose state, so every page that reads
 * [selection] redraws when it changes; [update] commits it to the prefs first, so a restart opens on the same choice.
 * [configs] is the per-project calendar config (mode and lane order, assets/projects.json).
 */
class ProjectScope(initial: ProjectSelection, val configs: ProjectConfigs, private val commit: (ProjectSelection) -> Unit) {
    var selection by mutableStateOf(initial)
        private set

    fun update(s: ProjectSelection) { commit(s); selection = s }
}
