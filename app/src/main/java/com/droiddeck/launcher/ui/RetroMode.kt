package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Retro's page in the rail, as Steam's: the library's covers drifting by, Play Retro Games, and a Settings sheet under it. */
@Composable
internal fun RetroStart(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val narrow = LocalNarrowPane.current
    val colors = MaterialTheme.colorScheme
    var words by remember { mutableStateOf<Rect?>(null) }
    // the games played last first, then the rest in a fixed shuffle, so the wall mixes systems
    val covers = remember(s.retro, s.retroStats) {
        val all = s.retro.flatMap { it.games }.filter { it.art != null }
        val played = all.filter { s.retroStats[it.hostPath.path] != null }.sortedByDescending { s.retroStats[it.hostPath.path]?.last ?: 0L }
        (played + all.shuffled(java.util.Random(7))).distinctBy { it.hostPath.path }.take(48)
    }
    val tabs = RETRO_SECTIONS.map { it to stringResource(sectionLabel(it)) }
    LaunchedEffect(Unit) { a.onRetroOpen() }
    LiftHero(
        key = "retro", tabs = tabs,
        onOpen = {}, onPrimary = { RetroNav.open = true },
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = if (narrow) 20.dp else 44.dp,
        background = {
            if (covers.isEmpty()) EmptyLibrary(avoid = words) { Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.06f; scaleY = 1.06f }) { WallFade() } }
            else TiltedWall(covers.size, 40_000, capW = 132.dp, capH = 132.dp) { i -> WallCapsule(artBrush(hueOf(covers[i].name)), covers[i].art) }
        },
        scrim = { if (covers.isNotEmpty()) WallFade() },
        words = {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onPlaced { words = it.boundsInParent() },
            ) {
                Wordmark(if (narrow) 30.dp else 40.dp)
                Text(stringResource(R.string.retro_title), fontSize = if (narrow) 30.sp else 40.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = LocalPalette.current.signal)
                Chip(stringResource(R.string.retro_games_count, s.retro.sumOf { it.games.size }), ok = false)
            }
        },
        button = { click ->
            PrimaryButton(stringResource(R.string.retro_play_games), main = true, large = true, icon = Icons.Filled.PlayArrow, onClick = click)
        },
        rows = { tab ->
            RetroSettingsSection(s, a, tab)
            if (tab == RETRO_SECTIONS.last()) Text(stringResource(R.string.retro_credit), fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        },
    )
}

private val RETRO_SECTIONS = listOf("emulators", "paths", "wallpapers")

internal fun sectionLabel(section: String) = when (section) {
    "emulators" -> R.string.retro_emulators
    "paths" -> R.string.retro_paths_title
    else -> R.string.retro_wallpapers
}

