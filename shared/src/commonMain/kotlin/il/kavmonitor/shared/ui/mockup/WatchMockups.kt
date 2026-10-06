package il.kavmonitor.shared.ui.mockup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * v0.6 UI MOCKUP – preview composables only (rendered by the Roborazzi harness from real recorded data); NOT used by
 * the app's screens yet.
 */

/** Hebrew RTL + the app's colours (light / dark), on a surface. */
@Composable
fun MockupTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Color(0xFF7BD88F)) else lightColorScheme(primary = Color(0xFF1B7F3B))
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@Composable
private fun isDark() = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }

@Composable
fun toneColor(t: HeroTone): Color {
    val dark = isDark()
    return when (t) {
        HeroTone.PLENTY -> if (dark) Color(0xFF2E7D32) else Color(0xFF1B7F3B)
        HeroTone.GET_READY -> if (dark) Color(0xFFE65100) else Color(0xFFEF6C00)
        HeroTone.LEAVE_NOW -> if (dark) Color(0xFFC62828) else Color(0xFFD32F2F)
        HeroTone.NONE -> if (dark) Color(0xFF4A4A4A) else Color(0xFF6E6E6E)
    }
}

@Composable
fun freshnessColor(f: Freshness): Color = when (f) {
    Freshness.FRESH -> Color(0xFF2EAD4B)
    Freshness.AGING -> Color(0xFFF08C00)
    Freshness.SCHEDULED -> Color(0xFF9E9E9E)
}

private fun freshnessText(f: Freshness) = when (f) {
    Freshness.FRESH -> "GPS עדכני"
    Freshness.AGING -> "GPS מתיישן"
    Freshness.SCHEDULED -> "לפי לו״ז בלבד"
}

// ---------------------------------------------------------------- 1. hero

