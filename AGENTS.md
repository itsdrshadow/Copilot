# Project Rules & Android Kotlin Workflow

## 1. Git, Tagging & Changelog
- **Commit & Build Check**: Verify compilation before committing. Commit every verified change using conventional commits (`feat`, `fix`, `refactor`, `chore`, `docs`). Never leave completed work uncommitted.
- **Release Tags**: Create an annotated tag (`git tag -a vX.Y.Z -m "Release vX.Y.Z"`) on every `versionName` bump or milestone.
- **CHANGELOG Sync**: Update `CHANGELOG.md` (`### Added`, `### Changed`, `### Fixed`, `### Removed`) for every change, keeping it strictly synced with `app/build.gradle.kts`.

## 2. Fast Gradle & Kotlin Optimization
- **Use Caches**: Always run incremental builds with build & configuration caches enabled (`./gradlew assembleDebug --build-cache --configuration-cache`). Never run `clean` unless resolving a corrupted cache or dependency bug.
- **Fast Compile Check**: Use `./gradlew compileDebugKotlin --build-cache` for quick syntax/type verification before full APK packaging.
- **Formatting**: Run `ktlint --format <changed_files>` after modifying `.kt`/`.kts` files.

## 3. Emulator Verification
- Install (`adb install -r app/build/outputs/apk/debug/app-debug.apk`), launch (`adb shell am start -n <package>/<activity>`), and check `adb logcat -d -s AndroidRuntime:E` to confirm zero crashes.

## 4. Windows CLI & Context Rules
- **Fast CLI**: Prefer `sg` (ast-grep) for Kotlin structural search/refactors, `rg` for text search, `fd` for files, and `just` if a `justfile` exists. Avoid PowerShell `Get-ChildItem`/`Select-String` and CMD `dir`/`findstr`.
- **Context Discipline**: Read targeted line ranges, batch parallel calls, and ignore `build/`, `.gradle/`, `.git/`, `.cxx/`, `*.apk`, `*.aab`.
- **Shell**: Chain dependent commands in one call, keep output concise, and always pass non-interactive flags (`-y`, `--yes`, `--no-input`).