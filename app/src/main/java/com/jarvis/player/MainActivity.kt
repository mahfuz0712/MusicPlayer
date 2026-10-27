package com.jarvis.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Audio.init(this)
        startService(Intent(this, PlaybackService::class.java))
        setContent { App() }
    }
}

enum class Screen(val label: String) { SONGS("TRACKS"), FOLDERS("DIRS"), FX("3D-FX"), SETTINGS("SYS") }

private fun perms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasPerm(c: Context) =
    ContextCompat.checkSelfPermission(c, perms()[0]) == PackageManager.PERMISSION_GRANTED

fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

class Knob(val label: String, val min: Float, val max: Float, val get: (Params) -> Float, val set: (Params, Float) -> Params)

val KNOBS = listOf(
    Knob("WIDTH", 0f, 3f, { it.width }, { p, v -> p.copy(width = v) }),
    Knob("CROSSFEED", 0f, 1f, { it.crossfeed }, { p, v -> p.copy(crossfeed = v) }),
    Knob("REVERB", 0f, 1f, { it.reverb }, { p, v -> p.copy(reverb = v) }),
    Knob("ROOM SIZE", 0f, 1f, { it.room }, { p, v -> p.copy(room = v) }),
    Knob("BASS dB", -10f, 10f, { it.bassDb }, { p, v -> p.copy(bassDb = v) }),
    Knob("TREBLE dB", -10f, 10f, { it.trebleDb }, { p, v -> p.copy(trebleDb = v) })
)

val PRESETS = linkedMapOf(
    "Off" to Params(),
    "Near" to Params(width = 1.3f, crossfeed = 0.15f, reverb = 0.05f, room = 0.4f),
    "Front" to Params(width = 1.1f, crossfeed = 0.45f, reverb = 0.12f, room = 0.5f, trebleDb = 1f),
    "Wide" to Params(width = 1.8f, crossfeed = 0.2f, reverb = 0.18f, room = 0.7f),
    "Surround" to Params(width = 2.0f, crossfeed = 0.3f, reverb = 0.3f, room = 0.85f, bassDb = 2f)
)

@Composable
fun sliderColors() = SliderDefaults.colors(
    thumbColor = cMain, activeTrackColor = cMain, inactiveTrackColor = cDim
)