@Composable
fun HeroCard(m: HeroModel, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().testTag("hero-${m.tone}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = toneColor(m.tone), contentColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (m.label != null) Text(m.label, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                m.big,
                fontSize = when { m.tone == HeroTone.NONE -> 28.sp; m.label == null -> 52.sp; else -> 64.sp },
                lineHeight = when { m.tone == HeroTone.NONE -> 34.sp; else -> 68.sp },
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(m.sub, fontSize = 15.sp, color = Color.White.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- 2. line diagram

private val RAIL = 36.dp

private fun DrawScope.rail(color: Color, top: Boolean, bottom: Boolean, dashed: Boolean = false) {
    val x = size.width / 2
    val w = 4.dp.toPx()
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())) else null
    if (top) drawLine(color, Offset(x, 0f), Offset(x, size.height / 2), w, StrokeCap.Butt, effect)
    if (bottom) drawLine(color, Offset(x, size.height / 2), Offset(x, size.height), w, StrokeCap.Butt, effect)
}

/** A tiny bus glyph (drawn, so it renders the same everywhere). */
@Composable
fun BusGlyph(color: Color, modifier: Modifier = Modifier, hollow: Boolean = false) {
    Canvas(modifier.size(width = 22.dp, height = 18.dp)) {
        val body = Size(size.width, size.height * 0.8f)
        if (hollow) drawRoundRect(color, size = body, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
        else drawRoundRect(color, size = body, cornerRadius = CornerRadius(4.dp.toPx()))
        val win = if (hollow) color else Color.White
        drawRoundRect(win, topLeft = Offset(size.width * 0.12f, size.height * 0.14f), size = Size(size.width * 0.76f, size.height * 0.28f), cornerRadius = CornerRadius(2.dp.toPx()))
        drawCircle(Color(0xFF222222), radius = size.height * 0.13f, center = Offset(size.width * 0.25f, size.height * 0.82f))
        drawCircle(Color(0xFF222222), radius = size.height * 0.13f, center = Offset(size.width * 0.75f, size.height * 0.82f))
    }
}

@Composable
private fun BusChip(b: DiagramBus, glyph: Boolean = true) {
    val c = freshnessColor(b.freshness)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (glyph) BusGlyph(if (b.notStarted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, hollow = b.notStarted)
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Text(
            listOfNotNull(
                b.vehicle?.let { "רכב $it" },
                if (b.notStarted) "טרם יצא" else null,
                b.etaToUserMinutes?.let { (if (b.etaEstimated) "\u2066~$it\u2069" else "$it") + " דק׳ לתחנה שלך" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LineDiagram(rows: List<DiagramRow>, modifier: Modifier = Modifier) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Column(modifier.fillMaxWidth().testTag("line-diagram")) {
        rows.forEachIndexed { i, r ->
            val first = i == 0
            val last = i == rows.lastIndex
            when (r) {
                is DiagramRow.Stop -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            rail(railColor, top = !first, bottom = !last)
                            val c = Offset(size.width / 2, size.height / 2)
                            if (r.isUser) {
                                drawCircle(primary, radius = 11.dp.toPx(), center = c)
                                drawCircle(surface, radius = 5.dp.toPx(), center = c)
                            } else if (r.isOrigin || r.isDestination) {
                                drawCircle(railColor, radius = 8.dp.toPx(), center = c)
                                drawCircle(surface, radius = 4.dp.toPx(), center = c)
                            } else {
                                drawCircle(surface, radius = 6.dp.toPx(), center = c)
                                drawCircle(railColor, radius = 6.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(vertical = 7.dp, horizontal = 6.dp)) {
                        val userStyle = if (r.isUser) Modifier.clip(RoundedCornerShape(8.dp)).background(primary.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier
                        Row(userStyle, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${r.index + 1}. ${r.name}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (r.isUser || r.isOrigin || r.isDestination) FontWeight.Bold else FontWeight.Normal,
                                color = if (r.isUser) primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (r.isUser) Text("  · התחנה שלך", style = MaterialTheme.typography.labelMedium, color = primary)
                        }
                        r.busesAt.forEach { BusChip(it) }
                    }
                }
                is DiagramRow.Between -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) { rail(railColor, top = true, bottom = true) }
                        BusGlyph(primary)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 6.dp)) { r.buses.forEach { BusChip(it, glyph = false) } }
                }
                is DiagramRow.Collapsed -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight()) {
                        Canvas(Modifier.fillMaxSize()) { rail(railColor, top = true, bottom = true, dashed = true) }
                    }
                    Text("… ${r.count} תחנות", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 3. compact bus rows

private fun distanceLabel(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} מ׳" else "${((m / 100).toInt() / 10.0)} ק״מ"

@Composable
fun CompactBusRow(m: BusRowModel, expanded: Boolean, onClick: () -> Unit = {}) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("bus-row-${m.key}"), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((m.etaMinutes?.toString() ?: "–").let { if (m.etaEstimated) "\u2066~$it\u2069" else it }, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                        color = if (m.etaEstimated) muted else MaterialTheme.colorScheme.onSurface)
                    Text(if (m.etaEstimated) "דק׳ (הערכה)" else "דק׳", style = MaterialTheme.typography.labelMedium, color = muted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(m.where, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { m.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = if (m.freshness == Freshness.SCHEDULED) muted else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                }
                Box(Modifier.size(12.dp).clip(CircleShape).background(freshnessColor(m.freshness)).testTag("fresh-${m.freshness}"))
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val lines = listOfNotNull(
                    m.vehicle?.let { "רכב $it" },
                    m.scheduledStart?.let { "יציאה מתוכננת מהמוצא $it" },
                    if (m.lastUpdate != null) "עדכון אחרון ${m.lastUpdate}" + (m.ageSeconds?.let { " (לפני $it שנ׳)" } ?: "") + " · " + freshnessText(m.freshness)
                    else freshnessText(m.freshness),
                    m.distanceToUserMeters?.let { "מרחק לתחנה שלך ${distanceLabel(it)} במסלול" },
                    if (m.etaEstimated) "זמן ההגעה משוער: curlbus מציג את האוטובוס רק בתחנות שלפניך + זמן הנסיעה לפי הלו״ז" else null,
                )
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
        }
    }
}

@Composable
fun CompactBusRows(rows: List<BusRowModel>, initiallyExpanded: String? = null) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r -> CompactBusRow(r, expanded == r.key) { expanded = if (expanded == r.key) null else r.key } }
    }
}

// ---------------------------------------------------------------- 4. combined watch screen

@Composable
fun WatchScreenMockup(
    title: String,
    subtitle: String,
    hero: HeroModel,
    diagram: List<DiagramRow>,
    rows: List<BusRowModel>,
    expanded: String? = null,
    scroll: Boolean = true,
) {
    Column(
        (if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HeroCard(hero)
        Text("על הקו עכשיו", style = MaterialTheme.typography.titleMedium)
        Card(shape = RoundedCornerShape(16.dp)) { LineDiagram(diagram, Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) }
        Text("אוטובוסים לתחנה שלך", style = MaterialTheme.typography.titleMedium)
        CompactBusRows(rows, expanded)
    }
}

/** Section preview frame (title + content) for the per-idea PNGs. */
@Composable
fun MockupFrame(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** All hero states stacked (for the comparison PNG). */
@Composable
fun HeroStates(heroes: List<HeroModel>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { heroes.forEach { HeroCard(it) } }
}
