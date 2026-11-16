# Project Rules & Development Discipline

## 1. Mandatory Git Version Control & Tagging Discipline
- **Strict Requirement**: Koi bhi bug fix ho, patch ho, feature add ho ya remove ho — sabhi changes ke liye Git me commit karna anivarya (mandatory) hai.
- **Git Tagging Requirement**: Har version release, bump (`versionName`) ya significant milestone ke liye matching Git tag (`vX.Y.Z`) create karna mandatory hai (e.g. `git tag -a v1.23.1 -m "Release v1.23.1"`). Kabhi bhi tag creation miss mat karo.
- **No Uncommitted Work**: Kabhi bhi completed ya verified task ko uncommitted ya untracked state me mat chhodo. Har change ko proper conventional commit ke sath save karo (`feat`, `fix`, `refactor`, `chore`, `docs`).
- **Build Verification**: Commit karne se pehle compile ya build check jarur verify karo.

## 2. Mandatory CHANGELOG.md Synchronization
- **Strict Requirement**: Har ek bug fix, patch, added feature ya removed feature ke liye `CHANGELOG.md` me entry update karna lazmi hai.
- Format Keep a Changelog guidelines ke hisaab se maintain rahega:
  - `### Added`
  - `### Changed`
  - `### Fixed`
  - `### Removed`
- Version increment hone par `app/build.gradle.kts` aur `CHANGELOG.md` dono synchronised rahenge.

## 3. Mandatory Emulator Installation & Launch Verification
- **Strict Requirement**: Task complete hone ke baad build ko Android emulator me install (`adb install -r`) karna aur app ko open/launch (`am start`) karke verify karna anivarya (mandatory) hai.
- Launch ke baad verify karo ki process active hai aur koi fatal crash nahi hai.

## 4. Global Execution & Tooling Rules (Windows)

### 4.1. Fast CLI Tools First (Avoid Slow Built-in Commands)
Always prefer installed high-speed CLI binaries over default PowerShell (`Get-ChildItem`, `Select-String`) or CMD (`dir`, `findstr`) commands:
- **Structural Code Search & Refactoring:** Use `sg` (`ast-grep`) when searching for function definitions, class patterns, imports, or performing multi-file code refactors.
- **Text & File Search:** Use `rg` (`ripgrep`) for text searches and `fd` for file name lookups if available; otherwise use `sg` or targeted file reads. Always exclude `build/`, `.gradle/`, `.dart_tool/`, `node_modules/`, and `.git/`.
- **Task & Build Automation:** If a `justfile` exists in the workspace root, inspect it and run `just <recipe>` instead of typing long multi-step build/test commands.

### 4.2. File Reading & Context Efficiency
- Never read entire large files when only a specific function or section is needed—use line ranges or search patterns first.
- Batch independent file reads and independent terminal commands in parallel instead of executing them sequentially.
- Ignore generated artifacts, build caches, and binary outputs (`*.apk`, `*.aab`, `.cxx/`, `build/`, `.gradle/`).

### 4.3. Kotlin & Android Fast Workflow
- **Formatting & Linting:** After generating or modifying `.kt` or `.kts` files, run `ktlint --format <changed_files>` to ensure clean formatting without launching a heavy IDE inspection.
- **Builds:** Use `./gradlew` with incremental flags rather than clean builds (`clean`) unless explicitly required by a dependency or cache issue.

### 4.4. Terminal Execution Standards (Windows)
- Chain related commands cleanly in a single shell invocation when order matters, and keep command output concise (avoid verbose debug flags unless diagnosing an error).
- Prefer non-interactive CLI flags (`--yes`, `-y`, `--no-input`) so terminal commands never hang waiting for user prompts.
