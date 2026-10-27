package com.mux.musicplayer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.cos
import kotlin.math.sin

val Bg = Color(0xFF03070B)
val Mono = FontFamily.Monospace

class AccentTheme(val name: String, val main: Color, val dim: Color)

val ACCENTS = listOf(
    AccentTheme("CYAN", Color(0xFF00E5FF), Color(0xFF0A5C68)),
    AccentTheme("GOLD", Color(0xFFFFB300), Color(0xFF6B4A00)),
    AccentTheme("GREEN", Color(0xFF00FF66), Color(0xFF05703A)),
    AccentTheme("RED", Color(0xFFFF3D3D), Color(0xFF6B1414))
)
private val theme: AccentTheme get() = ACCENTS[Audio.accent.coerceIn(0, ACCENTS.lastIndex)]
val cMain: Color get() = theme.main
val cDim: Color get() = theme.dim
val cSub: Color get() = theme.main.copy(alpha = 0.6f)

@Composable
fun T(
    text: String, size: Float = 14f, color: Color = cMain, bold: Boolean = false,
    mod: Modifier = Modifier, lines: Int = 1, align: TextAlign = TextAlign.Start
) = Text(
    text, modifier = mod, color = color, fontFamily = Mono, fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align
)

@Composable
fun HudBackground(content: @Composable BoxScope.() -> Unit) {
    val line = cDim.copy(alpha = 0.22f)
    Box(
        Modifier.fillMaxSize().background(Bg).drawBehind {
            val step = 36.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step }
        },
        content = content
    )
}

@Composable
fun Chip(label: String, on: Boolean, mod: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        mod.border(1.dp, if (on) cMain else cDim)
            .background(if (on) cDim.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        Alignment.Center
    ) { T(label, 11f, if (on) cMain else cSub) }
}

enum class G { PLAY, PAUSE, NEXT, PREV }

@Composable
fun Glyph(g: G, sz: Dp, color: Color, onClick: () -> Unit) {
    Canvas(Modifier.size(sz).clickable(onClick = onClick)) {
        val w = this.size.width
        val h = this.size.height
        fun tri(a: Float, b: Float) = drawPath(Path().apply {
            moveTo(w * a, h * .25f); lineTo(w * b, h * .5f); lineTo(w * a, h * .75f); close()
        }, color)
        when (g) {
            G.PLAY -> drawPath(Path().apply {
                moveTo(w * .28f, h * .2f); lineTo(w * .8f, h * .5f); lineTo(w * .28f, h * .8f); close()
            }, color)
            G.PAUSE -> {
                drawRect(color, Offset(w * .27f, h * .2f), Size(w * .17f, h * .6f))
                drawRect(color, Offset(w * .56f, h * .2f), Size(w * .17f, h * .6f))
            }
            G.NEXT -> {
                tri(.2f, .65f)
                drawRect(color, Offset(w * .7f, h * .25f), Size(w * .1f, h * .5f))
            }
            G.PREV -> {
                drawPath(Path().apply {
                    moveTo(w * .8f, h * .25f); lineTo(w * .35f, h * .5f); lineTo(w * .8f, h * .75f); close()
                }, color)
                drawRect(color, Offset(w * .2f, h * .25f), Size(w * .1f, h * .5f))
            }
        }
    }
}

@Composable
fun ArcReactor(progress: Float, playing: Boolean, modifier: Modifier = Modifier) {
    val main = cMain
    val dim = cDim
    val tr = rememberInfiniteTransition(label = "reactor")
    val rot by tr.animateFloat(
        0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "rot"
    )
    val pulse by tr.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(if (playing) 700 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val sw = 4.dp.toPx()
        fun pt(rad: Float, deg: Double) = Offset(
            c.x + (cos(Math.toRadians(deg)) * rad).toFloat(),
            c.y + (sin(Math.toRadians(deg)) * rad).toFloat()
        )
        drawCircle(dim, r * .98f, c, style = Stroke(1.5.dp.toPx()))
        rotate(rot, c) {
            for (i in 0 until 72) {
                val big = i % 6 == 0
                val l = if (big) .09f else .05f
                drawLine(
                    main.copy(alpha = if (big) .9f else .4f),
                    pt(r * .97f, i * 5.0), pt(r * (.97f - l), i * 5.0), 1.5.dp.toPx()
                )
            }
        }
        rotate(-rot * 1.5f, c) {
            drawCircle(
                main.copy(alpha = .55f), r * .86f, c,
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 18f)))
            )
        }
        val pr = r * .74f
        drawCircle(dim.copy(alpha = .6f), pr, c, style = Stroke(sw))
        drawArc(
            main, -90f, 360f * progress, false, Offset(c.x - pr, c.y - pr), Size(pr * 2, pr * 2),
            style = Stroke(sw * 1.4f, cap = StrokeCap.Round)
        )
        val sr = r * .58f
        rotate(rot * 2f, c) {
            for (i in 0 until 3) drawArc(
                main.copy(alpha = .85f), i * 120f, 70f, false, Offset(c.x - sr, c.y - sr),
                Size(sr * 2, sr * 2), style = Stroke(sw * 1.5f)
            )
        }
        val ir = r * .42f
        rotate(-rot * 3f, c) {
            for (i in 0 until 6) drawArc(
                main.copy(alpha = .6f), i * 60f, 30f, false, Offset(c.x - ir, c.y - ir),
                Size(ir * 2, ir * 2), style = Stroke(sw)
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(main.copy(alpha = .9f * pulse), Color.Transparent), center = c, radius = r * .34f),
            r * .34f, c
        )
        drawCircle(main, r * .09f * (.85f + .3f * pulse), c)
    }
}
