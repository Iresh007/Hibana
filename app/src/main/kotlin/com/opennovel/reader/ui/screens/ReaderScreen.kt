package com.opennovel.reader.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.data.ReadingMode
import com.opennovel.reader.data.TapZoneLayout
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.ui.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    // Tap-zone layout and volume paging are reader-wide preferences that the
    // shared ReaderViewModel has no setters for, so the sheet writes them
    // straight to the repository the container already owns.
    val settingsRepo = remember(context) {
        (context.applicationContext as NovelReaderApp).container.settingsRepository
    }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState { pageUrls.size }
    var showSettings by remember { mutableStateOf(false) }
    var pageActionsFor by remember { mutableStateOf<Int?>(null) }
    // Starts visible so the way back out of the reader is never hidden on entry.
    var menuVisible by remember { mutableStateOf(true) }

    // A chapter is either text (novel) or page images (manga), never both.
    val isManga = pageUrls.isNotEmpty() && content?.paragraphs.isNullOrEmpty()
    val paged = settings.readingMode.isPaged()
    val pagedManga = isManga && paged
    val rtl = settings.readingMode == ReadingMode.PAGED_RTL

    LaunchedEffect(chapterId) { vm.load(chapterId) }

    // Auto-scroll to the paragraph TTS is currently speaking (text mode only;
    // in manga mode the indices refer to OCR lines, not pages).
    LaunchedEffect(ttsState.index, ttsState.speaking) {
        if (ttsState.speaking && !isManga) listState.animateScrollToItem(ttsState.index)
    }

    // Persist scroll progress as the user reads.
    //
    // The loaded content is a key. Without it the first pass ran while content
    // was still null, bailed out, and — because neither the scroll index nor
    // isManga changed when the chapter finally arrived — never ran again, so
    // progress was only ever saved if the user happened to scroll.
    val paragraphCount = content?.paragraphs?.size ?: 0
    // Paged modes move the pager, not the list, so the list index would sit at 0
    // for a whole chapter and record no progress at all.
    val position = if (pagedManga) pagerState.currentPage else listState.firstVisibleItemIndex
    LaunchedEffect(position, isManga, paragraphCount, pageUrls.size) {
        val total = if (isManga) pageUrls.size else paragraphCount
        if (total > 0) vm.saveProgress(position.toFloat() / total)
    }

    // Honours the preference here rather than on the window flag, so leaving the
    // reader always releases it even if the screen is torn down mid-chapter.
    DisposableEffect(view, settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Immersive mode follows the menu: pages get the whole screen while reading,
    // and the system bars come back with the controls that sit beside them.
    val immersive = settings.comicFullscreen && isManga && !menuVisible
    DisposableEffect(view, immersive) {
        val controller = view.context.findActivity()?.window
            ?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(settings.volumeKeyPaging, isManga) {
        // Without focus the volume keys never reach the composable and the system
        // volume panel handles them instead.
        if (settings.volumeKeyPaging && isManga) runCatching { keyFocus.requestFocus() }
    }

    /** One "page forward/back", whatever the current layout means by a page. */
    fun turnPage(forward: Boolean) {
        scope.launch {
            if (pagedManga) {
                val last = (pageUrls.size - 1).coerceAtLeast(0)
                val target = (pagerState.currentPage + if (forward) 1 else -1).coerceIn(0, last)
                if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
            } else {
                // Continuous strips have no pages; a screenful less an overlap is
                // the equivalent step, and the overlap keeps a line of context.
                val step = listState.layoutInfo.viewportSize.height * 0.9f
                if (step > 0f) listState.animateScrollBy(if (forward) step else -step)
            }
        }
    }

    val fontFamily = when (settings.fontFamily) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }

    // The bars are part of the reader menu for comics; prose keeps them always
    // visible, since it has no tap zones to toggle them with.
    val barsVisible = !isManga || menuVisible

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            if (barsVisible) {
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
            }
        },
        bottomBar = {
            if (barsVisible) {
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
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(keyFocus)
                .onKeyEvent { event ->
                    if (!settings.volumeKeyPaging || !isManga || event.type != KeyEventType.KeyDown) {
                        return@onKeyEvent false
                    }
                    when (event.key) {
                        // Up goes back: the rocker maps to the page order, not to
                        // "louder is forward".
                        Key.VolumeUp -> { turnPage(false); true }
                        Key.VolumeDown -> { turnPage(true); true }
                        else -> false
                    }
                }
                .focusable()
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                // Manga layout follows the chosen reading mode, with the tap
                // zones layered over it.
                isManga -> Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(settings.tapZoneLayout, settings.readingMode, pageUrls.size) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val action = settings.tapZoneLayout.actionAt(
                                        x = offset.x / size.width.coerceAtLeast(1),
                                        y = offset.y / size.height.coerceAtLeast(1),
                                        rightToLeft = rtl,
                                    )
                                    when (action) {
                                        TapAction.NEXT -> turnPage(true)
                                        TapAction.PREVIOUS -> turnPage(false)
                                        TapAction.MENU -> menuVisible = !menuVisible
                                    }
                                },
                                onLongPress = {
                                    pageActionsFor =
                                        if (paged) pagerState.currentPage
                                        else listState.firstVisibleItemIndex
                                },
                            )
                        },
                ) {
                    MangaPages(
                        pageUrls = pageUrls,
                        mode = settings.readingMode,
                        listState = listState,
                        pagerState = pagerState,
                    )
                }

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
                    onTapZoneLayout = { scope.launch { settingsRepo.setTapZoneLayout(it) } },
                    onVolumeKeyPaging = { scope.launch { settingsRepo.setVolumeKeyPaging(it) } },
                    onFullscreen = { scope.launch { settingsRepo.setComicFullscreen(it) } },
                    onDismiss = { showSettings = false },
                )
            }

            pageActionsFor?.let { index ->
                val url = pageUrls.getOrNull(index)
                PageActionsSheet(
                    onDismiss = { pageActionsFor = null },
                    onSave = {
                        pageActionsFor = null
                        scope.launch {
                            snackbars.showSnackbar(savePageImage(context, url, index))
                        }
                    },
                    onShare = {
                        pageActionsFor = null
                        if (url == null) return@PageActionsSheet
                        // Sharing the link, not the bytes: handing another app a
                        // file needs a FileProvider this app does not declare.
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(send, "Share page"))
                    },
                    onSetCover = {
                        pageActionsFor = null
                        scope.launch {
                            snackbars.showSnackbar(
                                "Custom covers aren't supported yet — the library stores only the source's cover",
                            )
                        }
                    },
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

/** Modes that show one discrete page at a time rather than a scrolling strip. */
private fun ReadingMode.isPaged(): Boolean = when (this) {
    ReadingMode.PAGED_LTR, ReadingMode.PAGED_RTL, ReadingMode.PAGED_VERTICAL -> true
    ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> false
}

/** What a tap in a given zone does. */
private enum class TapAction { NEXT, PREVIOUS, MENU }

/**
 * Resolves a tap to an action from its position, expressed as fractions of the
 * viewport so the zones scale to any screen.
 *
 * [rightToLeft] mirrors the forward/back halves rather than the geometry: a
 * right-to-left title is read from the right, so the left of the screen is the
 * direction of travel and must advance.
 */
private fun TapZoneLayout.actionAt(x: Float, y: Float, rightToLeft: Boolean): TapAction {
    val action = when (this) {
        TapZoneLayout.DISABLED -> TapAction.MENU
        TapZoneLayout.EDGE -> when {
            x < 0.33f -> TapAction.PREVIOUS
            x > 0.66f -> TapAction.NEXT
            else -> TapAction.MENU
        }
        // A reading strip down the left, everything else forward, and the top of
        // the screen reserved for the menu.
        TapZoneLayout.KINDLE -> when {
            y < 0.33f -> TapAction.MENU
            x < 0.33f -> TapAction.PREVIOUS
            else -> TapAction.NEXT
        }
        // Forward wraps the right and bottom edges into an L; back is the left
        // column; the centre and top open the menu.
        TapZoneLayout.L_SHAPED -> when {
            x < 0.33f -> TapAction.PREVIOUS
            y > 0.66f -> TapAction.NEXT
            x > 0.66f && y > 0.33f -> TapAction.NEXT
            else -> TapAction.MENU
        }
    }
    return if (!rightToLeft) {
        action
    } else when (action) {
        TapAction.NEXT -> TapAction.PREVIOUS
        TapAction.PREVIOUS -> TapAction.NEXT
        TapAction.MENU -> TapAction.MENU
    }
}

/** Walks the context wrappers Compose sits behind to reach the hosting activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Writes a page to the device gallery.
 *
 * Coil already holds the decoded page, so this re-requests through the same
 * loader and gets a cache hit instead of a second download. Below API 29 there
 * is no scoped MediaStore write, and asking for WRITE_EXTERNAL_STORAGE for a
 * single feature is a poor trade, so those devices get the app's own Pictures
 * directory — visible over USB and in file managers, no permission needed.
 */
private suspend fun savePageImage(context: Context, url: String?, index: Int): String {
    if (url == null) return "That page is no longer loaded"
    return withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
            ?.drawable?.toBitmap()
            ?: return@withContext "Could not load that page"

        val name = "hibana_page_${index + 1}_${System.currentTimeMillis()}.jpg"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Hibana",
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("no gallery entry")
                context.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                } ?: error("no output stream")
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "Hibana",
                ).apply { mkdirs() }
                File(dir, name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
            }
        }.fold(
            onSuccess = { "Saved to Pictures/Hibana" },
            onFailure = { "Could not save that page" },
        )
    }
}

