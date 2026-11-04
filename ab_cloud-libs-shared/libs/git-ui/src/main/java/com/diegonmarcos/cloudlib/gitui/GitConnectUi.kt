package com.diegonmarcos.cloudlib.gitui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GitConnectTags {
    fun repo(id: String) = "gitconnect:$id"
    fun state(id: String) = "gitconnect:$id:state"
    fun token(id: String) = "gitconnect:$id:token"
    fun saveToken(id: String) = "gitconnect:$id:save-token"
    fun pull(id: String) = "gitconnect:$id:pull"
    fun edit(id: String, path: String) = "gitconnect:$id:edit:$path"
    fun save(id: String, path: String) = "gitconnect:$id:save:$path"
    fun keepMine(id: String) = "gitconnect:$id:keep-mine"
    fun takeTheirs(id: String) = "gitconnect:$id:take-theirs"
    fun diff(id: String) = "gitconnect:$id:diff"
}

/**
 * #1445 the repo connector panel every app mounts: for each declared [specs] repo it shows the read/write state, pulls
 * on open, lets the owner edit each declared file and Save = Auto Commit message + push, and on a conflict re-pulls and
 * offers the diff with Keep mine / Take theirs. [hostToken] is the app's GitHub sign-in (the gh engine), else a token
 * typed once and kept encrypted by libs:git-sync's GitCredentialStore.
 */
@Composable
fun GitConnectPanel(specs: List<GitConnectSpec>, hostToken: (Context) -> String? = { null }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (specs.isEmpty()) Text("No repo is declared for this profile.", Modifier.testTag("gitconnect:none"))
        for (s in specs) GitConnectRepo(s, hostToken)
    }
}

@Composable
private fun GitConnectRepo(spec: GitConnectSpec, hostToken: (Context) -> String?) {
    val ctx = LocalContext.current
    val gc = remember(spec) { GitConnect(ctx, spec, hostToken) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf("Not pulled yet") }
    var conflict by remember { mutableStateOf(false) }
    var diff by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    val texts = remember { mutableStateMapOf<String, String>() }
    val hasToken = remember(tick) { gc.hasToken() }

    fun pull() {
        scope.launch {
            line = "Pulling…"
            val r = withContext(Dispatchers.IO) { gc.open() }
            if (r.ok) { conflict = false; spec.paths.forEach { texts[it] = gc.read(it).orEmpty() } }
            line = (if (r.ok) "Pulled: " else "Pull failed: ") + r.summary
            conflict = r.conflict
            tick++
        }
    }
    LaunchedEffect(spec) { pull() }

    Column(Modifier.fillMaxWidth().testTag(GitConnectTags.repo(spec.id)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${spec.label} · ${spec.owner}/${spec.repo}@${spec.ref}")
        Text("${GitConnectRules.access(hasToken)} · $line", Modifier.testTag(GitConnectTags.state(spec.id)))
        if (!hasToken) {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag(GitConnectTags.token(spec.id)),
                label = { Text("GitHub token (kept encrypted)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            TextButton({ gc.saveToken(typed); typed = ""; tick++; pull() }, Modifier.testTag(GitConnectTags.saveToken(spec.id)), enabled = typed.isNotBlank()) { Text("Connect") }
        }
        TextButton({ pull() }, Modifier.testTag(GitConnectTags.pull(spec.id))) { Text("Pull") }
        for (p in spec.paths) {
            OutlinedTextField(texts[p].orEmpty(), { texts[p] = it }, Modifier.fillMaxWidth().testTag(GitConnectTags.edit(spec.id, p)), label = { Text(p) })
            TextButton({
                scope.launch {
                    val r = withContext(Dispatchers.IO) { gc.save(p, texts[p].orEmpty()) }
                    line = (if (r.ok) "Saved: " else "Save failed: ") + r.summary
                    conflict = r.conflict
                    if (conflict) diff = withContext(Dispatchers.IO) { gc.diff(p) }
                }
            }, Modifier.testTag(GitConnectTags.save(spec.id, p)), enabled = hasToken) { Text("Save (commit + push)") }
        }
        if (conflict) {
            Text("The repo changed elsewhere. Re-pull, or choose a side.")
            if (diff.isNotBlank()) Text(diff.take(2000), Modifier.testTag(GitConnectTags.diff(spec.id)))
            Row {
                TextButton({ pull() }) { Text("Re-pull") }
                TextButton({ scope.launch { line = withContext(Dispatchers.IO) { gc.keepMine() }.summary; conflict = false } }, Modifier.testTag(GitConnectTags.keepMine(spec.id))) { Text("Keep mine") }
                TextButton({ scope.launch { line = withContext(Dispatchers.IO) { gc.takeTheirs() }.summary; conflict = false; pull() } }, Modifier.testTag(GitConnectTags.takeTheirs(spec.id))) { Text("Take theirs") }
            }
        }
    }
}
