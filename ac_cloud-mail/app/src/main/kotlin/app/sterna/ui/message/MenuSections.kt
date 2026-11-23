package app.sterna.ui.message

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.sterna.ui.theme.MailMetrics

/**
 * #1870 the title over one section of the reader's overflow menu: one short word, uppercase, small and muted, so the
 * menu stays compact and scannable on a phone. Not an entry: it cannot be tapped, and a screen reader hears it as a
 * heading.
 */
@Composable
internal fun MenuSectionTitle(@StringRes label: Int) {
    Text(
        text = stringResource(label).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = MailMetrics.s12, end = MailMetrics.s12, top = MailMetrics.s8, bottom = MailMetrics.s2)
            .semantics { heading() },
    )
}
