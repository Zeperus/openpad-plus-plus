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

Early development. See [docs/roadmap.md](docs/roadmap.md) for the milestones and
[CURRENT_TASK.md](CURRENT_TASK.md) for the exact state of work.

## Building

Requirements: JDK 17 and the Android SDK (platform 37, build-tools 36). Android Studio is optional.

```sh
export ANDROID_HOME=~/Android/Sdk      # or create local.properties with sdk.dir=...
./gradlew assembleDebug                 # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug   # unit tests + Android lint
```

## Documentation

- [Architecture](docs/architecture.md)
- [Editor design](docs/editor.md)
- [Storage and data safety](docs/storage.md)
- [Roadmap](docs/roadmap.md)
- [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
