package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.error_prefix
import il.kavmonitor.shared.resources.retry
import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.FetchError
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.badge_estimated
import il.kavmonitor.shared.resources.err_network
import il.kavmonitor.shared.resources.err_other
import il.kavmonitor.shared.resources.err_server
import il.kavmonitor.shared.resources.err_server_stop
import il.kavmonitor.shared.resources.err_source
import il.kavmonitor.shared.resources.err_timeout
import org.jetbrains.compose.resources.stringResource

/** Short Hebrew text for an error. Never the raw exception (that only goes to the log). */
@Composable
fun errorText(err: FetchError): String = when (err.kind) {
    ErrorKind.SERVER -> err.stopCode?.let { stringResource(Res.string.err_server_stop, it) } ?: stringResource(Res.string.err_server)
    ErrorKind.NETWORK -> stringResource(Res.string.err_network)
    ErrorKind.TIMEOUT -> stringResource(Res.string.err_timeout)
    ErrorKind.SOURCE -> stringResource(Res.string.err_source)
    ErrorKind.OTHER -> stringResource(Res.string.err_other)
}

/** For places that carry the [ErrorKind] name as a string (Loadable.Failed, route view state). */
@Composable
fun errorText(kindName: String): String =
    errorText(FetchError(ErrorKind.entries.firstOrNull { it.name == kindName } ?: ErrorKind.OTHER))

/** Full-width error: container color carries the state, the action sits inside it. */
@Composable
internal fun ErrorNotice(message: String, onRetry: (() -> Unit)? = null) {
    val onContainer = MaterialTheme.colorScheme.onErrorContainer
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.error_prefix, errorText(message)), style = MaterialTheme.typography.bodyMedium, color = onContainer)
            if (onRetry != null) {
                TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = onContainer)) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }
    }
}

/** Small "estimated" badge for stops curlbus doesn't report (the watch derives them from neighbouring stops). */
@Composable
fun EstimatedBadge(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        Text(stringResource(Res.string.badge_estimated), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
