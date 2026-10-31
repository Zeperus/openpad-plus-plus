# Contributing

Thanks for helping. openPad++ values simplicity, reliability, Markdown portability, offline use and maintainability.
Please do not add features merely because other note apps have them.

## Ground rules

- No networking, ads, analytics, accounts or trackers. Adding a permission needs a documented reason in the README/docs.
- Note content lives in `.md` files, never only in a database. Databases/settings hold metadata only.
- Code that can lose user data (storage, autosave, Markdown round trips, trash) must have unit tests.
- Do not disable tests or suppress lint to get a green build.

## Workflow

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug   # must pass before every PR
```

- Small, focused commits using Conventional Commit prefixes (`feat:`, `fix:`, `test:`, `docs:`, `chore:`).
- Open a pull request against `main`; CI runs build, unit tests and lint.
- By contributing you agree your work is licensed GPL-3.0-or-later.

## Targeted test loops

A change should only need the tests of what it touches. The JVM tests mirror the source packages
(`app/src/main/kotlin/.../<pkg>` <-> `app/src/test/kotlin/.../<pkg>`):

```sh
./gradlew testDebugUnitTest --tests "io.github.zeperus.openpad.editor.*"     # editor/ (also run markdown.* when serialization changes)
./gradlew testDebugUnitTest --tests "io.github.zeperus.openpad.data.*"       # storage
./gradlew testDebugUnitTest --tests "io.github.zeperus.openpad.ui.*"         # view model
./gradlew testDebugUnitTest -Popenpad.fullProperties=true                    # full random/property counts, as CI runs them
```

Random/property tests run a fifth of their cases locally; CI always runs the full count (`-Popenpad.fullProperties=true`), and the seeds
are deterministic, so a CI failure can be reproduced locally with the same flag.

UI (instrumented) tests run on GitHub, not on a local emulator: start the `Instrumented tests` workflow by hand with `testClass` set to one
fully qualified class (e.g. `io.github.zeperus.openpad.ExternalFlowTest`). A run for one class never cancels the full run of a push.
Before a release the full suite must be green.