@Composable
fun rememberPos(): State<Pair<Long, Long>> {
    val s = remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val p = Audio.player
            s.value = p.currentPosition to p.duration.coerceAtLeast(0L)
            delay(250)
        }
    }
    return s
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.SONGS) }
    var showPlayer by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerm(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms()) }
    LaunchedEffect(granted, Audio.minSec, rescan) {
        if (granted) {
            scanning = true
            Audio.songs = withContext(Dispatchers.IO) { scanSongs(ctx, Audio.minSec) }
            scanning = false
        }
    }
    BackHandler(showPlayer) { showPlayer = false }

    MaterialTheme(colorScheme = darkColorScheme()) {
        HudBackground {
            if (showPlayer) {
                PlayerScreen { showPlayer = false }
            } else {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T("MUSIC PLAYER", 20f, cMain, true)
                            T("// J.A.R.V.I.S AUDIO CORE", 11f, cSub)
                        }
                        T(if (Audio.playing) "[ PLAYING ]" else "[ STANDBY ]", 11f, if (Audio.playing) cMain else cSub)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val needsPerm = screen == Screen.SONGS || screen == Screen.FOLDERS
                        when {
                            !granted && needsPerm -> Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                Arrangement.Center, Alignment.CenterHorizontally
                            ) {
                                T("ACCESS DENIED", 18f, cMain, true)
                                T("Audio permission is required to index local tracks.", 12f, cSub, mod = Modifier.padding(vertical = 12.dp), lines = 3, align = TextAlign.Center)
                                Chip("GRANT ACCESS", true, Modifier.fillMaxWidth()) { launcher.launch(perms()) }
                                Spacer(Modifier.height(8.dp))
                                Chip("OPEN APP SETTINGS", false, Modifier.fillMaxWidth()) {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                                }
                            }
                            screen == Screen.SONGS -> SongsScreen(folder, scanning) { folder = null }
                            screen == Screen.FOLDERS -> FoldersScreen { folder = it; screen = Screen.SONGS }
                            screen == Screen.FX -> FxScreen()
                            else -> SettingsScreen(scanning) { rescan++ }
                        }
                    }
                    MiniPlayer { showPlayer = true }
                    Row(Modifier.fillMaxWidth().background(Color(0xFF050D12))) {
                        for (s in Screen.entries) {
                            Column(
                                Modifier.weight(1f).clickable { screen = s }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                T(s.label, 12f, if (screen == s) cMain else cSub, screen == s)
                                Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (screen == s) cMain else Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongRow(i: Int, s: Song, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (active) cDim.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T("%03d".format(i + 1), 11f, cSub, mod = Modifier.width(38.dp))
        Column(Modifier.weight(1f)) {
            T(s.title, 15f, if (active) cMain else Color(0xFFD8F3F7), active)
            T("${s.artist} · ${s.album}", 11f, cSub)
        }
        T(fmt(s.durMs), 12f, cSub)
    }
}

@Composable
fun SongsScreen(folder: String?, scanning: Boolean, onClearFolder: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(Audio.songs, folder, q) {
        Audio.songs.filter {
            (folder == null || it.folder == folder) &&
                (q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp).border(1.dp, cDim).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("> ", 15f, cMain, true)
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) T("SEARCH_TRACKS...", 14f, cSub)
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            if (q.isNotEmpty()) T("[X]", 13f, cMain, mod = Modifier.clickable { q = "" })
        }
        if (folder != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("DIR: ${folder.substringAfterLast('/')}", 12f, cMain, mod = Modifier.weight(1f))
                T("[CLEAR]", 12f, cMain, mod = Modifier.clickable(onClick = onClearFolder).padding(6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (scanning) "// SCANNING..." else "// ${shown.size} TRACKS INDEXED", 11f, cSub, mod = Modifier.weight(1f))
            if (shown.isNotEmpty()) {
                T("[PLAY ALL]", 12f, cMain, mod = Modifier.clickable { Audio.player.shuffleModeEnabled = false; Audio.play(shown, 0) }.padding(6.dp))
                T("[SHUFFLE]", 12f, cMain, mod = Modifier.clickable {
                    Audio.player.shuffleModeEnabled = true
                    Audio.play(shown, Random.nextInt(shown.size))
                }.padding(6.dp))
            }
        }
        if (shown.isEmpty() && !scanning) {
            T("NO TRACKS FOUND", 13f, cSub, mod = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                SongRow(i, s, Audio.current?.id == s.id) { Audio.play(shown, i) }
            }
        }
    }
}

@Composable
fun FoldersScreen(onPick: (String) -> Unit) {
    val dirs = remember(Audio.songs) {
        Audio.songs.groupBy { it.folder }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        T("// ${dirs.size} DIRECTORIES", 11f, cSub, mod = Modifier.padding(16.dp, 12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(dirs, key = { _, d -> d.first }) { _, d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(d.first) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        T(d.first.substringAfterLast('/').ifEmpty { "/" }, 15f, Color(0xFFD8F3F7), true)
                        T(d.first, 10f, cSub)
                    }
                    T("${d.second}", 13f, cMain)
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val s = Audio.current ?: return
    val st by rememberPos()
    val frac = if (st.second > 0) (st.first.toFloat() / st.second).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Color(0xFF06141A)).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(cDim)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(cMain))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(s.title, 14f, cMain, true)
                T(s.artist, 11f, cSub)
            }
            Glyph(G.PREV, 36.dp, cMain) { Audio.player.seekToPrevious() }
            Glyph(if (Audio.playing) G.PAUSE else G.PLAY, 40.dp, cMain) { Audio.toggle() }
            Glyph(G.NEXT, 36.dp, cMain) { Audio.player.seekToNext() }
        }
    }
}

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val s = Audio.current
    val st by rememberPos()
    val pos = st.first
    val dur = st.second
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("< BACK", 13f, cMain, mod = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            T("// NOW PLAYING", 12f, cSub)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ArcReactor(drag ?: frac, Audio.playing, Modifier.size(minOf(maxWidth, maxHeight)))
        }
        T(s?.title ?: "NO TRACK LOADED", 20f, Color.White, true, Modifier.fillMaxWidth(), 2, TextAlign.Center)
        T(s?.artist ?: "", 13f, cSub, mod = Modifier.fillMaxWidth(), align = TextAlign.Center)
        Slider(
            value = drag ?: frac,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { Audio.player.seekTo((it * dur).toLong()) }; drag = null },
            colors = sliderColors()
        )
        Row(Modifier.fillMaxWidth()) {
            T(fmt(if (drag != null) (drag!! * dur).toLong() else pos), 12f, cSub)
            Spacer(Modifier.weight(1f))
            T(fmt(dur), 12f, cSub)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            T(if (Audio.shuffle) "SHUF:ON" else "SHUF", 12f, if (Audio.shuffle) cMain else cSub, mod = Modifier.clickable { Audio.toggleShuffle() }.padding(8.dp))
            Glyph(G.PREV, 44.dp, cMain) { Audio.player.seekToPrevious() }
            Box(Modifier.size(76.dp).border(2.dp, cMain, CircleShape), Alignment.Center) {
                Glyph(if (Audio.playing) G.PAUSE else G.PLAY, 44.dp, cMain) { Audio.toggle() }
            }
            Glyph(G.NEXT, 44.dp, cMain) { Audio.player.seekToNext() }
            val rep = when (Audio.repeat) { Player.REPEAT_MODE_ONE -> "RPT:1"; Player.REPEAT_MODE_ALL -> "RPT:ALL"; else -> "RPT" }
            T(rep, 12f, if (Audio.repeat != Player.REPEAT_MODE_OFF) cMain else cSub, mod = Modifier.clickable { Audio.cycleRepeat() }.padding(8.dp))
        }
        T(
            "3D-FX: ${if (Audio.fxOn) "ONLINE" else "OFFLINE"}", 12f, if (Audio.fxOn) cMain else cSub,
            mod = Modifier.fillMaxWidth().clickable { Audio.updateFxOn(!Audio.fxOn) }.padding(top = 12.dp, bottom = 4.dp),
            align = TextAlign.Center
        )
    }
}

