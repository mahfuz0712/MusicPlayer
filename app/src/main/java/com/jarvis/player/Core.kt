package com.jarvis.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

data class Song(
    val id: Long, val title: String, val artist: String,
    val album: String, val durMs: Long, val path: String
) {
    val uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val folder: String get() = path.substringBeforeLast('/', "")
}

fun scanSongs(ctx: Context, minSec: Int): List<Song> {
    val m = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA
    )
    val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
    val out = ArrayList<Song>()
    ctx.contentResolver.query(
        m, proj, sel, arrayOf((minSec * 1000L).toString()),
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        while (c.moveToNext()) {
            val artist = c.getString(2)
            out += Song(
                c.getLong(0), c.getString(1) ?: "Unknown",
                if (artist == null || artist == "<unknown>") "Unknown artist" else artist,
                c.getString(3) ?: "", c.getLong(4), c.getString(5) ?: ""
            )
        }
    }
    return out
}

@androidx.annotation.OptIn(UnstableApi::class)
object Audio {
    private lateinit var sp: SharedPreferences
    lateinit var player: ExoPlayer
    val proc = SpatialProcessor()
    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<Song> = emptyList()

    var songs by mutableStateOf<List<Song>>(emptyList())
    var current by mutableStateOf<Song?>(null)
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var fx by mutableStateOf(Params(width = 2f, crossfeed = 0.3f, reverb = 0.3f, room = 0.85f, bassDb = 2f))
    var fxOn by mutableStateOf(true)
    var accent by mutableIntStateOf(0)
    var minSec by mutableIntStateOf(30)
    var sleepEnd by mutableLongStateOf(0L)
    private val sleepRun = Runnable { player.pause(); sleepEnd = 0L }

    fun init(ctx: Context) {
        if (::player.isInitialized) return
        val app = ctx.applicationContext
        sp = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        minSec = sp.getInt("minSec", 30)
        fxOn = sp.getBoolean("fxOn", true)
        sp.getString("fx", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 6 }?.let { fx = Params(it[0], it[1], it[2], it[3], it[4], it[5]) }
        applyFx()
        val factory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(proc)).build()
        }
        player = ExoPlayer.Builder(app, factory)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                current = queue.getOrNull(player.currentMediaItemIndex)
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
            override fun onRepeatModeChanged(repeatMode: Int) { repeat = repeatMode }
        })
    }

    private fun Song.toItem() = MediaItem.Builder()
        .setUri(uri).setMediaId(id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build())
        .build()

    fun play(list: List<Song>, index: Int) {
        queue = list
        player.setMediaItems(list.map { it.toItem() }, index, 0L)
        player.prepare()
        player.play()
        current = list.getOrNull(index)
    }

    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun toggleShuffle() { player.shuffleModeEnabled = !player.shuffleModeEnabled }
    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun applyFx() { proc.params = if (fxOn) fx else Params() }
    fun updateFx(p: Params) {
        fx = p; applyFx()
        sp.edit().putString("fx", listOf(p.width, p.crossfeed, p.reverb, p.room, p.bassDb, p.trebleDb).joinToString(",")).apply()
    }
    fun updateFxOn(b: Boolean) { fxOn = b; applyFx(); sp.edit().putBoolean("fxOn", b).apply() }
    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateMinSec(s: Int) { minSec = s; sp.edit().putInt("minSec", s).apply() }

    fun sleep(min: Int) {
        handler.removeCallbacks(sleepRun)
        if (min > 0) {
            sleepEnd = System.currentTimeMillis() + min * 60_000L
            handler.postDelayed(sleepRun, min * 60_000L)
        } else sleepEnd = 0L
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Audio.init(this)
        session = MediaSession.Builder(this, Audio.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
