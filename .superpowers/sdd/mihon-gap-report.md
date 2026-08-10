# Hibana ↔ Mihon parity gap report

Audit date: 2026-08-10. Read-only audit — no source files were modified.

**Mihon reference:** `mihonapp/mihon@main`, enumerated live from GitHub, not from memory.
Directory listed via
`https://api.github.com/repos/mihonapp/mihon/contents/app/src/main/java/eu/kanade/presentation/more/settings/screen?ref=main`,
which returned: `SettingsMainScreen.kt`, `SettingsAppearanceScreen.kt`, `SettingsLibraryScreen.kt`,
`SettingsReaderScreen.kt`, `SettingsDownloadScreen.kt`, `SettingsTrackingScreen.kt`,
`SettingsBrowseScreen.kt`, `SettingsDataScreen.kt`, `SettingsSecurityScreen.kt`,
`SettingsAdvancedScreen.kt`, `SettingsSearchScreen.kt`, plus `about/`, `advanced/`, `appearance/`,
`browse/`, `data/`, `debug/` subpackages. Each screen file was fetched raw and its
`MR.strings.*` preference keys extracted, so every Mihon row below is grounded in a real
preference key in the current source.

**Hibana reference:** `C:\Users\Iresh\Downloads\Mihon_like\.worktrees\issues`,
package root `app/src/main/kotlin/com/opennovel/reader`. Files read in full:
`ui/screens/SettingsScreen.kt`, `ui/screens/SettingsSectionScreen.kt`,
`data/SettingsRepository.kt`, `data/AppSection.kt`, `ui/RootNav.kt`, `data/db/Entities.kt`;
string-level extraction over `ui/screens/{Library,Updates,History,Browse,Extensions,NovelDetail,
Reader,Downloads,Stats,Migration,More,GlobalSearch,Upcoming,SourceBrowse}Screen.kt`;
plus a whole-package keyword sweep (case-insensitive `grep -ri` over the package root) for the
~100 terms named in the "Search run" column.

---

## Section A — Present in Mihon, missing in Hibana

Severity key: **High** = a Mihon user notices on day one. **Medium** = missed within a week of
regular use. **Low** = obscure, power-user, or platform trivia.

### A.1 High

