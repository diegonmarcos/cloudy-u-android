package com.diegonmarcos.superapp.chat

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Comms/Mattermost page — login form when no session is saved, then a
 * categories + channels list with per-channel unread badges once
 * logged in. Mirrors libs/mail's flow: the user enters their server
 * URL (pre-filled from BuildConfig.MATTERMOST_DEFAULT_SERVER) +
 * email + password, taps Login. The session token is persisted to
 * [MattermostPrefs] (EncryptedSharedPreferences) and subsequent
 * opens skip straight to the list.
 *
 * Threading: every REST call runs on a dedicated background Thread.
 * Results land in Compose state (and Toasts on the main Handler).
 * This avoids hauling in kotlinx-coroutines as a new lib dep.
 *
 * Data model:
 *   • Pulled per refresh: teams[0] → categories + channels + members
 *   • Derived per channel: unread = totalMsgCount − member.msgCount
 *   • Empty categories silently skipped (no dead headers).
 *
 * 2026-10-16 owner decision: all apps must be Compose. The page is a ComposeView under
 * CloudKitTheme (libs:ui-kit) with its palette in the chat_* colour resources; the host
 * contract (newInstance, MattermostPrefs) is unchanged.
 */
class MattermostFragment : Fragment() {

    /** What the channel list shows. */
    private sealed interface Load {
        data object Loading : Load
        data class Done(val groups: List<Pair<MattermostCategory, List<ChannelRow>>>) : Load
        data class Failed(val message: String) : Load
    }

    private lateinit var prefs: MattermostPrefs
    private val main = Handler(Looper.getMainLooper())

