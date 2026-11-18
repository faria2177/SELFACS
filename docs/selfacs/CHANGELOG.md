# SELFACS changelog

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