@Composable
fun FxScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("3D AUDIO CORE", 16f, cMain, true, Modifier.weight(1f))
            Chip(if (Audio.fxOn) "ONLINE" else "OFFLINE", Audio.fxOn, Modifier.width(110.dp)) { Audio.updateFxOn(!Audio.fxOn) }
        }
        T("// PRESETS", 12f, cSub, mod = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((n, p) in PRESETS) Chip(n.uppercase(), Audio.fx == p, Modifier.weight(1f)) { Audio.updateFx(p) }
        }
        T("// MANUAL TUNING", 12f, cSub, mod = Modifier.padding(top = 20.dp))
        for (k in KNOBS) {
            val v = k.get(Audio.fx)
            T("${k.label}: ${"%.2f".format(v)}", 12f, cMain, mod = Modifier.padding(top = 10.dp))
            Slider(
                value = v, onValueChange = { Audio.updateFx(k.set(Audio.fx, it)) },
                valueRange = k.min..k.max, colors = sliderColors()
            )
        }
        T("Effects apply to playback inside this app.", 11f, cSub, mod = Modifier.padding(top = 16.dp), lines = 2)
    }
}

@Composable
fun SettingsScreen(scanning: Boolean, onRescan: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = System.currentTimeMillis() } }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 22.dp, bottom = 8.dp))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T("SYSTEM CONFIG", 16f, cMain, true)

        section("INTERFACE COLOR")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ACCENTS.forEachIndexed { i, a -> Chip(a.name, Audio.accent == i, Modifier.weight(1f)) { Audio.updateAccent(i) } }
        }

        section("IGNORE TRACKS SHORTER THAN")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((l, v) in listOf("ALL" to 0, "15s" to 15, "30s" to 30, "60s" to 60))
                Chip(l, Audio.minSec == v, Modifier.weight(1f)) { Audio.updateMinSec(v) }
        }

        section("SLEEP TIMER")
        val left = ((Audio.sleepEnd - now) / 60000).coerceAtLeast(0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((l, v) in listOf("OFF" to 0, "15m" to 15, "30m" to 30, "60m" to 60, "90m" to 90))
                Chip(l, (v == 0 && Audio.sleepEnd == 0L), Modifier.weight(1f)) { Audio.sleep(v) }
        }
        if (Audio.sleepEnd > 0) T("ARMED: pause in ~${left + 1} min", 12f, cMain, mod = Modifier.padding(top = 8.dp))

        section("LIBRARY")
        Chip(if (scanning) "SCANNING..." else "RESCAN LIBRARY", false, Modifier.fillMaxWidth()) { onRescan() }
        T("${Audio.songs.size} tracks indexed", 12f, cSub, mod = Modifier.padding(top = 8.dp))

        section("ABOUT")
        T("MUSIC PLAYER v1.0", 12f, cMain)
        T("MODE: LOCAL ONLY // ONLINE: DISABLED", 11f, cSub, mod = Modifier.padding(top = 4.dp))
    }
}
