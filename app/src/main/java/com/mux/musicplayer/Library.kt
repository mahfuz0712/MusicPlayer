package com.mux.musicplayer

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")

/**
 * Asks Android to index audio files that sit in already-known music folders
 * but are not in the media library yet (e.g. copied over USB or by a file manager).
 */
suspend fun indexNewFiles(ctx: Context, known: List<Song>): Unit = withContext(Dispatchers.IO) {
    val knownPaths = known.mapTo(HashSet()) { it.path }
    val files = known.map { it.folder }.toSet().flatMap { dir ->
        File(dir).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT && it.absolutePath !in knownPaths }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
    if (files.isEmpty()) return@withContext
    val latch = CountDownLatch(files.size)
    MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null) { _, _ -> latch.countDown() }
    latch.await(8, TimeUnit.SECONDS)
    Unit
}