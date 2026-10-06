package il.kavmonitor.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/** Small "estimated" badge for stops curlbus doesn't report (the watch derives them from neighbouring stops). */
@Composable
fun EstimatedBadge(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(stringResource(Res.string.badge_estimated), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}