| # | Mihon feature / setting | Where it lives in Mihon | What it does | Search run in Hibana | Sev |
|---|---|---|---|---|---|
| 1 | Tracker integration (MAL, AniList, Kitsu, Shikimori, Bangumi, MangaUpdates, enhanced/Komga-style trackers), login/logout, `pref_auto_update_manga_sync`, auto-update on mark-read | `SettingsTrackingScreen.kt`; per-entry tracking sheet on the manga screen | Syncs read chapter counts, scores and status to external services; auto-pushes progress when a chapter is marked read | `tracker`, `Tracking`, `MyAnimeList`, `AniList` → 0 hits outside a single unrelated comment | **High** — tracking is one of the three things Mihon users configure on first launch, and there is no substitute anywhere in Hibana. |
| 2 | Per-source extension preference screens (a source's own settings: domain mirror, login, preferred quality, chapter language) | Browse → source ⚙ → `SourcePreferencesScreen`; extension detail screen | Surfaces the `SharedPreferences`-backed settings an extension declares, without which many sources simply do not work | `sourcePreference`, `getPreference`, `PreferenceScreen` → only internal `IReaderPreferenceStore`/`IReaderDependencyFactory` plumbing, no UI; `ui/screens/ExtensionInfoScreen.kt` has no preference rendering | **High** — a large share of Keiyoushi sources need a mirror/domain preference set before they return anything, so without this UI those extensions look broken. |
| 3 | Reader navigation: tap-zone layouts (`pref_viewer_nav`, L-shaped/Kindle/Edge/Right-and-left), invert tapping, volume-key paging + inverted (`pref_read_with_volume_keys`), long-tap action (`pref_read_with_long_tap`), vertical navigator side/height | `SettingsReaderScreen.kt`, "Reader navigation"/"Pager"/"Webtoon" groups | Defines how taps and hardware keys move between pages | `volumeKey`, `volume`, `tapZone`, `TapZone`, `navigation` over the package; `ui/screens/ReaderScreen.kt` string dump shows only font/spacing/theme/page-layout controls | **High** — page-turning by tap zone or volume keys is the single most-used reader interaction in Mihon, and Hibana currently only supports swipe/scroll. |
| 4 | Per-entry chapter list filter / sort / display, and "set as default for all entries" | Manga screen → filter icon (`ChapterSettingsDialog`) | Filter by unread / downloaded / bookmarked, sort by source order / chapter number / upload date, ascending-descending, hide/show scanlator | `grep -niE "sort\|filter\|scanlator"` over `ui/screens/NovelDetailScreen.kt` → **zero** matches; chapter list is a fixed order with no controls | **High** — on any long-running series the first thing a user does is flip the chapter list to descending or filter to unread. |
| 5 | Download/backup storage location picker (`pref_storage_location`, SAF tree URI) and `save_chapter_as_cbz` | `SettingsDataScreen.kt`, `SettingsDownloadScreen.kt` | Puts downloads and automatic backups in a user-chosen folder, in a format other apps can read | `storageLocation`, `OpenDocumentTree`, `SAF`, `cbz`, `zip`, `epub`; `SettingsSectionScreen.kt` Downloads section carries a `TODO` and states downloads live in app-private storage | **High** — downloads vanishing on uninstall and being unreachable by any file manager is an immediate, visible regression versus Mihon. |
| 6 | Storage usage readout + clear chapter cache + auto-clear chapter cache (`pref_storage_usage`, `pref_clear_chapter_cache`, `pref_auto_clear_chapter_cache`) | `SettingsDataScreen.kt` | Shows cache size and lets the user reclaim space | Row exists in `SettingsSectionScreen.kt` "Storage" but is hard-wired `enabled = false` with a `TODO`; also `pref_clear_database` equivalent "Clear database" is `enabled = false` | **High** — two settings rows are visibly present but permanently greyed out, which reads as a broken app rather than a missing feature. |
| 7 | Library-update and new-chapter notifications (progress notification, "N new chapters" summary, per-entry tap-through), plus `pref_library_update_show_tab_badge` | Mihon's `LibraryUpdateNotifier` / `SettingsLibraryScreen.kt` | Tells the user a background sweep ran and what it found | `notif`, `Notification` → only `NovelReaderApp.kt` (TTS + downloads channels) and `tts/TtsService.kt`; `grep -niE "notif\|foreground"` over `update/LibraryUpdateWorker.kt` → 0 hits | **High** — scheduled updates that produce no notification are indistinguishable from updates that never ran. |

### A.2 Medium

| # | Mihon feature / setting | Where it lives in Mihon | What it does | Search run in Hibana | Sev |
|---|---|---|---|---|---|
| 8 | Reader image handling: `pref_crop_borders`, `pref_image_scale_type`, `pref_zoom_start`, `pref_landscape_zoom`, `pref_navigate_pan`, `pref_double_tap_zoom`, `pref_webtoon_side_padding`, `pref_webtoon_disable_zoom_out`, `pref_hide_threshold` | `SettingsReaderScreen.kt` | Controls how a page is fitted, cropped, zoomed and padded | `cropBorders`, `crop`, `scaleType`, `sidePadding`, `webtoonSidePadding`, `zoom` → no reader-side hits | Medium |
| 9 | Reader colour/brightness filter, grayscale, invert (reader bottom sheet) | Mihon reader settings sheet (`ReaderSettingsSheet` / colour filter tab) | Per-reader custom brightness and RGBA tint for night reading | `grayscale`, `colorFilter`, `brightness`, `Brightness` → 0 hits | Medium |
| 10 | Screen rotation / orientation lock (`pref_rotation_type`) and `pref_cutout_short` | `SettingsReaderScreen.kt` | Locks the reader to portrait/landscape/free, and lets pages draw into the display cutout | `orientation`, `rotation`, `Rotation`, `cutout` → 0 hits | Medium |
| 11 | Reader chrome: `pref_show_page_number`, `pref_page_transitions`, `pref_always_show_chapter_transition`, `pref_double_tap_anim_speed`, `pref_show_reading_mode`/`pref_show_navigation_mode` on chapter open | `SettingsReaderScreen.kt` | Page counter, inter-page animation, and the "next: chapter N" transition card | `pageNumber`, `showPageNumber`, `pageTransition`, `chapter transition` → 0 hits | Medium |
| 12 | Reading-flow skips: `pref_skip_read_chapters`, `pref_skip_filtered_chapters`, `pref_skip_dupe_chapters` | `SettingsReaderScreen.kt` | When paging past a chapter end, skip chapters the user has read/filtered/duplicated | `skipRead`, `skipFiltered`, `skip` → 0 hits | Medium |
| 13 | Library update scope rules: `pref_update_only_started`, `pref_update_only_non_completed`, `pref_update_only_in_release_period`, `pref_library_update_smart_update`, include/exclude categories from the sweep | `SettingsLibraryScreen.kt` | Keeps a large library's update sweep cheap and polite to sources | `updateOnly`, `smart update`, `excludeCategories` → 0 hits; `SettingsSectionScreen.kt` "Global update" only offers cadence + Wi-Fi | Medium |
| 14 | Update restrictions beyond Wi-Fi: `charging`, `network_not_metered` (`pref_library_update_restriction`) | `SettingsLibraryScreen.kt` | Defers sweeps until the device is charging | `charging` → 0 hits; only `updateOnWifiOnly` exists in `data/SettingsRepository.kt` | Medium |
| 15 | Chapter swipe actions in the chapter list (`pref_chapter_swipe_start` / `_end`: bookmark, mark read, download) | `SettingsLibraryScreen.kt` | Configurable left/right swipe on a chapter row | `chapterSwipe`, `swipe` → swipe exists on library covers only, not on chapter rows (`NovelDetailScreen.kt` has no swipe handler) | Medium |
| 16 | Delete-after-read granularity: `pref_remove_after_marked_as_read` with an N-chapters-behind option, `pref_remove_bookmarked_chapters`, `pref_remove_exclude_categories` | `SettingsDownloadScreen.kt` | Keeps the last N read chapters, never deletes bookmarked ones, exempts categories | `removeAfterRead` exists as a plain boolean in `data/SettingsRepository.kt`; `pref_remove_bookmarked`, `excludeCategories` → 0 hits | Medium |
| 17 | Auto-download while reading / download ahead (`download_ahead`, `auto_download_while_reading`), `pref_download_new_unread_chapters_only`, per-category auto-download | `SettingsDownloadScreen.kt` | Keeps N chapters ahead of the reading position downloaded | `downloadAhead`, `download ahead` → 0 hits | Medium |
| 18 | Split tall images / concurrent *pages* (`split_tall_images`, `pref_download_concurrent_pages`) | `SettingsDownloadScreen.kt` | Splits very tall webtoon strips for the pager; separate page-level concurrency knob | `splitTall`, `concurrentPages` → 0 hits (Hibana has one `concurrentDownloads` knob, 1–5) | Medium |
| 19 | App lock detail: `lock_with_biometrics`, timeout (`lock_never` / `lock_always` / `lock_when_idle`), `hide_notification_content` | `SettingsSecurityScreen.kt` | Biometric unlock with an idle grace period, and hiding titles in notifications | `biometric`, `Biometric`, `lockWhenIdle`, `notificationContent` → 0 hits; Hibana has a single `appLockEnabled` boolean | Medium |
| 20 | Advanced network: `pref_dns_over_https`, `pref_user_agent_string` + reset, `pref_clear_webview_data` | `SettingsAdvancedScreen.kt` | Bypasses ISP DNS blocks and spoofs UA — the standard fix when a source stops loading | `DnsOverHttps`, `doh`, `userAgent`, `webviewData` → 0 hits (a "Clear cookies" action does exist) | Medium |
| 21 | Extension installer choice (`ext_installer_pref`: legacy / package installer / Shizuku / private) and `ext_revoke_trust` (revoke *all* trust at once) | `SettingsAdvancedScreen.kt` | Silent/root-free installs via Shizuku; a global panic-button for extension trust | `Shizuku` → 0 hits; `revokeTrust` → 0 (per-extension "Remove trust" exists in `ExtensionsScreen.kt`) | Medium |
| 22 | Settings search (`SettingsSearchScreen.kt`) | Settings top bar magnifier | Full-text search across every preference in the tree | `SettingsScreen.kt` / `SettingsSectionScreen.kt` contain no search field; `grep "action_search"`-equivalent → 0 | Medium |
| 23 | Per-category display settings (`categorized_display_settings`) and category-scoped sort | `SettingsLibraryScreen.kt` + library sheet | Lets each shelf keep its own grid/list mode and sort | `categorized`, `categor` → categories exist, but display mode is stored once per `AppSection` in `data/AppSection.kt`, not per category | Medium |
| 24 | Library sort by "Last read" and "Chapter fetch date" | Mihon library sort sheet | Two sorts Mihon users rely on for "what was I reading" | `enum class LibrarySort` in `ui/ViewModels.kt` has Title / Total chapters / Unread / Latest chapter / Date added / Random — no last-read, no fetch date | Medium |
| 25 | Local source (a device folder as a source, `LocalSource`) | Mihon browse list, always present | Reads sideloaded CBZ/EPUB from local storage | `LocalSource`, `local source`, `cbz`, `epub` → 0 hits | Medium |
| 26 | Custom cover / edit cover, and share-entry | Manga screen overflow | Replace a bad cover; share a link to the entry | `editCover`, `setCover`, `Edit cover` → 0; sharing exists for *sources* (`BrowseScreen.kt`) but not for entries | Medium |
| 27 | `pref_hide_in_library_items` (hide already-in-library results while browsing) | `SettingsBrowseScreen.kt` | Declutters browse/global search for a big library | `hideInLibrary` → 0 hits (Hibana shows an "In library" badge instead) | Medium |
| 28 | Source-level search filters (genre/status/sort filter sheet returned by the extension) | Mihon browse screen filter button | Uses the source's own `getFilterList()` to build a search form | `getFilterList` appears only in `extension/MihonSourceAdapter.kt` (fetched then discarded); `ui/screens/SourceBrowseScreen.kt` has Popular/Latest and a plain text search, no filter UI | Medium |
| 29 | Onboarding flow (`pref_onboarding_guide`) with storage/notification permission steps | `SettingsAdvancedScreen.kt` + `onboarding/` | First-run guided setup | `onboarding`, `Onboarding` → 0 hits | Medium |

### A.3 Low

| # | Mihon feature / setting | Where it lives in Mihon | What it does | Search run in Hibana | Sev |
|---|---|---|---|---|---|
| 30 | `pref_tablet_ui_mode` | `SettingsAppearanceScreen.kt` | Forces/disables the two-pane tablet layout | `tabletUi`, `TabletUi` → 0 | Low |
| 31 | `pref_date_format` + `pref_relative_format` (Today/Yesterday vs absolute) | `SettingsAppearanceScreen.kt` | User-chosen timestamp format | `dateFormat`, `relativeTime` → relative labels are hard-coded in `HistoryScreen.kt`/`NovelDetailScreen.kt`, not configurable | Low |
| 32 | Named app themes (Tako, Yin & Yang, Green Apple, …) beyond light/dark | `SettingsAppearanceScreen.kt` (`pref_app_theme`) | Preset palettes | `ThemeMode` in `data/SettingsRepository.kt` = LIGHT/DARK/SYSTEM/SEPIA/BLACK, plus dynamic colour | Low |
| 33 | `pref_flash_page` family (flash duration/interval/style) | `SettingsReaderScreen.kt` | Anti-burn-in flash for OLED long-strip reading | `flash` → 0 | Low |
| 34 | `pref_dual_page_split`, `pref_dual_page_invert`, `pref_page_rotate`, `pref_page_rotate_invert` | `SettingsReaderScreen.kt` | Splits wide double-page spreads and rotates landscape pages | `dualPage`, `pageRotate` → 0 (Hibana has `PageLayout` SINGLE/DOUBLE/DOUBLE_EXCEPT_COVER only) | Low |
| 35 | `pref_mark_duplicate_read_chapter_read` (existing / new) | `SettingsLibraryScreen.kt` | Auto-marks duplicate chapter numbers read across scanlators | `duplicate` → hits are migration-related only | Low |
| 36 | `pref_hide_missing_chapter_indicators` | `SettingsLibraryScreen.kt` | Toggle for the gap markers | `data/ChapterGaps.kt` implements the indicator; only the *toggle* is missing | Low |
| 37 | Library export as CSV/list (`library_exported`, title/author/artist picker) | `SettingsDataScreen.kt` | Exports a plain list of the library | `csv`, `shareLibrary` → 0 | Low |
| 38 | `pref_refresh_library_covers`, `pref_reset_viewer_flags`, `pref_update_library_manga_titles` | `SettingsAdvancedScreen.kt` | Targeted maintenance actions | Hibana has one broad "Refresh library metadata"; the three narrow variants → 0 hits | Low |
| 39 | `pref_verbose_logging`, `pref_debug_info` screen, `pref_manage_notifications`, `pref_disable_battery_optimization`, Don't-kill-my-app link | `SettingsAdvancedScreen.kt`, `debug/` | Diagnostics and OEM battery-killer guidance | `verboseLogging`, `batteryOptim`, `debug info` → 0 (Hibana has "Dump crash logs" + a device info block) | Low |
| 40 | `pref_invalidate_download_cache` | `SettingsAdvancedScreen.kt` | Rebuilds the downloaded-chapter index | `invalidate`, `downloadCache` → 0 | Low |
| 41 | `pref_disallow_non_ascii_filenames`, `pref_hardware_bitmap_threshold`, `pref_always_decode_long_strip_with_ssiv_2`, `pref_display_profile` (ICC) | `SettingsAdvancedScreen.kt` | Device/filesystem workarounds and colour management | `hardwareBitmap`, `displayProfile`, `non_ascii` → 0 | Low |
| 42 | `pref_create_folder_per_manga` | `SettingsReaderScreen.kt` (download naming) | One folder per series in the download tree | `createFolder`, `folder per` → 0; moot until gap #5 lands | Low |
| 43 | Per-entry notes | Mihon manga screen | Freeform note attached to an entry | `notes`, `Notes` → 0 | Low |
| 44 | About screen with changelog / update check / licences links | `about/` | In-app version check and release notes | `checkForUpdate`, `GithubRelease` → 0; `MoreScreen.kt` shows a static About dialog | Low |

---

## Section B — Present in both (checklist, no detail)

- [x] Library tab with categories (create / rename / delete / reorder-by-shelf), default-category preference
- [x] Library combined **Filter / Sort / Display** bottom sheet, tri-state filters (downloaded, unread, started, completed) with clear-filters
- [x] Library sorts: alphabetical, total chapters, unread count, latest chapter, date added, random + reverse toggle
- [x] Library display modes: comfortable grid, compact grid, list
- [x] Cover badges: master switch, unread count, downloaded count, source language
- [x] Library multi-select (select all, invert, set categories, delete, migrate), search, pull-to-refresh, open random entry
- [x] Updates tab grouped by day, with download / bookmark actions and last-refresh stamp
- [x] History tab with per-entry removal and clear-all
- [x] Browse: source list, pinned sources, Popular / Latest listings, per-source overflow (WebView, open in browser, share, clear cookies), language filter
- [x] Global search across all sources in the active section
- [x] Extensions: installed / update-available / available lists, install, update, update-all, language filter, NSFW flag, per-extension trust and trust revocation, extension detail screen
- [x] Extension repositories screen (add / enable / remove stores)
- [x] Downloads queue screen: queue + downloaded, cancel, cancel-all, retry, delete, multi-select
- [x] Auto-download new chapters; remove after read; concurrent-download limit
- [x] Entry screen: add/remove from library, chapter list, multi-select (read/unread, bookmark, download, delete), refresh chapters, migrate
- [x] Reader with paged LTR / paged RTL / paged vertical / webtoon / continuous-vertical modes, keep-screen-on, fullscreen, progress persistence
- [x] Migration flow (source → source, with what-to-carry-over options and match review)
- [x] Backup / restore in Mihon's `.tachibk` protobuf+gzip format; automatic-backup frequency preference
- [x] Global library update scheduling (interval, Wi-Fi only, weekly/monthly time-of-day)
- [x] Missing-chapter gap indicators
- [x] Incognito mode; secure screen (FLAG_SECURE); app lock
- [x] NSFW source toggle; extension auto-update toggle
- [x] Appearance: theme mode (incl. pure-black), Material You dynamic colour, app language preference
- [x] Downloaded-only mode
- [x] Statistics screen; Upcoming-releases calendar
- [x] Advanced: reset settings, clear cookies, dump crash logs, version/device info
- [x] Nine-category settings tree mirroring Mihon's (`ui/screens/SettingsScreen.kt`)

---

## Section C — Deliberate divergences (correct, not gaps)

| Hibana behaviour | Mihon behaviour | Why Hibana is right |
|---|---|---|
| App is split into two sections, **Manga & Manhwa** and **Novels** (`data/AppSection.kt`), with an app-wide switcher; Library, Browse, Extensions, Updates and History are all scoped to the active section | One undifferentiated manga library | Prose and comics are read, sourced and paginated differently. A single shelf mixing scans and novels would force every settings screen to show controls half its content cannot honour. |
| Reader settings, library display mode and badges are stored **per section** (`SectionRepository`), with an "Applies to" chip picker in Settings | Single global set | A font size is meaningless for a scan; a page-fit mode is meaningless for prose. Splitting the store is the only way to give each half sane defaults (note the different `ReadingMode` defaults per section). |
| Four extension ecosystems (Mihon/Tachiyomi APK, Manatan APK, IReader APK, LNReader JS plugins) behind one `Ecosystem` enum, with `AppSection.of(ecosystem)` routing each to the right section | Tachiyomi APK extensions only | This is Hibana's reason to exist. It also means the Extensions and Browse lists must filter by ecosystem, which Mihon never needs to do. |
| Per-entry **content type** override (`ContentType` on `NovelEntity`, "Read this as" menu) | No such concept | A Mihon source can host a webtoon-formatted novel and IReader hosts illustrated works; deriving the reader from the source at read time would silently change how an entry renders when its extension updates. |
| Extra settings category **Narration & translation** (TTS speed/pitch/voice/language, OCR script, on-device translation with downloadable ML Kit packs) | No equivalent | Feature Hibana adds on purpose; OCR script choice is a genuine per-script quality decision for manga lettering. |
| Backups import Mihon `.tachibk` **and** Manatan `.manatanbk` | `.tachibk` only | Hibana ingests from two comic ecosystems, so it must read both migration paths. |
| Ships with **no** pre-installed sources or default repository | Ships with no sources either, but a single well-known repo ecosystem | Same posture, wider scope: with four ecosystems, silently seeding one would bias which half of the app works out of the box. |
| Categories are managed from the Library tab's overflow rather than from Settings | Settings → Library → Edit categories | Deliberate: categories are edited where the shelves they group are visible. Reachable, just relocated — treat as UX divergence, not a gap. |

---

## Section D — Not applicable

| Mihon feature | Why it does not apply |
|---|---|
| Firebase Crashlytics / Analytics opt-ins (`pref_firebase`, `onboarding_permission_crashlytics`, `onboarding_permission_analytics` in `SettingsSecurityScreen.kt`) | Hibana ships no analytics or crash-reporting SDK, so there is nothing to consent to. A toggle would be a lie. |
| Mihon's own in-app updater / release-channel picker (`about/`) | Distribution-model dependent; irrelevant until Hibana has a release channel. (The *changelog/licences* half of that screen is listed as gap #44.) |
| "Tachiyomi-branded" extension-repo defaults and Tachiyomi→Mihon migration prompts | Hibana's `ExtensionRepo` / `RepoIndexParser` layer is ecosystem-agnostic and replaces this entirely; there is no single blessed repo to migrate from. |
| `pref_show_nsfw_source` framed as a parental control with a lock | Hibana's NSFW toggle exists; Mihon's extra parental-controls framing presumes a curated source list Hibana does not have (four ecosystems, no curation). |
| Anything keyed off Mihon's `MR.strings` / moko-resources setup | Hibana uses plain Android string resources and inline literals; a like-for-like port of the resource layer is not a user-visible feature. |
| Mihon's `debug/` developer screens (bug-report info dumps tied to Mihon's build config) | `buildConfig` generation is off in Hibana (see the comment in `MoreScreen.kt`); the useful part — version/device info and logcat dump — already exists in Settings → Advanced. |

---

## Counts

| Severity | Count |
|---|---|
| High | 7 |
| Medium | 22 |
| Low | 15 |
| **Total gaps (Section A)** | **44** |

Deliberate divergences: 8. Not-applicable Mihon features: 6.
