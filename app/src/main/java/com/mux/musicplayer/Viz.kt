package com.mux.musicplayer

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Real-time audio analysis. The audio processor pushes samples, the UI calls update() every frame. */
object Viz {
    const val BANDS = 64
    private const val N = 4096
    private const val MASK = 8191
    private val ring = FloatArray(8192)
    @Volatile private var wpos = 0
    @Volatile var sampleRate = 44100

    val bands = FloatArray(BANDS)
    val peaks = FloatArray(BANDS)
    val wave = FloatArray(256)
    var bass = 0f

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val lo = IntArray(BANDS)
    private val hi = IntArray(BANDS)
    private var edgesSr = 0

    fun push(m: Float) {
        val w = wpos
        ring[w and MASK] = m
        wpos = w + 1
    }

    private fun buildEdges(sr: Int) {
        val binHz = sr / N.toFloat()
        val fMax = min(16000f, sr * 0.45f)
        val ratio = fMax / 40f
        for (k in 0 until BANDS) {
            val f0 = 40f * ratio.pow(k / BANDS.toFloat())
            val f1 = 40f * ratio.pow((k + 1) / BANDS.toFloat())
            val l = max(1, (f0 / binHz).toInt())
            lo[k] = l
            hi[k] = min(N / 2 - 1, max(l, (f1 / binHz).toInt()))
        }
        edgesSr = sr
    }

    fun update(playing: Boolean) {
        if (!playing) {
            for (k in 0 until BANDS) { bands[k] *= 0.9f; peaks[k] = max(0f, peaks[k] - 0.02f) }
            bass *= 0.9f
            for (i in wave.indices) wave[i] *= 0.9f
            return
        }
        val sr = sampleRate
        if (sr != edgesSr) buildEdges(sr)
        val w = wpos
        for (i in 0 until N) { re[i] = ring[(w - N + i) and MASK] * win[i]; im[i] = 0f }
        fft()
        for (k in 0 until BANDS) {
            var m = 0f
            for (b in lo[k]..hi[k]) {
                val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                if (mag > m) m = mag
            }
            val amp = m / (N / 4f)
            val db = 20f * log10(amp + 1e-6f) + k * 14f / BANDS
            val v = ((db + 78f) / 58f).coerceIn(0f, 1f)
            val cur = bands[k]
            bands[k] = cur + (v - cur) * (if (v > cur) 0.6f else 0.18f)
            peaks[k] = max(bands[k], peaks[k] - 0.012f)
        }
        var b = 0f
        for (k in 0 until 6) b += bands[k]
        b /= 6f
        bass += (b - bass) * (if (b > bass) 0.5f else 0.15f)
        for (i in wave.indices) wave[i] = ring[(w - 1024 + i * 4) and MASK]
    }

    private fun fft() {
        val n = N
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val t = re[i]; re[i] = re[j]; re[j] = t
                val u = im[i]; im[i] = im[j]; im[j] = u
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] = re[a] + vr; im[a] = im[a] + vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

val VIZ_STYLES = listOf("ARC REACTOR", "RADIAL SPECTRUM", "NEON BARS", "WAVE SCOPE", "MATRIX RAIN")

private val GLYPHS = "01#$%&*+<>=ABCDEF789".map { it.toString() }

@Composable
fun VisualizerView(style: Int, playing: Boolean, progress: Float, modifier: Modifier = Modifier) {
    var tick by remember { mutableIntStateOf(0) }
    val heads = remember { FloatArray(32) { Random.nextFloat() * 1.3f } }
    val paint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = AndroidPaint.Align.CENTER
        }
    }
    LaunchedEffect(playing) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 1f else ((now - last) / 16_666_667f).coerceIn(0f, 4f)
            last = now
            Viz.update(playing)
            for (c in heads.indices) {
                heads[c] += (0.004f + Viz.bands[c * 2] * 0.03f) * dt
                if (heads[c] > 1.25f) heads[c] = -Random.nextFloat() * 0.4f
            }
            tick++
        }
    }
    val main = cMain
    val dim = cDim
    if (style == 0) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            ArcReactor(progress, playing, Modifier.size(minOf(maxWidth, maxHeight)))
        }
    } else {
        Canvas(modifier) {
            val frame = tick
            when (style) {
                1 -> radial(main, dim)
                2 -> neonBars(main, dim)
                3 -> scope(main, dim)
                else -> rain(heads, paint, frame, main)
            }
        }
    }
}

