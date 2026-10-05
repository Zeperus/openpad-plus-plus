# Architecture

Single-module Android app (`:app`), Kotlin, Jetpack Compose, Material 3. Minimum API 28.

```
io.github.zeperus.openpad
├── ui/        Compose screens, theme (no business logic)
├── domain/    Pure-Kotlin note concepts and rules (unit-testable on the JVM)
├── data/      File storage, metadata, settings, session
└── markdown/  (later) parser, document model, serializer
```

Principles: constructor injection by hand (no DI framework), no layer without a purpose, Compose-independent
domain/markdown code, build entirely from the terminal with `./gradlew`.

Toolchain: AGP 9.4 (built-in Kotlin; the `kotlin-android` plugin must not be applied), Gradle 9.8, JDK 17,
compileSdk/targetSdk 37.
