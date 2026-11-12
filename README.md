# Hibana 話

![Hibana](Logo_1.png)

**Manga • Manhwa • Manhua • Web Novels — a story told by lamplight.**

Hibana is a free, open-source reader for Android, written in Kotlin and Jetpack
Compose. The name is a coined compound — 灯 *lamplight* + 話 *story/episode* — and
話 doubles as "chapter" across manga and web novels.

It reads **comics** (manga, manhwa, manhua) and **web/light novels** from
third-party extensions, and it runs extensions from **four ecosystems at once**:
Mihon/Tachiyomi, Manatan, IReader, and LNReader.

> **Anime is not part of this app.** Video playback is a possible future phase of
> the wider project, not a goal of this codebase.

---

## Status

Hibana is a personal-use project under active development. CI (build, unit
tests, lint and an emulator launch test) is green, but a lot has only been
verified by tests and an empty first-run emulator, not by long real-world use.
Treat anything not listed under **Works** as unproven.

## Features

**Library**
- Separate **Manga & Manhwa** and **Novels** sections, each with its own library,
  updates, categories and reader settings
- Categories (custom shelves) with tabs and chapter counts; a default category
  for new entries
- Tri-state filters (downloaded / unread / started), sort by title, chapters,
  unread, latest chapter, date added or random
- Grid, compact grid or list, with unread and downloaded cover badges
- Long-press multi-select for batch actions; overflow menu with *Update library*
  and *Open random entry*

**Entry and chapters**
- Swipe a chapter right to mark read, left to bookmark
- Long-press to multi-select (select all / invert), then bookmark, mark read or
  unread, download or delete downloads
- Per-entry chapter filter, sort and title display
- Download housekeeping: delete after read, keep last N read chapters, protect
  bookmarked chapters, download ahead, and rules for auto-downloading new
  chapters

**Reading**
- Text reader with font size, line spacing, font family and reading modes
- Comic reader: webtoon, gapped strip, paged LTR, paged RTL (manga) and paged
  vertical; pinch and double-tap zoom; resume where you left off
- Reader options: image scale type, side padding, brightness, colour filter,
  grayscale, inverted colours, orientation, page number, page transitions,
  zoom if landscape, flash on page change, draw into display cutout
- Next/previous chapter honours *skip read*, *skip duplicate* and *skip filtered*
  chapters

**Text-to-speech and translation**
- Narrates novels with play/pause/skip, speed, pitch and paragraph highlighting
- Reads **comics** aloud via on-device OCR (ML Kit: Latin, Japanese, Korean,
  Chinese) with per-script panel order
- On-device translation into English or Hindi

**Sources and extensions**
- Four ecosystems side by side: **Mihon/Tachiyomi** and **Manatan** APK
  extensions (through a bundled `eu.kanade.tachiyomi` source-api runtime),
  **IReader** APK extensions, and **LNReader** JavaScript plugins (Rhino)
- Extension stores: any repository URL, including Keiyoushi-style `index.pb`,
  `index.min.json` and LNReader `plugins.min.json`
- Extension list with installed, available and updatable sections, source icons,
  language filter and an extension-info page (trust, language, links)
- **Trust model**: APK extensions run in-process and are not loaded until you
  approve them; trust is bound to the package and its signing certificate
- Sources tab with pinning, WebView, and per-source preferences
- Option to hide 18+ extensions and sources
- Global search across all installed sources

**Migration**
- Move one, several or all entries from a source to another; candidates are
  scored and compared, progress is re-matched by chapter number, nothing moves
  until you confirm; migration options (chapters read, categories, bookmarks,
  keep or remove the original)

**Updates, backups and data**
- Updates tab and library update with options: only started, only non-completed,
  excluded categories, only while charging, private notifications
- Scheduled updates (6h to monthly) and scheduled automatic backups
- **Backup/restore in Mihon's `.tachibk` format**, and **import of Manatan
  `.manatanbk`** backups. Hibana does not store tracking data, per-chapter
  flags, notes or source preferences, so those are dropped on import
- History and reading statistics

**Appearance and privacy**
- Themes: Light, Dark, System, Sepia and **AMOLED** (pure black)
- Accent themes: Hibana, Lavender, Midnight dusk, Green apple, Strawberry, Tako,
  Yin & Yang, and **Material You** (wallpaper colours, Android 12+) — accents
  apply to Light, Dark, System and AMOLED
- Relative or absolute dates with a custom format
- App lock (biometrics and/or device credential, with timeout), secure screen,
  incognito mode
- Custom User-Agent and DNS-over-HTTPS applied to the app and extensions

**Known gaps**
- Reader: crop borders, zoom start position, dual-page split and rotate wide
  pages are stored settings with no effect yet
