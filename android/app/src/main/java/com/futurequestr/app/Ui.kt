package com.futurequestr.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Every icon in the app is drawn here, on a 24 x 24 grid, so the style stays consistent. */
enum class Ico {
    Quests, Plan, Progress, Focus, Account, Search, Plus, Share, Flame, SoundOn, SoundOff,
    Study, Health, Comms, Creative, Rest, Level, Clock, Check, Star, ChevronDown
}

@Composable
fun FQIcon(kind: Ico, tint: Color, boxSize: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(boxSize)) {
        drawIco(kind, tint, this.size.minDimension / 24f)
    }
}

private fun DrawScope.drawIco(kind: Ico, c: Color, s: Float) {
    val sw = 1.8f * s
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pt(x: Float, y: Float) = Offset(x * s, y * s)
    fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, pt(x1, y1), pt(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
    fun Path.m(x: Float, y: Float) = moveTo(x * s, y * s)
    fun Path.l(x: Float, y: Float) = lineTo(x * s, y * s)
    fun Path.cu(a: Float, b: Float, c1: Float, d: Float, e: Float, f: Float) =
        cubicTo(a * s, b * s, c1 * s, d * s, e * s, f * s)
    fun stroke(block: Path.() -> Unit) = drawPath(Path().apply(block), c, style = st)

    when (kind) {
        Ico.Quests -> {
            drawRoundRect(c, pt(4f, 4f), Size(16f * s, 16f * s), CornerRadius(4f * s), style = st)
            stroke { m(8f, 12.5f); l(11f, 15.5f); l(16f, 9f) }
        }
        Ico.Plan, Ico.Star -> stroke {
            m(12f, 3f); l(14.2f, 9.8f); l(21f, 12f); l(14.2f, 14.2f); l(12f, 21f); l(9.8f, 14.2f); l(3f, 12f); l(9.8f, 9.8f); close()
        }
        Ico.Progress -> {
            for ((x, y) in listOf(6f to 13f, 12f to 6f, 18f to 10f)) {
                drawLine(c, pt(x, 20f), pt(x, y), strokeWidth = 3.2f * s, cap = StrokeCap.Round)
            }
        }
        Ico.Focus -> {
            drawCircle(c, 9f * s, pt(12f, 12f), style = st)
            drawCircle(c, 4.8f * s, pt(12f, 12f), style = st)
            drawCircle(c, 1.3f * s, pt(12f, 12f))
        }
        Ico.Account -> {
            drawCircle(c, 3.6f * s, pt(12f, 8.2f), style = st)
            drawArc(c, 180f, 180f, false, topLeft = pt(5f, 13.5f), size = Size(14f * s, 14f * s), style = st)
        }
        Ico.Search -> {
            drawCircle(c, 6f * s, pt(10.5f, 10.5f), style = st)
            ln(15f, 15f, 20f, 20f)
        }
        Ico.Plus -> { ln(12f, 5f, 12f, 19f); ln(5f, 12f, 19f, 12f) }
        Ico.Share -> {
            stroke { m(6f, 12f); l(6f, 20f); l(18f, 20f); l(18f, 12f) }
            ln(12f, 4f, 12f, 15f)
            stroke { m(8f, 8f); l(12f, 4f); l(16f, 8f) }
        }
        Ico.Flame -> stroke {
            m(12f, 3f); cu(12f, 3f, 18f, 8f, 18f, 14f); cu(18f, 17.5f, 15.3f, 20.5f, 12f, 20.5f)
            cu(8.7f, 20.5f, 6f, 17.5f, 6f, 14f); cu(6f, 11f, 8f, 9.5f, 9f, 8f)
            cu(9.5f, 10f, 10.5f, 11f, 12f, 11f); cu(12f, 8f, 12f, 5f, 12f, 3f); close()
        }
        Ico.SoundOn -> {
            stroke { m(4f, 9f); l(8f, 9f); l(13f, 5f); l(13f, 19f); l(8f, 15f); l(4f, 15f); close() }
            drawArc(c, -45f, 90f, false, topLeft = pt(8f, 7f), size = Size(10f * s, 10f * s), style = st)
            drawArc(c, -45f, 90f, false, topLeft = pt(5f, 4f), size = Size(16f * s, 16f * s), style = st)
        }
        Ico.SoundOff -> {
            stroke { m(4f, 9f); l(8f, 9f); l(13f, 5f); l(13f, 19f); l(8f, 15f); l(4f, 15f); close() }
            ln(16.5f, 9f, 21f, 15f); ln(21f, 9f, 16.5f, 15f)
        }
        Ico.Study -> {
            drawRoundRect(c, pt(5f, 3.5f), Size(14f * s, 17f * s), CornerRadius(2.5f * s), style = st)
            ln(9f, 3.5f, 9f, 20.5f); ln(12.4f, 8f, 16f, 8f); ln(12.4f, 11.5f, 16f, 11.5f)
        }
        Ico.Health -> stroke {
            m(12f, 20f); cu(4f, 14f, 3f, 9f, 6f, 6.5f); cu(8.5f, 4.5f, 11f, 5.5f, 12f, 8f)
            cu(13f, 5.5f, 15.5f, 4.5f, 18f, 6.5f); cu(21f, 9f, 20f, 14f, 12f, 20f); close()
        }
        Ico.Comms -> {
            drawRoundRect(c, pt(4f, 5f), Size(16f * s, 11f * s), CornerRadius(3f * s), style = st)
            stroke { m(9f, 16f); l(9f, 20f); l(13f, 16f) }
        }
        Ico.Creative -> {
            stroke { m(4f, 20f); l(5f, 15f); l(16f, 4f); l(20f, 8f); l(9f, 19f); close() }
            ln(13.5f, 6.5f, 17.5f, 10.5f)
        }
        Ico.Rest -> {
            val big = Path().apply { addOval(Rect(pt(4f, 4f), Size(16f * s, 16f * s))) }
            val cut = Path().apply { addOval(Rect(pt(9f, 2.5f), Size(14f * s, 14f * s))) }
            drawPath(Path.combine(PathOperation.Difference, big, cut), c, style = st)
        }
        Ico.Level -> {
            stroke { m(6f, 13f); l(12f, 7f); l(18f, 13f) }
            stroke { m(6f, 19f); l(12f, 13f); l(18f, 19f) }
        }
        Ico.Clock -> {
            drawCircle(c, 9f * s, pt(12f, 12f), style = st)
            stroke { m(12f, 7f); l(12f, 12f); l(15.5f, 14f) }
        }
        Ico.Check -> {
            drawCircle(c, 9f * s, pt(12f, 12f), style = st)
            stroke { m(8f, 12.3f); l(11f, 15.2f); l(16f, 9.3f) }
        }
        Ico.ChevronDown -> stroke { m(7f, 10f); l(12f, 15f); l(17f, 10f) }
        else -> { }
    }
}

/** Category icon on a softly tinted square. */
@Composable
fun CatTile(cat: String, boxSize: Dp = 40.dp) {
    val a = accentColor()
    Box(
        Modifier.size(boxSize).clip(RoundedCornerShape(10.dp)).background(a.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) { FQIcon(Game.icon(cat), a, boxSize * 0.55f) }
}

/** Ring badge: accent when earned, muted outline when locked. */
@Composable
fun BadgeGlyph(kind: Ico, on: Boolean) {
    val accent = accentColor()
    val line = if (on) accent else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (on) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.5.dp, line, CircleShape),
        contentAlignment = Alignment.Center
    ) { FQIcon(kind, line, 24.dp) }
}

/** Flat card with a thin outline. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content
    )
}

@Composable
fun FQButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(10.dp), content = content)
}

@Composable
fun FQOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(10.dp), content = content)
}
