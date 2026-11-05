package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon VOCABULARY a build.json `icon` may name (ui.sections and tools.tasks).
 * test/test-tools-app.sh holds every declared icon to a branch here, so a misspelt name fails the
 * build instead of drawing [fallback].
 */
object IconCatalog {
    val fallback: ImageVector = Icons.Filled.Apps

    fun vector(name: String): ImageVector = when (name) {
        "chat" -> Icons.Filled.Chat
        "tasks" -> Icons.Filled.Widgets
        "settings" -> Icons.Filled.Settings
        "translate" -> Icons.Filled.Translate
        "summarize" -> Icons.Filled.ShortText
        "edit" -> Icons.Filled.Edit
        "spellcheck" -> Icons.Filled.Spellcheck
        "list" -> Icons.Filled.ViewList
        "label" -> Icons.Filled.Label
        "reply" -> Icons.Filled.Reply
        "school" -> Icons.Filled.School
        "calculate" -> Icons.Filled.Calculate
        "data" -> Icons.Filled.DataObject
        "terminal" -> Icons.Filled.Terminal
        "tag" -> Icons.Filled.Tag
        "mood" -> Icons.Filled.Mood
        "key" -> Icons.Filled.Key
        "question" -> Icons.Filled.QuestionAnswer
        "todo" -> Icons.Filled.Checklist
        else -> fallback
    }
}
