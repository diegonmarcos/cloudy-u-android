package app.sterna.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.components.monogramColor
import app.sterna.ui.components.monogramRamps
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.settings.DetailScaffold
import app.sterna.ui.theme.MailMetrics
import java.text.DateFormat
import java.util.Date

/**
 * The Home screen: a landing page for the mail on this phone. A hero with the unread total and a
 * one-line mood, the shortcuts and quickmarks that turn it into somewhere to START from, and one
 * section of numbers per configured account (#216, #501). Read-only for the accounts themselves —
 * nothing here syncs or writes; every row still just navigates or reads.
 *
 * Every number is computed from the local mail store ([HomeViewModel]) and drawn by [HomeContent];
 * a tile with nothing to say says so ([AccountMailStats.hasNoMail]) instead of drawing zeros.
 *
 * The house style for a secondary screen is a sectioned scroll with NO CARDS (DESIGN.md → "Visual &
 * motion rules"), so every neutral tone comes from `MaterialTheme.colorScheme` and the dark and
 * Samsung-black themes carry it unchanged. The colour is the account's OWN accent — the one its
 * Monogram already wears (or, when none is chosen, the theme-derived colour the Monogram derives
 * from its name) — on its numbers and its share of the unread bar: colour that carries meaning.
 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenCompose: () -> Unit,
    onOpenSearch: (query: String) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenSnoozed: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenMailBySender: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenWebmail: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenWorkflows: () -> Unit,
    onOpenAi: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.home_title), onBack = onBack) { padding ->
        HomeContent(
            ui = ui,
            // Exhaustive over [HomeDestination]: a destination added to the declaration without a
            // route here does not compile, so a tile can never be drawn that opens nothing.
            onOpen = { destination ->
                when (destination) {
                    HomeDestination.COMPOSE -> onOpenCompose()
                    HomeDestination.SEARCH -> onOpenSearch("")
                    HomeDestination.SETTINGS -> onOpenAppSettings()
                    HomeDestination.STARRED -> onOpenStarred()
                    HomeDestination.SCHEDULED -> onOpenScheduled()
                    HomeDestination.SNOOZED -> onOpenSnoozed()
                    HomeDestination.OUTBOX -> onOpenOutbox()
                    HomeDestination.BY_SENDER -> onOpenMailBySender()
                    HomeDestination.NEWS -> onOpenRss()
                    HomeDestination.WEBMAIL -> onOpenWebmail()
                    HomeDestination.AGENTS -> onOpenAgents()
                    HomeDestination.WORKFLOWS -> onOpenWorkflows()
                    HomeDestination.AI -> onOpenAi()
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The page itself, from a [HomeUi] and one callback — no ViewModel, no scaffold — so a test can
 * render it over a mailbox it built and read what a person would read (HomeContentRenderTest).
 *
 * The ONE motion decision is read here, once, from [rememberMotionEnabled] — the gate every other
 * screen of this app shares and MotionTest proves is battery-saver aware (#501: wire to the existing
 * signal, do not invent a second one) — and threaded down. Nothing on this page animates FOREVER:
 * the count-up and the bar's grow-in are finite one-shots that begin when the page enters the
 * composition and end on their own, so an idle Home draws no frames and costs nothing.
 */
