package app.sterna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sterna.ui.theme.MailMetrics
import app.sterna.R
import app.sterna.fleet.CloudSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CloudSecretTags {
    fun take(id: String) = "cloud_secret_take:$id"
    fun set(id: String) = "cloud_secret_set:$id"
    fun line(id: String) = "cloud_secret_line:$id"
}

/**
 * #1693 A secret (mailbox password, API token, gateway token) is not typed in Cloud Mail: it is entered in Cloud Account (App credentials)
 * and taken from there. [id] is the credential's id ([CloudSecrets]); [onTaken] receives the value (the caller keeps it exactly where the
 * typed one used to go); [filled] says whether the caller already holds one. [idOrNull] is null while the address it depends on is empty.
 */
@Composable
fun CloudSecretField(
    label: String,
    idOrNull: String?,
    filled: Boolean,
    onTaken: (String) -> Unit,
    modifier: Modifier = Modifier,
    missingAddress: String? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var line by remember(idOrNull) { mutableStateOf("") }
    val key = idOrNull ?: "none"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MailMetrics.s4)) {
        Text(
            label + ": " + stringResource(if (filled) R.string.cloud_secret_taken else R.string.cloud_secret_note),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CloudSecretTags.line(key)))
        Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
            Button(onClick = {
                val id = idOrNull
                if (id == null) { line = missingAddress ?: CloudSecrets.needAddress("")!!.line; return@Button }
                scope.launch {
                    when (val t = withContext(Dispatchers.IO) { CloudSecrets.read(ctx, id, label) }) {
                        is CloudSecrets.Taken.Value -> { line = ""; onTaken(t.secret) }
                        is CloudSecrets.Taken.Missing -> line = t.line
                    }
                }
            }, modifier = Modifier.testTag(CloudSecretTags.take(key))) { Text(stringResource(R.string.cloud_secret_take)) }
            OutlinedButton(onClick = { line = CloudSecrets.open(ctx).orEmpty() }, modifier = Modifier.testTag(CloudSecretTags.set(key))) { Text(stringResource(R.string.cloud_secret_set)) }
        }
    }
}
