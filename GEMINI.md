# Project Rules & Development Discipline

## 1. Mandatory Git Version Control Discipline
- **Strict Requirement**: Koi bhi bug fix ho, patch ho, feature add ho ya remove ho — sabhi changes ke liye Git me commit karna anivarya (mandatory) hai.
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