/**
 * Retro mode: the whole screen, no rail. Library is a card per system as Daijishō has them (L1/R1, or D-pad down to the
 * systems strip and then left/right, flips them); a system's games and a game's page open over it and B steps back out,
 * past Settings, to the rail's Retro page.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun RetroMode(s: FrontEndState, a: FrontEndActions, onExit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ff = LocalFrontFocus.current
    val scope = rememberCoroutineScope()
    val count = s.retro.size
    var tab by rememberSaveable { mutableStateOf(0) } // 0 Library, 1 Settings
    var current by rememberSaveable { mutableStateOf(s.retro.indexOfFirst { it.platform.id == RetroNav.lastPlatform }.coerceAtLeast(0)) }
    val at = if (count == 0) 0 else current.coerceIn(0, count - 1)
    var dir by remember { mutableStateOf(1) }
    var gallery by rememberSaveable { mutableStateOf<String?>(null) } // "rsys:<platform>"
    var detail by rememberSaveable { mutableStateOf<String?>(null) } // "rgame:<platform>:<index>"
    var refocus by remember { mutableStateOf(0) }
    var stripFocused by remember { mutableStateOf(false) }
    val libraryButtons = remember { HashMap<Int, FocusRequester>() }
    val strip = remember { FocusRequester() }
    val tabFocus = remember { List(2) { FocusRequester() } }
    val cardButton = { libraryButtons.getOrPut(at) { FocusRequester() } }
    fun focusLater(target: () -> FocusRequester) { scope.launch { delay(Motion.ms(40).toLong()); focusSoon(target) } }
    val step: (Int) -> Unit = { d ->
        if (count > 1) {
            dir = d
            current = (at + d).mod(count)
            RetroNav.lastPlatform = s.retro[current].platform.id
            if (!stripFocused) focusLater { libraryButtons.getOrPut(current) { FocusRequester() } }
        }
    }
    val onSelect: (String) -> Unit = { key ->
        when {
            key.startsWith("rgame:") -> detail = key
            key.startsWith("rsys:") -> {
                gallery = key
                detail = null
                s.retro.indexOfFirst { it.platform.id == key.removePrefix("rsys:") }.takeIf { it >= 0 }?.let { current = it }
            }
        }
    }
    val openLibrary = {
        if (count > 0) {
            RetroNav.lastPlatform = s.retro[at].platform.id
            RetroNav.focusFirst = true
            onSelect("rsys:" + s.retro[at].platform.id)
        }
    }
    val showTab: (Int) -> Unit = { t ->
        tab = t
        if (t == 0) focusLater(cardButton) else focusLater { tabFocus[1] }
    }
    ShoulderKeys(active = gallery == null && tab == 0) { d -> step(d) }
    LaunchedEffect(Unit) { focusSoon(cardButton) }
    LaunchedEffect(detail) { if (detail != null && ff != null) { delay(Motion.ms(120).toLong()); focusSoon { ff.primary } } }
    BackHandler {
        when {
            detail != null -> { detail = null; refocus++ }
            gallery != null -> { gallery = null; focusLater(cardButton) }
            tab == 1 -> showTab(0)
            else -> onExit()
        }
    }
    Column(
        Modifier.fillMaxSize().background(colors.background).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.ButtonStart && gallery == null) { showTab(1 - tab); true } else false
        },
    ) {
        TopBar(tab, tabFocus, enabled = gallery == null) { t -> tab = t }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().focusProperties { enter = { if (gallery != null) FocusRequester.Cancel else FocusRequester.Default } }.focusGroup()) {
                if (tab == 1) {
                    RetroSettingsPage(s, a, Modifier.weight(1f).fillMaxWidth())
                } else if (count == 0) {
                    Box(Modifier.weight(1f).padding(22.dp)) { Note(stringResource(R.string.retro_empty)) }
                    KeysBar(stringResource(R.string.retro_mode_keys))
                } else {
                    AnimatedContent(
                        targetState = at,
                        transitionSpec = {
                            (slideInHorizontally(Motion.tw(240)) { dir * it / 3 } + fadeIn(Motion.tw(200)))
                                .togetherWith(slideOutHorizontally(Motion.tw(240)) { -dir * it / 3 } + fadeOut(Motion.tw(140)))
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp),
                        label = "retroCard",
                    ) { i ->
                        val sys = s.retro.getOrNull(i)
                        if (sys != null) PlatformCard(sys, i, count, a, onSelect, Modifier.fillMaxSize(), libraryButtons.getOrPut(i) { FocusRequester() }) {}
                    }
                    SystemsStrip(s, at, strip, stripFocused, { stripFocused = it }, step, onUp = { focusLater(cardButton) }, onOpen = openLibrary) { i ->
                        dir = if (i >= at) 1 else -1
                        current = i
                        RetroNav.lastPlatform = s.retro[i].platform.id
                    }
                    KeysBar(stringResource(R.string.retro_mode_keys))
                }
            }
            Box(Modifier.fillMaxSize().focusProperties { enter = { if (detail != null) FocusRequester.Cancel else FocusRequester.Default } }.focusGroup()) {
                Layer(gallery) { key -> RetroGallery(s, a, key, onSelect, Modifier.fillMaxSize(), active = detail == null, refocus = refocus) }
            }
            Layer(detail) { key -> Content(s, key, a, Modifier.fillMaxSize(), onSelect, {}, {}, {}) }
        }
    }
}

/** A page over retro mode's cards (a system's games, a game's page): it fades and grows in, and keeps its last key while it fades out. */
@Composable
private fun Layer(key: String?, content: @Composable (String) -> Unit) {
    val last = remember { arrayOf(key) }
    if (key != null) last[0] = key
    AnimatedVisibility(
        visible = key != null,
        enter = fadeIn(Motion.tw(160)) + scaleIn(Motion.tw(220), initialScale = 0.97f),
        exit = fadeOut(Motion.tw(120)),
        label = "retroLayer",
    ) {
        val k = key ?: last[0]
        if (k != null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(k) }
    }
}

@Composable
private fun TopBar(tab: Int, focus: List<FocusRequester>, enabled: Boolean, onTab: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(15_000) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 8.dp, bottom = 2.dp)) {
        listOf(R.string.retro_library, R.string.retro_settings).forEachIndexed { i, label ->
            var focused by remember { mutableStateOf(false) }
            Text(
                stringResource(label), fontSize = 22.sp, fontWeight = if (tab == i) FontWeight.Black else FontWeight.Bold,
                color = if (tab == i) colors.onBackground else colors.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.focusRequester(focus[i]).focusProperties { canFocus = enabled }
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onTab(i) }
                    .clip(RoundedCornerShape(10.dp)).background(if (focused) colors.surfaceVariant else Color.Transparent)
                    .clickable(enabled = enabled) { onTab(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)),
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
        )
    }
}

/** The systems under the card, Daijishō's strip: one stop for focus, where left and right flip the card and A opens its games. */
@Composable
private fun SystemsStrip(
    s: FrontEndState, at: Int, focus: FocusRequester, focused: Boolean, onFocused: (Boolean) -> Unit, step: (Int) -> Unit,
    onUp: () -> Unit, onOpen: () -> Unit, onPick: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val list = rememberLazyListState()
    LaunchedEffect(at) { list.animateScrollToItem((at - 3).coerceAtLeast(0)) }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (focused) colors.surfaceVariant.copy(alpha = 0.7f) else Color.Transparent)
            .focusRequester(focus).onFocusChanged { onFocused(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false
                else when (e.key) {
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionRight -> { step(1); true }
                    Key.DirectionUp -> { onUp(); true }
                    Key.ButtonA, Key.Enter, Key.DirectionCenter -> { onOpen(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
            itemsIndexed(s.retro, key = { _, sys -> sys.platform.id }) { i, sys ->
                val here = i == at
                Text(
                    sys.platform.shortname.uppercase(), fontSize = 13.sp, fontWeight = if (here) FontWeight.Black else FontWeight.Bold, fontStyle = FontStyle.Italic,
                    color = if (here) Color.White else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip,
                    modifier = Modifier.widthIn(max = 140.dp).clip(RoundedCornerShape(8.dp)).background(if (here) (if (focused) pal.signal else colors.surfaceVariant) else Color.Transparent)
                        .pointerInput(i) { detectTapGestures { onPick(i) } }.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
internal fun KeysBar(keys: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 18.dp, vertical = 7.dp)) {
        Spacer(Modifier.weight(1f))
        Text(keys, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
    }
}