@Composable
internal fun HomeContent(ui: HomeUi, onOpen: (HomeDestination) -> Unit, modifier: Modifier = Modifier) {
    // Nothing at all until the account list has been read: an empty state that flashes on the way to
    // two accounts of statistics reads as "your accounts are gone".
    if (!ui.loaded) return
    if (ui.accounts.isEmpty()) {
        NoAccounts(modifier.fillMaxSize())
        return
    }
    val motionOn = rememberMotionEnabled()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero(ui.accounts, motionOn)
        ShortcutsRow(actionsOf(HomeDestination.Kind.SHORTCUT, onOpen))
        QuickmarksRow(actionsOf(HomeDestination.Kind.QUICKMARK, onOpen))
        // Home subpages: full-width rows that are always on screen. A destination that is ONLY a chip
        // in the sideways-scrolling quickmarks row can sit past the right edge of a phone and never be
        // seen (Webmail did, #1751); a subpage is a row the reader cannot miss.
        SubpagesList(subpagesOf(onOpen))
        ui.accounts.forEachIndexed { index, account ->
            // A rule between accounts and none before the first: the divider is what says "these
            // numbers stop belonging to the account above", which this page must never leave ambiguous.
            if (index > 0) HorizontalDivider(Modifier.padding(vertical = MailMetrics.s8))
            AccountSection(account, motionOn)
        }
        Spacer(Modifier.height(MailMetrics.s16))
    }
}

