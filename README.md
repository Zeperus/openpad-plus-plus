# openPad++

> openPad++ is a simple, local-first Android Markdown notepad. No ads, no accounts, no telemetry, no clutter.

Inspired by the simplicity of Notepad++, built for Android. Your notes are plain `.md` files that you own.

## Principles

- Free and open source (GPL-3.0-or-later)
- No advertisements, accounts, subscriptions, telemetry or trackers
- Local-first and fully offline — the app requests **no permissions** (not even `INTERNET`)
- Standard Markdown files instead of a proprietary note format
- Fast startup, minimal UI

## Status

**Alpha (`0.1.0-alpha.6`, test build).** Notes are edited as formatted text (headings, bold/italic/strike, lists,
checklists incl. a Smart Checklist mode, quotes, code, links, tables), native text selection across paragraphs, lists and checklists (drag the system's own handles) with copy / cut / copy as Markdown, search, simple folders, English and German UI (selectable in Settings) and saved as plain Markdown; tabs, Favorites, Recent, Trash, external `.md` files
(Open with / file picker) and Share work. Keep backups of important notes while testing, and see
[docs/alpha-test-checklist.md](docs/alpha-test-checklist.md). See [docs/roadmap.md](docs/roadmap.md) for the milestones and
[CURRENT_TASK.md](CURRENT_TASK.md) for the exact state of work.

## Building

Requirements: JDK 17 and the Android SDK (platform 37, build-tools 36). Android Studio is optional.

```sh
export ANDROID_HOME=~/Android/Sdk      # or create local.properties with sdk.dir=...
./gradlew assembleDebug                 # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug   # unit tests + Android lint
```

## Permissions

openPad++ requests **no permissions**: no `INTERNET`, no storage permissions. (Android lists one signature-level
`...DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that the AndroidX libraries add automatically; it is private to the
app.) Cloud backup of app data is disabled. External files are accessed through Android's file picker / "Open with" (Storage Access
Framework), never through broad file access. Sharing uses a non-exported FileProvider limited to a cache folder.

## Tests

```sh
./gradlew testDebugUnitTest lintDebug     # JVM unit tests + lint (run in CI)
./gradlew connectedDebugAndroidTest       # UI tests via Android Test Orchestrator; needs an emulator/device
                                          # (also runs in the separate `Instrumented tests` CI workflow)
```

## Documentation

- [Architecture](docs/architecture.md)
- [Editor design](docs/editor.md)
- [Storage and data safety](docs/storage.md)
- [Roadmap](docs/roadmap.md)
- [Alpha test checklist](docs/alpha-test-checklist.md)
- [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
