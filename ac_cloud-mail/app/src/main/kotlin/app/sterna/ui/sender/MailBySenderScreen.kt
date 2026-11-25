package app.sterna.ui.sender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.sterna.ui.errors.ErrorLogDialog
import app.sterna.ui.errors.showError
import app.sterna.ui.errors.ErrorActionsWithLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.mail.SenderVolume
import app.sterna.ui.components.ContactAvatar
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.theme.MailMetrics

/**
 * "Mail by sender": for the current account, how many messages this phone holds from each
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailBySenderScreen(
    onBack: () -> Unit,
    onOpenSearch: (from: String) -> Unit,
    viewModel: MailBySenderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The row the rule is being confirmed for, or null. It lives here and not in the ViewModel
    // because there is nothing to read before asking — the delete's confirmation carries the ids
    // it materialised, this one carries a decision and an address. Same shape as the reader's
    // (MessageScreen's `confirmRule`), for the same gesture.
    var confirmRule by remember { mutableStateOf<SenderVolume?>(null) }
    val undoLabel = stringResource(R.string.inbox_undo)
    // Counted, not "Message deleted" in the singular for forty. The count is the offer's own
    // `deleted` — what the server confirmed it moved — and the plural is this screen's, not the
    // inbox's shared string: the grouped path through the inbox is not in this lot.
    val deletedLabel = undo?.let {
        pluralStringResource(R.plurals.sender_volume_deleted, it.deleted, it.deleted)
    }

    var errorLogOpen by remember { mutableStateOf(false) }
    if (errorLogOpen) ErrorLogDialog { errorLogOpen = false }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showError(m) { errorLogOpen = true }
        viewModel.clearMessage()
    }

    LaunchedEffect(undo) {
        undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel ?: return@LaunchedEffect,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sender_volume_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Which body is drawn is decided by screenBody(), a plain function a JVM test runs. All
        // this does is give each of its answers a widget.
        when (screenBody(state)) {
            SenderScreenBody.NO_ACCOUNT ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.settings_vacation_no_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            SenderScreenBody.LOADING ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    LoadingRing()
                }

            SenderScreenBody.FAILED ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(MailMetrics.s32),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.settings_vacation_load_error, state.loadError.orEmpty()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        // Filled Button, like every other error-recovery "Retry" in the app.
                        Button(onClick = { viewModel.load() }, modifier = Modifier.padding(top = MailMetrics.s16)) {
                            Text(stringResource(R.string.settings_vacation_retry))
                        }
                    }
                }

            SenderScreenBody.EMPTY ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.sender_volume_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

            SenderScreenBody.ROWS -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12)) {
                        Text(
                            stringResource(R.string.settings_vacation_account, state.accountLabel),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // One sentence: how much, that it is what this phone holds, and what is
                        // left out of it.
                        Text(
                            stringResource(
                                R.string.sender_volume_scope,
                                pluralStringResource(R.plurals.sender_volume_cached, state.total, state.total),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                // Keyed on the address AS STORED, with no transformation. Two lines are two SQL
                // groups, so their LOWER(fromEmail) differ, so their fromEmail differ — the key
                items(state.rows, key = { it.email }) { row ->
                    SenderRow(
                        row = row,
                        canDelete = state.canDelete,
                        canBlock = state.canBlock,
                        ownAddresses = state.ownAddresses,
                        blocked = viewModel.isBlocked(row.email),
                        working = state.working,
                        onSearch = { onOpenSearch(row.email) },
                        onDelete = { viewModel.askDelete(row) },
                        onBlock = { confirmRule = row },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                // The one absent gesture that can be explained, explained at the foot of the
                // list — the shape FiltersScreen's own note uses, and with no button, because
                // taking a running script over belongs to the screen that warns in red first.
                state.blockNote?.let { note ->
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(MailMetrics.s32),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(
                                    note,
                                    stringResource(R.string.inbox_settings),
                                    stringResource(R.string.settings_filters_title),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    val pending = state.pending
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = {
                // The count is the size of the list this dialog's confirm button hands to the
                // delete — read when the dialog opened, not when the screen did. It can differ
                // from the row's number; when it does, this one is the true one.
                Text(
                    pluralStringResource(
                        R.plurals.sender_volume_delete_title,
                        pending.ids.size,
                        pending.ids.size,
                        pending.sender.email,
                    ),
                )
            },
            text = { Text(stringResource(R.string.sender_volume_delete_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(stringResource(R.string.inbox_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }

    val ruleFor = confirmRule
    if (ruleFor != null) {
        // The same dialog the reader gets, word for word, because it is the same write: a rule on
        // the server, permanent, with no Undo behind it. The menu entry's label already says what
        AlertDialog(
            onDismissRequest = { confirmRule = null },
            title = { Text(stringResource(R.string.sender_volume_block_title, ruleFor.email)) },
            text = {
                // The body SCROLLS, as the reader's copy of this dialog does. Material's text
                // slot is a height-bounded box with no scrolling of its own, and a bare Text in
                Text(
                    stringResource(
                        R.string.sender_volume_block_body,
                        stringResource(R.string.inbox_settings),
                        stringResource(R.string.settings_filters_title),
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // A verb, not the menu entry's sentence, and for the reason measured on the
                // reader's copy of this dialog: with the sentence here the label wrapped and
                TextButton(onClick = { confirmRule = null; viewModel.blockSender(ruleFor) }) {
                    Text(stringResource(R.string.sender_volume_block_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRule = null }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }
}

/**
 * One sender: avatar, name over address, the two counts, and the row menu.
 */
@Composable
private fun SenderRow(
    row: SenderVolume,
    canDelete: Boolean,
    canBlock: Boolean,
    ownAddresses: List<String>,
    blocked: Boolean,
    working: Boolean,
    onSearch: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(email = row.email, name = row.name, photoUri = null)
        Column(Modifier.weight(1f).padding(horizontal = MailMetrics.s12)) {
            Text(
                row.name?.takeIf { it.isNotBlank() } ?: row.email,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.total.toString(), style = MaterialTheme.typography.titleMedium)
            if (row.unread > 0) {
                Text(
                    pluralStringResource(R.plurals.sender_volume_unread, row.unread, row.unread),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(MailMetrics.s280),
            ) {
                // Which entries exist, and which of them can be tapped, is senderMenuEntries()'s
                // decision — a plain function a JVM test runs. An entry is ABSENT only when this
                val entries =
                    senderMenuEntries(canDelete, canBlock, blocked, working, row.email, ownAddresses)
                entries.forEach { entry ->
                    DropdownMenuItem(
                        enabled = entry.enabled,
                        text = { Text(stringResource(entry.labelRes)) },
                        onClick = {
                            menuOpen = false
                            when (entry.action) {
                                SenderAction.SEARCH -> onSearch()
                                SenderAction.DELETE -> onDelete()
                                SenderAction.BLOCK -> onBlock()
                            }
                        },
                    )
                }
            }
        }
    }
}
