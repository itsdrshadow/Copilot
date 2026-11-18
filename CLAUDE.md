# CLAUDE.md

See also `AGENTS.md` (git, changelog, emulator verification rules).

## Token Efficiency
- Never read build outputs, lockfiles, or generated code.
- Target specific paths/functions when searching or reading; never scan whole directories.
- Keep terminal output minimal: use `--quiet`, `--console=plain`, or pipe to `tail -n 30`.
- Do not repeat large code blocks in responses; show only changed lines or unified diffs.

## Kotlin & Gradle Fast Builds
- Always build/test with caches: `./gradlew <task> --build-cache --configuration-cache --parallel`.
- Never run `clean` unless explicitly requested.
- Compile/test only the targeted module or test class, e.g.
  `./gradlew :module:testDebugUnitTest --tests "ClassName"` — avoid full project builds.

## Workflow
- Before multi-file edits, state a brief 2-3 line plan.
- Wait for confirmation if architectural changes are needed.
- **Versioning & Git Push:** After completing any change, bump the version per SemVer
  (`PATCH` = fixes, `MINOR` = features, `MAJOR` = breaking; also `versionCode` +1 on Android).
  Stage changes, commit with a concise conventional message (`fix:`, `feat:`, `chore:`),
  and `git push` to the current branch — confirm with the user first if on `main`/`master`.
