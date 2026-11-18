# SELFACS changelog

## Next

- Fixed: update check failing with "GitHub rate limit reached" on mobile networks. The app now reads a static update feed and only falls back to the GitHub API.
- Fixed: Android projects failed to sync with "Null extracted folder for artifact logger-runtime.aar". The logger runtime is now bundled, and it is never injected when unavailable.
- SELFACS branding across the app (all languages). About now links to SELFACS GitHub issues and credits the upstream Android Code Studio project.
- Privacy: usage statistics are no longer sent to the upstream project's server (disabled entirely).
- AI panel: History button keeps your requests and answers per project (stored privately on the device, not in the project). The last answer is shown again when you reopen the editor.
- Fixed: in-app update download stopped with "Animators may only be run on Looper threads".
- AI panel: new "Fix last build error" mode uses the last failed build automatically (no pasting), reads the affected files and proposes a fix for review.
- After an approved fix, SELFACS offers to rebuild the same task; if it still fails, the AI can try again with the new errors (up to 3 attempts, each step needs your approval).

## v0.1.2

- New in-app updater: update prompt on launch, Updates screen, verified download and install.
- AI agent can read files, list folders and search the project before answering.
- Signed with the SELFACS release key. Future updates appear automatically.

## Development baseline (not a release)

- Imported the pinned Android Code Studio source and preserved upstream licenses.
- Added independent launcher and About branding.
- Isolated update metadata from upstream. No downloadable release is advertised until a signed SELFACS release is verified.
- Existing toolchain package names remain unchanged pending compatibility tests.

## Workspace and diagnostics

- Responsive dashboard actions, portrait and landscape.
- Local read-only toolchain inventory with a Manage tools shortcut.
- Fix recycled action long-click state.
- About website points to SELFACS; upstream toolchain endpoints stay intact.

## Additional AI providers

- Groq, OpenRouter, Z.AI/GLM and Alibaba Qwen use an OpenAI-compatible HTTP transport and user-entered model IDs.
- Configure from editor AI Settings. New provider credentials are encrypted via Android Keystore-backed storage. No API keys or live requests are bundled.
- Scope new provider file writes to the current project; use bounded source context, basic secret filtering, no redirect credential forwarding. Filtering is heuristic, not a guarantee against secret exposure.
- Existing provider credentials retain upstream storage; summaries are masked. Live provider and APK validation still pending.

- Added connection test with a minimal prompt (no project context); removed cached credential use so clearing keys prevents new requests.

## Review-first AI workflow

- Mandatory before/after review and approval for manager-driven proposed edits; strict fenced complete-file parser, bounded snapshot, sensitive-path and conflict checks.
- Atomic per-file writes, backup, stop after first failure, conflict-aware undo.
- Read-only ask, code review, project analysis and pasted build-log modes. No automatic command execution or build.
- Unit tests added; APK and device verification pending.

## Flutter & Dart Studio

- Separate private source workspace and Sora text editor, Flutter starter with pubspec/main.dart/widget test, project/file picker, save and new file.
- Bounded ZIP import/export, path validation, conflict checks, common sensitive/generated file exclusions.
- Explicit GitHub Actions template generation for Flutter analysis, tests and debug APK. Export and push to a separate repository manually; no Flutter SDK bundled, no direct Actions dispatch, no Dart analysis server yet.
- Workspace unit tests included; Android integration and device testing pending.

## GitHub Studio

- Added encrypted fine-grained personal access token storage using Android Keystore-backed EncryptedSharedPreferences.
- Added GitHub account and repository listing, remote branch browsing, workflow listing and manual workflow dispatch, recent run history, artifact listing and ZIP download.
- Added GitHub Studio entry in the Git client and a standalone entry when no local Git repository is initialized.
- Existing JGit local clone/commit/push/pull/branch features remain available. Repository creation opens GitHub's official web flow; this app does not create repositories through an over-privileged token.
- Token is never written to logs or forwarded to artifact redirect hosts. API permissions depend on the user's fine-grained token. Android build and device verification pending.