/** Long-press actions for the page under the finger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageActionsSheet(
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onSetCover: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            PageAction(Icons.Filled.Save, "Save image", onSave)
            PageAction(Icons.Filled.Share, "Share image", onShare)
            PageAction(Icons.Filled.Image, "Set as cover", onSetCover)
        }
    }
}

@Composable
private fun PageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableMinTouch(onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.padding(start = 16.dp))
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
    onTapZoneLayout: (TapZoneLayout) -> Unit,
    onVolumeKeyPaging: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
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

                HorizontalDivider()

                Text("Tap zones", style = MaterialTheme.typography.labelLarge)
                TapZoneLayout.entries.forEach { layout ->
                    Row(
                        Modifier.fillMaxWidth().clickableMinTouch { onTapZoneLayout(layout) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.tapZoneLayout == layout, onClick = null)
                        Text(layout.label, Modifier.padding(start = 8.dp))
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume keys turn pages", Modifier.weight(1f))
                    Switch(checked = settings.volumeKeyPaging, onCheckedChange = onVolumeKeyPaging)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Fullscreen", Modifier.weight(1f))
                    Switch(checked = settings.comicFullscreen, onCheckedChange = onFullscreen)
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
    pagerState: PagerState,
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
            // Mirroring the layout direction flips swipe direction and page order
            // together, so the pager itself needs no index arithmetic.
            CompositionLocalProvider(
                LocalLayoutDirection provides
                    if (mode == ReadingMode.PAGED_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    PagedImage(pageUrls[page], page)
                }
            }
        }

        ReadingMode.PAGED_VERTICAL -> {
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
