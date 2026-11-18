# SELFACS

SELFACS is an independent Android mobile IDE based on [Android Code Studio](https://github.com/AndroidCSOfficial/android-code-studio), itself derived from AndroidIDE.

## Project status

Bootstrap stage. The upstream Android source has not yet been imported or built. The repository will use a pinned upstream snapshot, preserving license, attribution, modules, native binaries and submodules.

- Upstream branch: `dev`
- Upstream snapshot: `24cf7fe1133c35ca3e18a8e30128435482e07709`
- Native Kotlin/Java + Gradle; app module `core:app`
- Initial branding: SELFACS; package migration deferred until terminal/toolchain compatibility is audited.
- Initial build: unsigned-for-release, debug-signed APK for ARM64 and ARMv7.

## Milestones

1. Import source and validate the baseline APK.
2. Independent branding, updater, application ID and toolchain compatibility.
3. Improve Android project editor, Gradle, SDK/JDK manager and Git integration.
4. Secure multi-provider AI coding agent and user-controlled API keys.
5. Flutter/Dart editing and optional remote builds.
6. APK analysis, performance, device tests and release signing.

## License

Derived Android Code Studio code is GPL-3.0-or-later. Original copyright, notices, third-party licenses and corresponding source must be preserved. SELFACS is not an official Android Code Studio release.