/** A fresh install: an account list that is empty is not an error, and says what to do next. */
@Composable
private fun NoAccounts(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = MailMetrics.s32),
        ) {
            Text(
                stringResource(R.string.home_no_accounts_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MailMetrics.s8))
            Text(
                stringResource(R.string.home_no_accounts_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * EVERY place the Home page can send the reader, DECLARED once (#351/#474): its kind, icon and label.
 * [HomeContent] renders both rows by walking this table, and [HomeScreen] routes each entry in an
 * exhaustive `when` — so adding, removing or reordering a tile is an edit to THIS list and nothing
 * else, and no tile can exist without a route.
 *
 * A SHORTCUT is an action ("do something": compose, search, settings). A QUICKMARK is a destination
 * the reader comes back to — the virtual mailboxes that do not live in the folder tree (starred,
 * scheduled, snoozed, outbox, by sender, news). They are the app's own fixed set, not a learned
 * "most used" list: usage is not recorded anywhere in this app, and recording it would be a new
 * data store for a convenience the drawer already gives in one tap.
 */
internal enum class HomeDestination(
    val kind: Kind,
    val icon: ImageVector,
    @StringRes val label: Int,
    /** Also drawn as a full-width row under the quickmarks (never off-screen), in addition to its chip. */
    val subpage: Boolean = false,
) {
    COMPOSE(Kind.SHORTCUT, Icons.Filled.Create, R.string.inbox_compose),
    SEARCH(Kind.SHORTCUT, Icons.Filled.Search, R.string.inbox_search),
    SETTINGS(Kind.SHORTCUT, Icons.Filled.Settings, R.string.inbox_settings),
    AGENTS(Kind.SHORTCUT, Icons.Filled.Hub, R.string.home_agents),
    AI(Kind.SHORTCUT, Icons.Filled.SmartToy, R.string.home_ai),
    WORKFLOWS(Kind.SHORTCUT, Icons.Filled.AccountTree, R.string.home_workflows),
    STARRED(Kind.QUICKMARK, Icons.Filled.Star, R.string.folder_flagged),
    SCHEDULED(Kind.QUICKMARK, Icons.Filled.Schedule, R.string.inbox_scheduled),
    SNOOZED(Kind.QUICKMARK, Icons.Filled.Snooze, R.string.inbox_snoozed),
    OUTBOX(Kind.QUICKMARK, Icons.AutoMirrored.Filled.Send, R.string.inbox_outbox),
    BY_SENDER(Kind.QUICKMARK, Icons.Filled.Person, R.string.inbox_by_sender),
    NEWS(Kind.QUICKMARK, Icons.Filled.RssFeed, R.string.rss_title),
    WEBMAIL(Kind.QUICKMARK, Icons.Filled.Language, R.string.home_webmail, subpage = true),
    ;

    enum class Kind { SHORTCUT, QUICKMARK }
}

/** The declared destinations of one [kind], as the rows draw them. */
@Composable
private fun actionsOf(kind: HomeDestination.Kind, onOpen: (HomeDestination) -> Unit): List<HomeAction> =
    HomeDestination.entries.filter { it.kind == kind }
        .map { destination -> HomeAction(destination.icon, stringResource(destination.label)) { onOpen(destination) } }

/** The destinations declared as Home subpages, as [SubpagesList] draws them. */
@Composable
private fun subpagesOf(onOpen: (HomeDestination) -> Unit): List<HomeAction> =
    HomeDestination.entries.filter { it.subpage }
        .map { destination -> HomeAction(destination.icon, stringResource(destination.label)) { onOpen(destination) } }

/** One tile as a row draws it: an icon, its label, and what a tap does. */
internal data class HomeAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The page's primary actions: compose, search, settings — the three every other screen reaches
 *  from its own top bar or FAB, now one tap from the landing page too. Each icon sits in the
 *  glass orb superapp home draws behind its top icons (#534): the fill and ring are the shared
 *  glass_orb_* tokens of libs:bottomnav, the same ones superapp's bg_liquid_glass_pill resolves. */
@Composable
internal fun ShortcutsRow(actions: List<HomeAction>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
    ) {
        // Six shortcuts: equal cells (not fixed gaps) so they fit a 360 dp phone in every language.
        actions.forEach { action ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(MailMetrics.s48)
                        .testTag(SHORTCUT_CIRCLE_TAG)
                        .clip(CircleShape)
                        .background(colorResource(GlassOrb.fill))
                        .border(MailMetrics.hair, colorResource(GlassOrb.stroke), CircleShape)
                        .clickable(role = Role.Button, onClick = action.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(action.icon, contentDescription = action.label, tint = MaterialTheme.colorScheme.primary)
                }
                Text(action.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = MailMetrics.s4))
            }
        }
    }
}

/** The shared orb tokens, by resource id: the test resolves the same ids the row draws. */
internal object GlassOrb {
    val fill = com.diegonmarcos.superapp.bottomnav.R.color.glass_orb_fill
    val stroke = com.diegonmarcos.superapp.bottomnav.R.color.glass_orb_stroke
}

internal const val SHORTCUT_CIRCLE_TAG = "home_shortcut_circle"
internal const val QUICKMARK_TAG = "home_quickmark"
internal const val HOME_HERO_NUMBER_TAG = "home_hero_unread"
internal const val HOME_MOOD_TAG = "home_mood"
internal const val HOME_ACCOUNT_EMPTY_TAG = "home_account_empty"

/** The tag of one account's bar segment, and of one of its stats: what a test (or a screen reader
 *  service) addresses a single tile by. */
internal fun barSegmentTag(accountId: String) = "home_bar:$accountId"
internal fun statTag(accountId: String, key: String) = "home_stat:$accountId:$key"

/** Quickmarks: the destinations that already exist in the drawer and the inbox's overflow menu — a
 *  second door to each, not a second implementation of any of them. Horizontally scrollable, so a
 *  small phone never wraps them. */
@Composable
private fun QuickmarksRow(actions: List<HomeAction>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
    ) {
        actions.forEach { action ->
            AssistChip(
                onClick = action.onClick,
                label = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                modifier = Modifier.testTag(QUICKMARK_TAG),
            )
        }
    }
}

/** Subpages: one full-width, tappable row per declared subpage, always visible without scrolling. */
@Composable
internal fun SubpagesList(actions: List<HomeAction>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4)) {
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(SUBPAGE_TAG)
                    .clip(RoundedCornerShape(MailMetrics.s12))
                    .border(MailMetrics.hair, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(MailMetrics.s12))
                    .clickable(role = Role.Button, onClick = action.onClick)
                    .padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(MailMetrics.s12))
                Text(action.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal const val SUBPAGE_TAG = "home_subpage"

/** How long a number takes to climb to its value, and the head start each row after the first gives
 *  the one above it — a cascade, not a wall of simultaneous tickers. */
internal const val HOME_COUNT_UP_MS = 700
private const val HOME_STAGGER_MS = 60

/**
 * The gate every one-shot on this page opens through. Under motion it starts CLOSED and opens on the
 * first frame, which is what gives the animation something to run from; without motion it starts
 * OPEN, so the very first frame already shows the real value and no animation is left to skip.
 * [animateIntAsState] and [animateFloatAsState] start AT their target, so without this gate the
 * "count-up" would never play when the page opens — only when a number later changed.
 */
@Composable
private fun rememberRevealed(motionOn: Boolean): Boolean {
    var revealed by remember { mutableStateOf(!motionOn) }
    LaunchedEffect(Unit) { revealed = true }
    return revealed
}

/** A number that counts up to [target] under motion and IS [target] on the first frame without it.
 *  Screen readers get the final value, never a frame of the climb. */
@Composable
private fun CountUp(
    target: Int,
    motionOn: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
) {
    val revealed = rememberRevealed(motionOn)
    val shown by animateIntAsState(
        targetValue = if (revealed) target else 0,
        animationSpec = if (motionOn) tween(HOME_COUNT_UP_MS, delayMillis) else snap(),
        label = "homeCountUp",
    )
    val final = stringResource(R.string.home_stat_number, target)
    Text(
        stringResource(R.string.home_stat_number, shown),
        style = style,
        color = color,
        modifier = modifier.semantics { contentDescription = final },
    )
}

/** The colour an account wears: its chosen accent, or the one its Monogram derives from its name
 *  from the active theme — so the bar, the numbers and the avatar are always the same colour. */
@Composable
private fun accentOf(stats: AccountMailStats): Color =
    accountColorOf(stats.color) ?: monogramColor(stats.label, MaterialTheme.colorScheme.monogramRamps())

/**
 * The top of the page: the unread total counted up large, a line of personality chosen by that real
 * number ([HomeMood]), and — only while something IS unread — a bar split by account in each account's
 * own colour, growing in once. With nothing to split (zero unread, or no account able to count)
 * there is no bar at all: an empty bar would be a chart of nothing.
 */
@Composable
private fun Hero(accounts: List<AccountMailStats>, motionOn: Boolean) {
    val total = heroUnread(accounts)
    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s16)) {
        if (total != null) {
            CountUp(
                target = total,
                motionOn = motionOn,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(HOME_HERO_NUMBER_TAG),
            )
            Text(
                stringResource(R.string.home_hero_unread_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(HomeMood.of(accounts)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = MailMetrics.s8).testTag(HOME_MOOD_TAG),
        )
        val counted = accounts.filter { (it.unread ?: 0) > 0 }
        if (counted.isNotEmpty()) UnreadBar(counted, motionOn)
    }
}

/** One segment per account that has unread, its width that account's share, each in its own colour,
 *  with a legend row per segment so the colours are never the only carrier of the meaning. */
@Composable
private fun UnreadBar(accounts: List<AccountMailStats>, motionOn: Boolean) {
    val revealed = rememberRevealed(motionOn)
    val grown by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (motionOn) tween(HOME_COUNT_UP_MS) else snap(),
        label = "homeBar",
    )
    Row(
        Modifier
            .padding(top = MailMetrics.s12)
            .fillMaxWidth(grown)
            .height(MailMetrics.s12)
            .clip(RoundedCornerShape(MailMetrics.s6)),
    ) {
        accounts.forEach { account ->
            Box(
                Modifier
                    .weight((account.unread ?: 0).toFloat())
                    .fillMaxSize()
                    .background(accentOf(account))
                    .testTag(barSegmentTag(account.accountId)),
            )
        }
    }
    accounts.forEach { account ->
        Row(Modifier.padding(top = MailMetrics.s6), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(MailMetrics.s8).clip(CircleShape).background(accentOf(account)))
            Spacer(Modifier.width(MailMetrics.s8))
            Text(
                stringResource(R.string.home_bar_legend, account.label, account.unread ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One account: who it is, then its numbers — or, when it holds nothing, a sentence saying so. */
@Composable
private fun AccountSection(stats: AccountMailStats, motionOn: Boolean) {
    val accent = accentOf(stats)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = MailMetrics.s16, end = MailMetrics.s16, top = MailMetrics.s16, bottom = MailMetrics.s8),
    ) {
        Monogram(seed = stats.label, label = stats.label, color = accountColorOf(stats.color))
        Spacer(Modifier.width(MailMetrics.s12))
        Column {
            Text(
                stats.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The protocol and the host together, because the owner runs one JMAP account and
                // one IMAP account side by side and several numbers below mean different things on
                // each. A host is blank on an account that has never been configured that far.
                if (stats.host.isBlank()) {
                    stats.protocol.name
                } else {
                    stringResource(R.string.home_account_server, stats.protocol.name, stats.host)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (stats.hasNoMail) {
        Text(
            stringResource(R.string.home_account_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8).testTag(HOME_ACCOUNT_EMPTY_TAG),
        )
        return
    }
    statsOf(stats).forEachIndexed { index, stat ->
        StatRow(stats.accountId, stat, accent, motionOn, delayMillis = index * HOME_STAGGER_MS)
    }
    stats.oldestMillis?.let { oldest ->
        val date = remember(oldest) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(oldest)) }
        FactRow(stringResource(R.string.home_stat_oldest), date, statTag(stats.accountId, "oldest"))
    }
}

/**
 * One number this account's section lists: a stable [key] (what a test addresses it by), the label
 * and its optional format argument, and the value itself, RAW — [StatRow] is the one place
 * formatting and animation happen. A null [value] means "this account cannot answer", never zero.
 */
internal data class Stat(val key: String, @StringRes val label: Int, val value: Int?, val labelArg: Int? = null)

/**
 * What this account's section lists, as DATA rather than as hand-placed rows: the order and the
 * wording live in one list and [StatRow] draws whatever is in it. Every [Stat.value] comes straight
 * off [stats] — nothing here is a literal, so a section can never show a number the store did not
 * return, and a test can hand it a known [AccountMailStats] and read every figure back.
 */
internal fun statsOf(stats: AccountMailStats): List<Stat> = listOf(
    // Null is "this account cannot answer", not zero — see [AccountMailStats.unread].
    Stat("unread", R.string.home_stat_unread, stats.unread),
    Stat("cached", R.string.home_stat_cached, stats.cachedMessages),
    Stat("starred", R.string.home_stat_starred, stats.starred),
    Stat("attachments", R.string.home_stat_attachments, stats.withAttachments),
    Stat("recent", R.string.home_stat_recent, stats.recent, labelArg = HOME_RECENT_DAYS),
    Stat("folders", R.string.home_stat_folders, stats.folders),
    Stat("subscribed", R.string.home_stat_subscribed, stats.subscribedFolders),
)

/** One label/value line, laid out like the settings rows: label left, value at the end. A null value
 *  draws the "unavailable" sentence, unanimated — there is no number to count towards. */
@Composable
private fun StatRow(accountId: String, stat: Stat, accent: Color, motionOn: Boolean, delayMillis: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (stat.labelArg == null) stringResource(stat.label) else stringResource(stat.label, stat.labelArg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(MailMetrics.s16))
        if (stat.value == null) {
            Text(
                stringResource(R.string.home_stat_unread_unavailable),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(statTag(accountId, stat.key)),
            )
        } else {
            CountUp(
                target = stat.value,
                motionOn = motionOn,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                modifier = Modifier.testTag(statTag(accountId, stat.key)),
                delayMillis = delayMillis,
            )
        }
    }
}

/** A label and a text value that is not a count (a date): same layout as [StatRow], nothing to animate. */
@Composable
private fun FactRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(MailMetrics.s16))
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(tag))
    }
}
