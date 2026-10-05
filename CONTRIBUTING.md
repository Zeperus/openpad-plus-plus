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
