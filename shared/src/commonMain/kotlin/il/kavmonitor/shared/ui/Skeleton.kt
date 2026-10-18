package il.kavmonitor.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Placeholder bars for a request that has not returned yet. */
@Composable
fun LoadingSkeleton(lines: Int = 3, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines.coerceAtLeast(1)) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1 && lines > 1) 0.55f else 1f)
                    .height(if (i == 0) 18.dp else 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bar),
            )
        }
    }
}
