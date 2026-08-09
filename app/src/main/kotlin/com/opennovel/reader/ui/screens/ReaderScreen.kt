package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opennovel.reader.data.ReadingMode
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.ui.ReaderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapterId: Long,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: ReaderViewModel = viewModel(factory = factory)
    val content by vm.content.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val ttsState by vm.ttsState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pageUrls by vm.pageUrls.collectAsStateWithLifecycle()
    val ocrRunning by vm.ocrRunning.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    // A chapter is either text (novel) or page images (manga), never both.
    val isManga = pageUrls.isNotEmpty() && content?.paragraphs.isNullOrEmpty()

    LaunchedEffect(chapterId) { vm.load(chapterId) }

    // Auto-scroll to the paragraph TTS is currently speaking (text mode only;
    // in manga mode the indices refer to OCR lines, not pages).
    LaunchedEffect(ttsState.index, ttsState.speaking) {
        if (ttsState.speaking && !isManga) listState.animateScrollToItem(ttsState.index)
    }

    // Persist scroll progress as the user reads.
    LaunchedEffect(listState.firstVisibleItemIndex, isManga) {
        val total = if (isManga) pageUrls.size else content?.paragraphs?.size ?: return@LaunchedEffect
        if (total > 0) vm.saveProgress(listState.firstVisibleItemIndex.toFloat() / total)
    }

    val fontFamily = when (settings.fontFamily) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Reader settings")
                    }
                    IconButton(onClick = { vm.translateCurrent() }) {
                        Icon(Icons.Filled.Translate, contentDescription = "Translate chapter")
                    }
                    IconButton(onClick = { vm.downloadCurrent() }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download for offline")
                    }
                },
            )
        },
        bottomBar = {
            TtsBar(
                speaking = ttsState.speaking,
                paused = ttsState.paused,
                onPlay = {
                    if (ttsState.paused) vm.resumeTts()
                    else vm.startTts(settings.ttsSpeed, settings.ttsPitch, settings.ttsVoice)
                },
                onPause = vm::pauseTts,
                onStop = vm::stopTts,
                onNext = { vm.tts.skipNext() },
                onPrevious = { vm.tts.skipPrevious() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                // Manga layout follows the chosen reading mode.
                isManga -> MangaPages(
                    pageUrls = pageUrls,
                    mode = settings.readingMode,
                    listState = listState,
                )

                else -> LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy((16 * settings.lineSpacing).dp),
                ) {
                    itemsIndexed(content?.paragraphs.orEmpty()) { index, para ->
                        val highlighted = ttsState.speaking && index == ttsState.index
                        Text(
                            text = para,
                            fontFamily = fontFamily,
                            fontSize = (18 * settings.fontScale).sp,
                            lineHeight = (18 * settings.fontScale * settings.lineSpacing).sp,
                            textAlign = TextAlign.Start,
                            color = if (highlighted)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (showSettings) {
                ReaderSettingsSheet(
                    settings = settings,
                    isManga = isManga,
                    vm = vm,
                    onDismiss = { showSettings = false },
                )
            }

            // OCR runs before manga narration can start; it can take a few
            // seconds per chapter, so surface it rather than appearing frozen.
            if (ocrRunning || translating) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text(if (ocrRunning) "Reading text from pages…" else "Translating…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * In-reader settings sheet.
 *
 * The same values live in the Settings screen, but adjusting type size or page
 * layout is something you do *while reading and looking at the result* — making
 * the user leave the chapter to tweak it defeats the purpose. Only the controls
 * relevant to the current content are shown: page-layout options are pointless
 * for a text novel, and typography is pointless for manga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    settings: com.opennovel.reader.data.ReaderSettings,
    isManga: Boolean,
    vm: ReaderViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Reader settings", style = MaterialTheme.typography.titleMedium)

            if (isManga) {
                Text("Page layout", style = MaterialTheme.typography.labelLarge)
                ReadingMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickableMinTouch { vm.setReadingMode(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.readingMode == mode, onClick = null)
                        Text(mode.label, Modifier.padding(start = 8.dp))
                    }
                }
            } else {
                Text(
                    "Text size: ${"%.1f".format(settings.fontScale)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = settings.fontScale,
                    onValueChange = vm::setFontScale,
                    valueRange = 0.8f..1.8f,
                )
                Text(
                    "Line spacing: ${"%.1f".format(settings.lineSpacing)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = settings.lineSpacing,
                    onValueChange = vm::setLineSpacing,
                    valueRange = 1.0f..2.2f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("serif", "sans", "monospace").forEach { family ->
                        FilterChip(
                            selected = settings.fontFamily == family,
                            onClick = { vm.setFontFamily(family) },
                            label = { Text(family.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            HorizontalDivider()

            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on", Modifier.weight(1f))
                Switch(checked = settings.keepScreenOn, onCheckedChange = vm::setKeepScreenOn)
            }
        }
    }
}

/**
 * Renders manga pages in the reader's configured layout.
 *
 * Continuous modes scroll as one strip (manhwa/manhua convention); paged modes
 * show one page at a time and swipe. [ReadingMode.PAGED_RTL] reverses the pager
 * so page 1 sits on the right, which is how Japanese manga is read — without it
 * a right-to-left title pages backwards.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MangaPages(
    pageUrls: List<String>,
    mode: ReadingMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    when (mode) {
        ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> {
            val gap = if (mode == ReadingMode.CONTINUOUS_VERTICAL) 8.dp else 0.dp
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                itemsIndexed(pageUrls) { index, url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        ReadingMode.PAGED_LTR, ReadingMode.PAGED_RTL -> {
            val rtl = mode == ReadingMode.PAGED_RTL
            val pagerState = rememberPagerState(
                initialPage = if (rtl) pageUrls.lastIndex.coerceAtLeast(0) else 0,
            ) { pageUrls.size }
            // Mirroring the layout direction flips swipe direction and page order
            // together, so the pager itself needs no index arithmetic.
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    PagedImage(pageUrls[page], page)
                }
            }
        }

        ReadingMode.PAGED_VERTICAL -> {
            val pagerState = rememberPagerState { pageUrls.size }
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                PagedImage(pageUrls[page], page)
            }
        }
    }
}

/** A single page fitted to the screen, as paged modes expect. */
@Composable
private fun PagedImage(url: String, index: Int) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        AsyncImage(
            model = url,
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TtsBar(
    speaking: Boolean,
    paused: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous paragraph")
            }
            IconButton(onClick = if (speaking) onPause else onPlay) {
                Icon(
                    if (speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (speaking) "Pause" else "Read aloud",
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next paragraph")
            }
        }
    }
}

