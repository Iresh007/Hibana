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

## Features

**Library**
- Categories (custom shelves) with tabs, plus per-entry assignment
- Tri-state filters (downloaded / unread / started) — off, only, or exclude
- Sort by title, total chapters, unread count, latest chapter, date added, random
- Comfortable grid, compact grid, or list, with unread/downloaded cover badges
- Long-press multi-select for batch actions

**Reading**
- Text reader with font size, line spacing, font family, and 5 themes
- Comic reader with five page layouts: webtoon (continuous), gapped vertical
  strip, paged left-to-right, **paged right-to-left** (Japanese manga), and paged
  vertical (e-reader style)
- In-reader settings overlay so type and layout are adjusted while reading
- Missing-chapter detection from chapter numbering, plus upload dates

**Text-to-speech and translation**
- Reads novel chapters aloud — play/pause/skip, speed, pitch, paragraph
  highlighting and auto-scroll, with a foreground playback service
- Reads **comics** aloud too, via on-device OCR (ML Kit: Latin, Japanese, Korean,
  Chinese). Panel order is corrected per script, so right-to-left Japanese pages
  narrate in reading order rather than detection order
- On-device translation into **English or Hindi**
- Narration language: English or Hindi

**Sources and extensions**
- Four ecosystems supported side by side:
  - **Mihon / Tachiyomi** and **Manatan** — APK extensions, via a bundled
    `eu.kanade.tachiyomi` source-api runtime
  - **IReader** — APK extensions, against the published `io.github.ireaderorg:source-api`
  - **LNReader** — JavaScript plugins, executed on Rhino (plugins compile to ES5,
    so no NDK or per-ABI splits are needed)
- **Extension stores**: add any repository index URL, including custom and
  self-hosted ones. Both `index.min.json` (Mihon-style) and `plugins.min.json`
  (LNReader) are detected automatically
- Update detection, language filtering, and per-source browsing (Popular/Latest)
- **Trust model**: APK extensions run in-process, so they are not loaded until
  you approve them. Trust is keyed on package **plus signing certificate**, so an
  update re-signed by someone else must be approved again

**Discovery**
- Global search across every installed source, results grouped per source, each
  group appearing as soon as that source responds
- Browse split into **Sources | Extensions | Migrate**

**Source migration**
- Move entries when a source breaks or falls behind — one title, several, or an
  entire source at once
- Candidates are matched by normalised title plus author, scored and shown with a
  chapter-count comparison; nothing moves until you confirm
- Reading progress is re-matched **by chapter number**, so it survives the move
- Search all sources, or restrict to a chosen subset

**Library upkeep**
- Updates tab with newest chapters across the library
- Scheduled updates: manual, 6h, 12h, 24h, alternate day, weekly (day + time), or
  monthly (date + time)
- Download manager with queue, retry, and delete
- **Backup and restore in Mihon's `.tachibk` format** — a Hibana backup restores
  into Mihon, and a Mihon backup restores into Hibana

---

## Build

Requires **JDK 17** and the Android SDK (Android Studio Ladybug or newer).

```bash
gradle wrapper --gradle-version 8.11.1   # first time, to create ./gradlew
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The binary `gradle/wrapper/gradle-wrapper.jar` is intentionally not committed;
`gradle wrapper` regenerates it, and Android Studio does so on first sync.

| | |
|---|---|
| minSdk | 26 — the floor declared by `io.github.ireaderorg:source-api-android` |
| targetSdk | 34 |
| compileSdk | 36 — required by `androidx.core` 1.17 |
| Kotlin | 2.2.21 (matches what IReader extensions are built against) |
| Gradle / AGP | 8.11.1 / 8.9.1 |

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
| `SIGNING_KEYSTORE` | base64 of the `.jks` — `base64 -w0 hibana-release.jks` (macOS: `base64 -i`) |
| `SIGNING_KEY_ALIAS` | the alias, e.g. `hibana` |
| `SIGNING_KEY_PASSWORD` | key password |
| `SIGNING_STORE_PASSWORD` | keystore password |

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
| `release.yml` | `v*` tag | Signed release APK, verified, attached to a GitHub Release |

---

## Roadmap

- Separate Manga and Novel sections, with their own settings
- Import Manatan `.manatanbk` backups
- AniList / MyAnimeList progress tracking
- Windows desktop build
- Anime support — a possible later phase of the wider project, out of scope here

---

## Acknowledgements

Hibana interoperates with formats and extension APIs from
[Mihon](https://github.com/mihonapp/mihon),
[IReader](https://github.com/IReaderorg/IReader),
[LNReader](https://github.com/lnreader/lnreader), and
[Manatan](https://github.com/KolbyML/Manatan). Those projects' extension
ecosystems make this app useful; the compatibility layers here are written
against their public APIs.
