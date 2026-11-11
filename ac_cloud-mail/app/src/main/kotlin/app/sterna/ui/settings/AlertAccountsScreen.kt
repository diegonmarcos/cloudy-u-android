package app.sterna.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.rss.StoredAlertRssAccount
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.MailMetrics

/**
 * Configs ▸ Alert (RSS) accounts (#1624): the account TYPE behind the Alerts view. The default
 * c3-notify account ships out of the box (shown, not removable); the user can add more RSS sources
 * beside it, each a name and a feed address. The feeds of every account appear in the Alerts reader.
 */
@Composable
fun AlertAccountsScreen(
    onBack: () -> Unit,
    viewModel: AlertAccountsViewModel = viewModel(),
) {
    val userAccounts by viewModel.userAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val invalidUrlMessage = stringResource(R.string.rss_invalid_url)
    val addedMessage = stringResource(R.string.rss_subscribed)

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    DetailScaffold(title = stringResource(R.string.settings_alert_accounts_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
        ) {
            // The add row: a name and a feed address, then Add. The address gate's verdict is said,
            // never dropped — a non-http(s) string gets its own sentence.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.alert_accounts_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.alert_accounts_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        when (viewModel.add(name, url)) {
                            AddAlertAccountOutcome.INVALID ->
                                Toast.makeText(context, invalidUrlMessage, Toast.LENGTH_SHORT).show()
                            AddAlertAccountOutcome.ADDED -> {
                                Toast.makeText(context, addedMessage, Toast.LENGTH_SHORT).show()
                                name = ""
                                url = ""
                            }
                        }
                    },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.alert_accounts_add))
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = MailMetrics.s12),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // The default account — the c3-notify system, built in and not removable.
            SectionLabel(stringResource(R.string.alert_accounts_default_section))
            DefaultAccountRow(
                name = viewModel.defaultAccount.name,
                channelCount = viewModel.defaultAccount.channels.size,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // The user's own accounts.
            SectionLabel(stringResource(R.string.alert_accounts_added_section))
            if (userAccounts.isEmpty()) {
                Text(
                    stringResource(R.string.alert_accounts_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
                )
            } else {
                userAccounts.forEach { account ->
                    UserAccountRow(account = account, onRemove = { viewModel.remove(account.url) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = MailMetrics.s16, end = MailMetrics.s16, top = MailMetrics.s12, bottom = MailMetrics.s4),
    )
}

@Composable
private fun DefaultAccountRow(name: String, channelCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(MailMetrics.s12))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.alert_accounts_builtin) + " · " +
                    stringResource(R.string.alert_accounts_channels_count, channelCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserAccountRow(account: StoredAlertRssAccount, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = MailMetrics.s16, end = MailMetrics.s8, top = MailMetrics.s8, bottom = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                account.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.alert_accounts_remove))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