private fun DrawScope.radial(main: Color, dim: Color) {
    val c = center
    val r = size.minDimension / 2f
    val inner = r * .36f
    val maxLen = r * .56f
    val n = 64
    drawCircle(Brush.radialGradient(listOf(main.copy(alpha = .12f + .6f * Viz.bass), Color.Transparent), center = c, radius = inner), inner, c)
    drawCircle(dim, inner * .94f, c, style = Stroke(1.5.dp.toPx()))
    for (i in 0 until n) {
        val k = (if (i < 32) i else 63 - i) * 2 + 1
        val v = Viz.bands[k]
        val a = Math.toRadians(i * 360.0 / n - 90.0)
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val len = 3.dp.toPx() + v * maxLen
        drawLine(
            main.copy(alpha = .35f + .65f * v),
            Offset(c.x + ca * inner, c.y + sa * inner), Offset(c.x + ca * (inner + len), c.y + sa * (inner + len)),
            4.dp.toPx(), StrokeCap.Round
        )
        val pr = inner + 3.dp.toPx() + Viz.peaks[k] * maxLen + 7.dp.toPx()
        drawCircle(Color.White.copy(alpha = .85f), 1.8.dp.toPx(), Offset(c.x + ca * pr, c.y + sa * pr))
    }
}

private fun DrawScope.neonBars(main: Color, dim: Color) {
    val n = 48
    val w = size.width
    val h = size.height
    val gap = 3.dp.toPx()
    val bw = (w - gap * (n - 1)) / n
    val seg = 6.dp.toPx()
    val sg = 2.dp.toPx()
    val maxSegs = ((h * .92f) / (seg + sg)).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val k = i * Viz.BANDS / n
        val lit = (Viz.bands[k] * maxSegs).toInt()
        val x = i * (bw + gap)
        for (s in 0 until lit) {
            val a = if (s == lit - 1) 1f else .45f + .35f * s / maxSegs
            drawRect(main.copy(alpha = a), Offset(x, h - (s + 1) * (seg + sg)), Size(bw, seg))
        }
        val pk = (Viz.peaks[k] * maxSegs).toInt().coerceIn(0, maxSegs - 1)
        drawRect(Color.White.copy(alpha = .9f), Offset(x, h - (pk + 1) * (seg + sg)), Size(bw, 2.dp.toPx()))
    }
    drawLine(dim, Offset(0f, h), Offset(w, h), 1.dp.toPx())
}

private fun DrawScope.scope(main: Color, dim: Color) {
    val w = size.width
    val h = size.height
    val mid = h / 2f
    for (g in 1..3) drawLine(dim.copy(alpha = .35f), Offset(0f, h * g / 4f), Offset(w, h * g / 4f), 1.dp.toPx())
    for (g in 1..7) drawLine(dim.copy(alpha = .25f), Offset(w * g / 8f, 0f), Offset(w * g / 8f, h), 1.dp.toPx())
    val n = Viz.wave.size
    val path = Path()
    for (i in 0 until n) {
        val x = w * i / (n - 1)
        val y = mid - (Viz.wave[i] * 2.2f).coerceIn(-1f, 1f) * h * .42f
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, main.copy(alpha = .16f), style = Stroke(11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, main.copy(alpha = .4f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, main, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.rain(heads: FloatArray, paint: AndroidPaint, frame: Int, main: Color) {
    val cols = heads.size
    val cw = size.width / cols
    val trail = 14
    paint.textSize = cw * 0.95f
    val canvas = drawContext.canvas.nativeCanvas
    for (c in 0 until cols) {
        val e = Viz.bands[c * 2]
        val x = c * cw + cw / 2f
        val headY = heads[c] * size.height
        for (t in 0 until trail) {
            val y = headY - t * cw * 1.15f
            if (y < 0f || y > size.height) continue
            val a = (1f - t / trail.toFloat()) * (.25f + .75f * e)
            val col = if (t == 0) Color.White.copy(alpha = .55f + e * .45f) else main.copy(alpha = a)
            paint.color = col.toArgb()
            canvas.drawText(GLYPHS[(c * 7 + t * 13 + frame / 6) % GLYPHS.size], x, y, paint)
        }
    }
}