    private var loggedIn by mutableStateOf(false)
    private var loginStatus by mutableStateOf("")
    private var signingIn by mutableStateOf(false)
    private var load by mutableStateOf<Load>(Load.Loading)

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = MattermostPrefs(ctx)
        loggedIn = prefs.isLoggedIn()
        if (loggedIn) loadAndRender(ctx)
        return ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val palette = KitPalette(
                    surface = colorResource(R.color.chat_surface),
                    surfaceSelected = colorResource(R.color.chat_selected),
                    textPrimary = colorResource(R.color.chat_text),
                    textSecondary = colorResource(R.color.chat_text_dim),
                    accent = colorResource(R.color.chat_accent),
                    hairline = colorResource(R.color.chat_hairline),
                    tileInk = colorResource(R.color.chat_bg),
                )
                CloudKitTheme(palette) {
                    Column(Modifier.fillMaxSize().background(colorResource(R.color.chat_bg)).padding(16.dp)) {
                        if (loggedIn) ChannelList(ctx) else LoginForm(ctx)
                    }
                }
            }
        }
    }

    // ─────────────────────────── login form ───────────────────────────

    @Composable
    private fun LoginForm(ctx: Context) {
        val p = LocalKitPalette.current
        var url by remember { mutableStateOf(prefs.serverUrl) }
        Header("Mattermost — Sign in")
        Field("Server URL", url, KeyboardType.Uri) { url = it }
        // #1693 the login and password are typed once in Cloud Account (App credentials); this page only reads them from there.
        Text("The email or username and the password are entered in Cloud Account (App credentials) and read from there.",
            color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(loginStatus, color = p.textSecondary, fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !signingIn, onClick = {
                val u = url.trim()
                if (u.isBlank()) {
                    loginStatus = "Server URL required."
                    return@Button
                }
                loginStatus = "Reading Cloud Account…"
                signingIn = true
                Thread {
                    val login = runCatching { ChatCredentials.read(ctx, ChatCredentials.ID_LOGIN) }.getOrElse { ChatCredentials.Cred("", "Cloud Account read failed") }
                    val password = runCatching { ChatCredentials.read(ctx, ChatCredentials.ID_PASSWORD) }.getOrElse { ChatCredentials.Cred("", "Cloud Account read failed") }
                    val why = ChatCredentials.usable(login, password)
                    if (why != null) { main.post { loginStatus = why; signingIn = false }; return@Thread }
                    main.post { loginStatus = "Signing in…" }
                    val result = runCatching {
                        MattermostClient(serverUrl = u, token = "").login(login.value.trim(), password.value)
                    }
                    main.post {
                        result.onSuccess { r ->
                            prefs.serverUrl = u
                            prefs.token = r.token
                            prefs.userId = r.user.id
                            loggedIn = true
                            signingIn = false
                            loadAndRender(ctx)
                        }.onFailure { err ->
                            loginStatus = "Login failed: ${err.message}"
                            signingIn = false
                        }
                    }
                }.start()
            }) { Text("Sign in with Cloud Account") }
            Button(onClick = { loginStatus = ChatCredentials.open(ctx).orEmpty() }) { Text("Set it in Cloud Account") }
        }
    }

    // ─────────────────────────── list view ───────────────────────────

    @Composable
    private fun ChannelList(ctx: Context) {
        val p = LocalKitPalette.current
        Header("Mattermost")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadAndRender(ctx) }) { Text("Refresh") }
            Button(onClick = {
                prefs.clear()
                loginStatus = ""
                loggedIn = false
            }) { Text("Sign out") }
        }
        Box(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            when (val l = load) {
                Load.Loading -> Text("Loading…", color = p.textSecondary, fontSize = 13.sp)
                is Load.Failed -> Text(
                    "Load failed: ${l.message}\nTap Sign out + back in if the token expired.",
                    color = colorResource(R.color.chat_error), fontSize = 12.sp)
                is Load.Done ->
                    if (l.groups.isEmpty()) {
                        Text("No categories yet on this team.", color = p.textSecondary, fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            for ((cat, rows) in l.groups) {
                                item(key = "cat:${cat.id}") { CategoryHeader(cat, rows.sumOf { it.unreadCount }) }
                                for (row in rows) {
                                    item(key = "ch:${cat.id}:${row.channel.id}") { ChannelItem(row) }
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun loadAndRender(ctx: Context) {
        load = Load.Loading
        val client = MattermostClient(prefs.serverUrl, prefs.token)
        val userId = prefs.userId
        Thread {
            val result = runCatching {
                val teams = client.listMyTeams()
                if (teams.isEmpty()) emptyList<Pair<MattermostCategory, List<ChannelRow>>>()
                else {
                    val team = teams.first()
                    val categories = client.listCategories(userId, team.id)
                    val channels   = client.listChannels(userId, team.id).associateBy { it.id }
                    val members    = client.listChannelMembers(userId, team.id).associateBy { it.channelId }
                    categories.map { cat ->
                        val rows = cat.channelIds.mapNotNull { id ->
                            val ch = channels[id] ?: return@mapNotNull null
                            val mem = members[id]
                            val unread = (ch.totalMsgCount - (mem?.msgCount ?: 0L)).coerceAtLeast(0L)
                            ChannelRow(ch, unread, mem?.mentionCount ?: 0L)
                        }
                        cat to rows
                    }.filter { it.second.isNotEmpty() }
                }
            }
            main.post {
                result.onSuccess { groups -> load = Load.Done(groups) }
                    .onFailure { e ->
                        load = Load.Failed(e.message ?: "")
                        Toast.makeText(ctx, "Mattermost: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    // ─────────────────────────── widgets ───────────────────────────

    @Composable
    private fun Header(text: String) {
        Text(text, color = LocalKitPalette.current.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
    }

    @Composable
    private fun CategoryHeader(cat: MattermostCategory, totalUnread: Long) {
        val name = cat.displayName.ifBlank { cat.type }
        Text(if (totalUnread > 0) "$name  ·  $totalUnread" else name,
            color = colorResource(R.color.chat_category), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    }

    @Composable
    private fun ChannelItem(row: ChannelRow) {
        val p = LocalKitPalette.current
        // Type prefix — # public, 🔒 private, @ DM, 👥 group DM.
        val prefix = when (row.channel.type) {
            "O" -> "# "
            "P" -> "🔒 "
            "D" -> "@ "
            "G" -> "👥 "
            else -> "?  "
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(prefix + row.channel.displayName.ifBlank { row.channel.name },
                color = if (row.unreadCount > 0) p.textPrimary else p.textSecondary.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = if (row.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f))
            if (row.unreadCount > 0) {
                val badgeText = if (row.mentionCount > 0)
                    "${row.unreadCount}  (@${row.mentionCount})"
                else
                    row.unreadCount.toString()
                Text(badgeText,
                    color = if (row.mentionCount > 0) colorResource(R.color.chat_mention) else p.accent,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true,
            label = { Text(label, fontSize = 11.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
    }

    companion object { fun newInstance() = MattermostFragment() }
}
