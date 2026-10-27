package com.mux.musicplayer

import android.os.Build
import android.os.Environment
import java.io.File

class LyricLine(val timeMs: Long, val text: String)
class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

private val TS = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

fun parseLrc(raw: String): Lyrics? {
    val timed = ArrayList<LyricLine>()
    val plain = ArrayList<LyricLine>()
    for (line in raw.removePrefix("\uFEFF").lines()) {
        val ms = TS.findAll(line).toList()
        if (ms.isEmpty()) {
            val t = line.trim()
            val isTag = t.startsWith("[") && t.contains(":") && t.endsWith("]")
            if (t.isNotEmpty() && !isTag) plain.add(LyricLine(-1L, t))
            continue
        }
        val text = line.substring(ms.last().range.last + 1).trim()
        for (m in ms) {
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val f = m.groupValues[3]
            val frac = if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong()
            timed.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
        }
    }
    return when {
        timed.isNotEmpty() -> Lyrics(timed.sortedBy { it.timeMs }, true)
        plain.isNotEmpty() -> Lyrics(plain, false)
        else -> null
    }
}

/** Looks for "<song name>.lrc" (or .txt) next to the audio file. */
fun loadLyrics(song: Song): Lyrics? {
    val base = song.path.substringBeforeLast('.', song.path)
    for (ext in listOf("lrc", "LRC", "txt")) {
        try {
            val f = File("$base.$ext")
            if (f.isFile && f.canRead()) {
                val l = parseLrc(f.readText(Charsets.UTF_8))
                if (l != null) return l
            }
        } catch (e: Exception) { }
    }
    return null
}

/** Android 11+ only lets an app read non-media files (like .lrc) with "All files access". */
fun canReadLyrics(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
