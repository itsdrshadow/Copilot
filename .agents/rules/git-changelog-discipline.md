# Rule: Git, CHANGELOG, and Emulator Verification Discipline

Every bug fix, patch, feature addition, or removal in this project MUST:
1. Be committed to Git with an appropriate conventional commit message (`fix`, `feat`, `chore`, etc.).
2. Be documented in `CHANGELOG.md` under the corresponding section (`Added`, `Changed`, `Fixed`, `Removed`).
3. Never leave uncommitted changes or untracked files behind upon completing a task.
4. After task completion, compile/build the app, install it on the Android emulator (`adb install -r`), launch the app (`am start`), and verify it runs without crashing.
