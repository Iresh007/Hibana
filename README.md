# Hibana

**Manga • Novels — stories that stay with you.**

Hibana is a free, Mihon-style **web-novel / light-novel reader** for Android,
written in Kotlin + Jetpack Compose, with a violet-to-blue-on-deep-navy theme.
First milestone targets Android; a Windows build is a later phase (see *Roadmap*).

Core features in this scaffold:

- **Library** with reading progress and resume-where-you-left-off
- **Reader** with font size, line spacing, font family, and 5 themes (light, dark, sepia, black/OLED, system)
- **Text-to-speech** — read chapters aloud with play/pause/skip, speed & pitch, paragraph highlight + auto-scroll, foreground playback service
- **Offline downloads** — chapters saved to app storage and read without network
- **Pluggable source/extension system** — Mihon-style `Source` contract, a built-in Project Gutenberg source, and adapters for third-party extension ecosystems

---

## Build & run

Requires **Android Studio (Koala/2024.1+)** and **JDK 17**.

1. Open the project folder in Android Studio (it will use the Gradle version
   catalog in `gradle/libs.versions.toml`).
2. Let it sync, then Run on a device/emulator (min SDK 24, target 34).

Command line (once a Gradle wrapper is generated):

```bash
gradle wrapper --gradle-version 8.9   # first time, to create ./gradlew
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

> The binary `gradle/wrapper/gradle-wrapper.jar` is not checked in (it's a
> binary blob); `gradle wrapper` regenerates it. Android Studio does this
> automatically on first sync.

On first launch the **Browse** tab already lists real, public-domain books via
Project Gutenberg — add one to your library and open it to try the reader + TTS
immediately, no extensions required.

---

## Architecture

Single Gradle module, MVVM, manual DI (no Hilt/Dagger) via `AppContainer`.

```
NovelReaderApp ── AppContainer (DI: db, http, sources, repos, downloader, TTS, loaders)
                     │
 ui/  Compose + ViewModels ── MVVM
   RootNav                     bottom nav: Library · Browse · Settings, + Reader route
   screens/                    LibraryScreen, BrowseScreen, ReaderScreen, SettingsScreen
   theme/                      Material 3 + dedicated reader palettes (sepia/black)
                     │
 data/                         Room (novels, chapters), LibraryRepository, SettingsRepository (DataStore)
 source/                       Source contract, HttpSource base, SourceManager, builtin/GutenbergSource
 extension/                    ExtensionLoader + per-ecosystem adapters
 download/                     Downloader (chapter text → local files)
 tts/                          TtsManager (engine) + TtsService (foreground playback)
```

Design decisions worth noting:

- The `Source` interface is deliberately small and Mihon-shaped so extensions
  are easy to author and so third-party extensions can be adapted onto it.
- Read/download state lives on the `chapters` table — one source of truth shared
  by library, reader, and downloader.
- TTS runs paragraph-by-paragraph, exposing the spoken index so the reader can
  auto-scroll and highlight in sync (accessibility + follow-along).

---

## Extension compatibility (Mihon / IReader / LNReader / Manatan)

**Goal:** interoperate with extensions from these four apps. They do **not**
share an extension format, so the app uses one adapter per ecosystem behind a
common `ExtensionLoader` → `Source` seam. Honest status:

| Ecosystem | Packaging | Language | Content | Adapter status |
|-----------|-----------|----------|---------|----------------|
| **Mihon / Tachiyomi** | APK (installed package) | Kotlin | **Manga/images** | `ApkExtensionLoader` — discovery implemented (scans `tachiyomi.extension` metadata); DexClassLoader + API-shim wiring remaining. Note: returns image pages, not novel text. |
| **IReader** | APK | Kotlin | Novels (text) | **Implemented** — `IReaderExtensionLoader` (feature-scan + read-only DexClassLoader + `Dependencies` constructor, a faithful port of IReader's `AndroidCatalogLoader`) and `IReaderSourceAdapter` (reflective bridge over `CatalogSource`). One activation step remains: bundle IReader's `source-api` + set `IReaderRuntime.dependencyFactory` (see below). |
| **LNReader** | `.js` plugins from a repo | JavaScript | Novels (text) | `LNReaderLoader` — architecture + `Source` bridge defined; needs an embedded JS engine (QuickJS recommended) with `fetch`/DOM shims. |
| **Manatan** | APK | Kotlin | Manga | `ApkExtensionLoader(MANATAN)` — same path as Mihon. |

What "remaining" means concretely:

- **APK ecosystems:** load the extension APK with a `DexClassLoader`, resolve the
  declared source/factory class from the package `<meta-data>`, and map that
  ecosystem's native `Source`/`HttpSource` onto our `Source`. Each needs thin
  compile-only shim interfaces matching its API package (e.g.
  `eu.kanade.tachiyomi.source.*`) so the extension's dex resolves at runtime.
- **LNReader:** add a JS engine dependency (e.g. `quickjs-android`), implement
  `JsRuntime`/`JsPluginHandle` in `LNReaderLoader.kt`, and marshal JS objects to
  our data classes.

The reference sources you provided — `mihon-0.20.1.zip`, `IReader-2.0.23.zip`,
`Manatan-6.0.64.zip` — are the authoritative APIs to build each adapter against.

### Activating IReader extensions

The loader/adapter are done and compile standalone (via reflection, so the app
has no compile-time coupling to IReader). To run real IReader extension APKs,
the host must expose the API those extensions were compiled against:

1. Add IReader's `source-api` (`ireader.core.source.*`) to the app — either its
   published artifact or by vendoring `IReader-2.0.23/source-api` as a Gradle
   module. This also pulls Ktor + Ksoup, which IReader sources use.
2. Provide concrete `HttpClientsInterface` + `PreferenceStore` implementations
   and wire them once at startup:

   ```kotlin
   IReaderRuntime.dependencyFactory = { ctx, pkgName ->
       ireader.core.source.Dependencies(httpClients, preferenceStore)
   }
   ```

Until step 2 runs, `IReaderRuntime.isAvailable()` is false and the loader fails
fast with a clear message instead of crashing. Discovery (listing installed
IReader extensions) works regardless.

**Important caveat:** Mihon and Manatan extensions are for **manga** and return
image page URLs, not text — they're usable for manga/webtoon reading but won't
feed the novel reader/TTS. For text novels, IReader and LNReader are the right
ecosystems.

---

## Adding a built-in source

Implement `Source` (or extend `HttpSource`) and register it in
`AppContainer.init`:

```kotlin
sourceManager.register(MyNovelSource(httpClient))
```

`GutenbergSource` is a complete, working reference.

---

## Roadmap

- Novel detail screen (chapter list, mark-read, per-chapter download queue)
- Extension install/repo UI + finish the four adapters
- WorkManager-backed download queue with retries and notifications
- Library categories, sorting/filtering, backup/restore
- **Windows build** via Kotlin Multiplatform / Compose Multiplatform, sharing the
  `data`, `source`, `download`, and (non-Android) TTS logic

## Legality

The app ships **no copyrighted content**. The built-in source is public-domain
(Project Gutenberg). Third-party extensions are installed by the user and are
their responsibility, exactly as in Mihon.
