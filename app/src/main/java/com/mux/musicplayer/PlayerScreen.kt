package com.mux.musicplayer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val s = Audio.current
    val st by rememberPos()
    val pos = st.first
    val dur = st.second
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("< BACK", 13f, cMain, mod = Modifier.clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.weight(1f))
            T("LYRICS", 11f, if (pager.currentPage == 0) cMain else cSub, pager.currentPage == 0,
                Modifier.clickable { scope.launch { pager.animateScrollToPage(0) } }.padding(6.dp))
            T("|", 11f, cDim)
            T("VISUAL", 11f, if (pager.currentPage == 1) cMain else cSub, pager.currentPage == 1,
                Modifier.clickable { scope.launch { pager.animateScrollToPage(1) } }.padding(6.dp))
        }

        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                LyricsPage(s)
            } else {
                Box(Modifier.fillMaxSize()) {
                    VisualizerView(Audio.vizStyle, Audio.playing, drag ?: frac, Modifier.fillMaxSize())
                    var open by remember { mutableStateOf(false) }
                    Box(Modifier.align(Alignment.TopEnd)) {
                        T(
                            "[ ${VIZ_STYLES[Audio.vizStyle.coerceIn(0, VIZ_STYLES.lastIndex)]} v ]", 11f, cMain,
                            mod = Modifier.border(1.dp, cDim).background(Color(0xFF06141A))
                                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                        DropdownMenu(
                            expanded = open, onDismissRequest = { open = false },
                            modifier = Modifier.background(Color(0xFF06141A)).border(1.dp, cMain)
                        ) {
                            VIZ_STYLES.forEachIndexed { i, name ->
                                DropdownMenuItem(
                                    text = { T(name, 13f, if (i == Audio.vizStyle) cMain else cSub, i == Audio.vizStyle) },
                                    onClick = { Audio.updateVizStyle(i); open = false }
                                )
                            }
                        }
                    }
                }
            }
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
            "MUX 3D-FX: ${if (Audio.fxOn) "ONLINE" else "OFFLINE"}", 12f, if (Audio.fxOn) cMain else cSub,
            mod = Modifier.fillMaxWidth().clickable { Audio.updateFxOn(!Audio.fxOn) }.padding(top = 12.dp, bottom = 4.dp),
            align = TextAlign.Center
        )
    }
}

@Composable
fun LyricsPage(song: Song?) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    var loading by remember { mutableStateOf(true) }
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song?.id, refresh) {
        loading = true
        lyrics = if (song == null) null else withContext(Dispatchers.IO) { loadLyrics(song) }
        loading = false
    }

    val l = lyrics
    if (loading && l == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { T("// LOADING...", 12f, cSub) }
        return
    }
    if (l == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            T("NO LYRICS FOUND", 18f, cMain, true)
            T(
                "Put a .lrc file with the same name as the song in the same folder.", 11f, cSub,
                mod = Modifier.padding(top = 10.dp), lines = 4, align = TextAlign.Center
            )
            if (!canReadLyrics()) {
                T(
                    "Android needs \"All files access\" to read lyric files.", 11f, cSub,
                    mod = Modifier.padding(top = 14.dp, bottom = 8.dp), lines = 3, align = TextAlign.Center
                )
                Chip("ALLOW LYRICS ACCESS", true, Modifier.fillMaxWidth()) {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (e: Exception) { }
                }
            }
        }
        return
    }

    val st by rememberPos()
    val active = if (l.synced) l.lines.indexOfLast { it.timeMs <= st.first } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        T(if (l.synced) "// LYRICS [SYNCED]" else "// LYRICS", 11f, cSub, mod = Modifier.padding(top = 10.dp, bottom = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(l.lines) { i, line ->
                val on = i == active
                val m = if (l.synced) Modifier.fillMaxWidth().clickable { Audio.player.seekTo(line.timeMs) }.padding(vertical = 8.dp)
                else Modifier.fillMaxWidth().padding(vertical = 6.dp)
                T(
                    line.text.ifBlank { "..." }, if (on) 18f else 14f, if (on) cMain else cSub, on,
                    m, lines = 3, align = TextAlign.Center
                )
            }
        }
    }
}
