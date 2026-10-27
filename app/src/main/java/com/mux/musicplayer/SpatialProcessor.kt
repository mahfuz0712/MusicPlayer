package com.mux.musicplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Params(
    val width: Float = 1f,      // 1 = normal stereo, >1 wider
    val crossfeed: Float = 0f,  // 0..1 headphone crossfeed (HRTF-lite)
    val reverb: Float = 0f,     // 0..1 wet amount
    val room: Float = 0.6f,     // 0..1 reverb size
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f
)

private class Biquad {
    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
    fun shelf(low: Boolean, freq: Double, gainDb: Double, sr: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / sr
        val cw = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0)
        val sq = 2 * sqrt(a) * alpha
        val n0: Double; val n1: Double; val n2: Double
        val d0: Double; val d1: Double; val d2: Double
        if (low) {
            n0 = a * ((a + 1) - (a - 1) * cw + sq); n1 = 2 * a * ((a - 1) - (a + 1) * cw)
            n2 = a * ((a + 1) - (a - 1) * cw - sq)
            d0 = (a + 1) + (a - 1) * cw + sq; d1 = -2 * ((a - 1) + (a + 1) * cw)
            d2 = (a + 1) + (a - 1) * cw - sq
        } else {
            n0 = a * ((a + 1) + (a - 1) * cw + sq); n1 = -2 * a * ((a - 1) + (a + 1) * cw)
            n2 = a * ((a + 1) + (a - 1) * cw - sq)
            d0 = (a + 1) - (a - 1) * cw + sq; d1 = 2 * ((a - 1) - (a + 1) * cw)
            d2 = (a + 1) - (a - 1) * cw - sq
        }
        b0 = (n0 / d0).toFloat(); b1 = (n1 / d0).toFloat(); b2 = (n2 / d0).toFloat()
        a1 = (d1 / d0).toFloat(); a2 = (d2 / d0).toFloat()
    }
}

private class Comb(n: Int) {
    private val buf = FloatArray(n); private var i = 0; private var lp = 0f
    fun p(x: Float, fb: Float, damp: Float): Float {
        val y = buf[i]
        lp = y * (1 - damp) + lp * damp
        buf[i] = x + lp * fb
        if (++i == buf.size) i = 0
        return y
    }
}

private class Allpass(n: Int) {
    private val buf = FloatArray(n); private var i = 0
    fun p(x: Float): Float {
        val b = buf[i]
        buf[i] = x + b * 0.5f
        if (++i == buf.size) i = 0
        return b - x
    }
}

/** Stereo widening + crossfeed + shelving EQ + small reverb, 16-bit stereo PCM. */
@androidx.annotation.OptIn(UnstableApi::class)
class SpatialProcessor : BaseAudioProcessor() {
    @Volatile var params = Params()
    private var applied: Params? = null
    private var sr = 44100

    private var bassL = Biquad(); private var bassR = Biquad()
    private var trebL = Biquad(); private var trebR = Biquad()
    private var combL = emptyList<Comb>(); private var combR = emptyList<Comb>()
    private var apL = emptyList<Allpass>(); private var apR = emptyList<Allpass>()
    private var dL = FloatArray(1); private var dR = FloatArray(1); private var di = 0
    private var lpL = 0f; private var lpR = 0f
    private var lpCoef = 0f
    private var width = 1f; private var cf = 0f; private var wet = 0f
    private var fb = 0.8f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET // not stereo 16-bit: processor stays inactive
        }
        sr = inputAudioFormat.sampleRate
        build()
        return inputAudioFormat
    }

    override fun onFlush() { if (isActive) build() }

    private fun build() {
        val k = sr / 44100.0
        fun n(x: Int) = (x * k).toInt().coerceAtLeast(8)
        combL = listOf(1116, 1188, 1277, 1356).map { Comb(n(it)) }
        combR = listOf(1139, 1211, 1300, 1379).map { Comb(n(it)) }
        apL = listOf(556, 441).map { Allpass(n(it)) }
        apR = listOf(579, 464).map { Allpass(n(it)) }
        dL = FloatArray((0.0003 * sr).toInt().coerceAtLeast(2)); dR = FloatArray(dL.size); di = 0
        lpL = 0f; lpR = 0f
        lpCoef = exp(-2 * PI * 700 / sr).toFloat()
        bassL = Biquad(); bassR = Biquad(); trebL = Biquad(); trebR = Biquad()
        applied = null
    }

    private fun apply(p: Params) {
        width = p.width; cf = p.crossfeed; wet = p.reverb; fb = 0.7f + 0.28f * p.room
        for (b in listOf(bassL, bassR)) b.shelf(true, 120.0, p.bassDb.toDouble(), sr.toDouble())
        for (t in listOf(trebL, trebR)) t.shelf(false, 6000.0, p.trebleDb.toDouble(), sr.toDouble())
        applied = p
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val p = params
        if (p !== applied) apply(p)
        val frames = inputBuffer.remaining() / 4
        val out = replaceOutputBuffer(frames * 4)
        val norm = 1f / (1f + cf * 0.5f)
        repeat(frames) {
            var l = inputBuffer.short / 32768f
            var r = inputBuffer.short / 32768f

            // 1) Mid/Side widening
            val m = (l + r) * 0.5f
            val s = (l - r) * 0.5f * width
            l = m + s; r = m - s

            // 2) Crossfeed: delayed + low-passed opposite channel
            val dl = dL[di]; val dr = dR[di]
            dL[di] = l; dR[di] = r
            if (++di == dL.size) di = 0
            lpL = (1 - lpCoef) * dl + lpCoef * lpL
            lpR = (1 - lpCoef) * dr + lpCoef * lpR
            l = (l + cf * lpR) * norm
            r = (r + cf * lpL) * norm

            // 3) Shelving EQ
            l = trebL.process(bassL.process(l))
            r = trebR.process(bassR.process(r))

            // 4) Reverb
            if (wet > 0.001f) {
                val x = (l + r) * 0.15f
                var wl = 0f; var wr = 0f
                for (c in combL) wl += c.p(x, fb, 0.3f)
                for (c in combR) wr += c.p(x, fb, 0.3f)
                for (a in apL) wl = a.p(wl)
                for (a in apR) wr = a.p(wr)
                l += wl * wet; r += wr * wet
            }

            out.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