- A number of other settings are still stored but not wired (tracking, language
  badge, tablet UI and others)
- Cloudflare challenge handling for extensions is not implemented
- Real extensions have been checked only statically and in unit tests; they have
  not been exercised end to end on a device

---

## Build

Requires **JDK 17 or newer** (CI uses 21) and the Android SDK.

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

`gradle/wrapper/gradle-wrapper.jar` is not committed; run `gradle wrapper
--gradle-version 8.14.5` once, or let Android Studio do it on first sync.

The Firebase plugin is applied only if `app/google-services.json` exists (it is
git-ignored); without it the app builds normally.

| | |
|---|---|
| applicationId | `com.hibana.app` |
| minSdk | 26 — the floor declared by `io.github.ireaderorg:source-api-android` |
| targetSdk / compileSdk | 34 / 36 (`androidx.core` 1.17 needs 36) |
| Kotlin | 2.2.21, pinned to what IReader extensions are built against |
| Gradle / AGP | 8.14.5 / 8.9.1 |
| ABIs | arm64-v8a, armeabi-v7a (add x86_64 with `-PincludeX86=true`) |

AGP, Kotlin and library versions are deliberately pinned together; upgrading one
usually forces the others and the extension runtime, so do it as a separate,
tested step.

---

## Releases and signing

Releases are **signed**. Android ties app identity to the signing key: an update
signed by a different key is rejected, so the key must stay stable and private.

### One-time setup

Create a keystore and keep it somewhere safe and backed up:

```bash
keytool -genkeypair -v -keystore hibana-release.jks -alias hibana \
        -keyalg RSA -keysize 4096 -validity 10000
```

> **If this file is lost, no future build can update an already-installed
> Hibana.** The only remedy is uninstall and reinstall, which loses local data.
> Back it up before shipping anything.

**Local signed builds** — copy `keystore.properties.example` to
`keystore.properties` and fill it in. Both the keystore and that file are
gitignored.

**CI** — add four repository secrets under *Settings → Secrets and variables →
Actions*:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE` | base64 of the `.jks` (see below) |
| `SIGNING_KEY_ALIAS` | the alias, e.g. `hibana` |
| `SIGNING_KEY_PASSWORD` | key password |
| `SIGNING_STORE_PASSWORD` | keystore password |

To produce the base64:

```powershell
# Windows PowerShell — use an ABSOLUTE path. .NET resolves relative paths against
# the process working directory, which `cd` does not change, so a relative path
# here fails with a confusing "could not find file" pointing at some other folder.
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\hibana-release.jks")) | Set-Clipboard
(Get-Clipboard).Length   # sanity check: expect a few thousand characters
```

```bash
# Linux
base64 -w0 hibana-release.jks
# macOS
base64 -i hibana-release.jks
```

### Cutting a release

```bash
git tag v0.2.0
git push --tags
```

`release.yml` then extracts the tag, builds a signed APK with `versionName` taken
from the tag, verifies the signature with `apksigner`, and publishes a GitHub
Release with the APK attached. The workflow **fails loudly if the secrets are
missing** rather than quietly producing an unsigned build.

---

## Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `build.yml` | push, PR | Assemble, unit tests, lint; uploads the debug APK and reports |
| `android.yml` | push, PR | Installs the debug APK on an emulator, runs a monkey test, uploads logcat and crash logs |
| `firebase-test-lab.yml` | manual | Runs the APK on Firebase Test Lab (needs the `GCP_SA_KEY` secret) |
| `release.yml` | `v*` tag | Signed release APK, verified, attached to a GitHub Release |

---

## Roadmap

- Wire the remaining unused settings (see *Known gaps*)
- Run real extensions end to end on a device and fix what breaks
- Cloudflare challenge handling for extensions
- AniList / MyAnimeList tracking
- Upgrade AGP, Kotlin and libraries together
- Windows desktop build
- Anime support — a possible later phase of the wider project, out of scope here

---

## License

Licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE).

Apache 2.0 fits this project for three reasons: Mihon and IReader are both
Apache 2.0, so the compatibility layers here sit comfortably alongside them; it
grants patent rights explicitly, which bare MIT does not; and it requires
attribution notices to be preserved, which matters because Hibana implements
several upstream APIs and formats.

Hibana ships and hosts no content. It reads from sources supplied by third-party
extensions that you choose to install; responsibility for what those extensions
access rests with their operators and with you.

---

## Acknowledgements

Hibana interoperates with formats and extension APIs from
[Mihon](https://github.com/mihonapp/mihon),
[IReader](https://github.com/IReaderorg/IReader),
[LNReader](https://github.com/lnreader/lnreader), and
[Manatan](https://github.com/KolbyML/Manatan). Those projects' extension
ecosystems make this app useful; the compatibility layers here are written
against their public APIs.
