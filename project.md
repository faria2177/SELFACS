# SELFACS — Project Status & Roadmap

> **Last updated:** 2026-11-18  
> **Repository:** https://github.com/faria2177/SELFACS  
> **Branch:** `main`  
> **Status:** Active development — not yet a verified stable release

এই ডকুমেন্টে SELFACS Android Code Studio প্রকল্পে কী কী কাজ যুক্ত হয়েছে, কোনগুলো আংশিক/যাচাই-বাকি এবং পরবর্তী কাজের তালিকা রাখা হয়েছে। প্রতিটি নতুন phase, feature, bug fix, test ও release-এর পর এই ফাইল আপডেট করতে হবে।

## 1. Project overview

SELFACS হলো Android Code Studio-ভিত্তিক Android IDE। মূল লক্ষ্য হলো Android ডিভাইস থেকেই code edit, project manage, AI-assisted development, Flutter/Dart source editing এবং Git/GitHub workflow ব্যবহার করা।

- Upstream project: [Android Code Studio](https://github.com/AndroidCSOfficial/android-code-studio)
- Imported upstream baseline: `24cf7fe1133c35ca3e18a8e30128435482e07709`
- Main Android module: `core:app`
- Current upstream package namespace: `com.tom.rv2ide` (SELFACS namespace migration এখনো করা হয়নি)
- Build toolchain: Gradle wrapper 9.8.2, JDK 21, Android SDK 37
- License: GPL-3.0-or-later; upstream license notices ও attribution অক্ষুণ্ণ রাখতে হবে।

## 2. Status legend

- [x] Repository-তে implementation/কোড যোগ করা হয়েছে।
- [~] আংশিক implementation আছে; পূর্ণ workflow, integration বা device validation বাকি।
- [ ] এখনো সম্পন্ন হয়নি।
- **Verified** মানে সংশ্লিষ্ট automated build/test বা বাস্তব device test সফল হয়েছে। শুধু code commit হওয়া Verified নয়।

## 3. Completed implementation (code present)

### 3.1 Repository foundation & branding
- [x] Android Code Studio source SELFACS repository-তে import করা।
- [x] Upstream baseline commit pin করা এবং upstream import script রাখা।
- [x] SELFACS launcher/app branding ও About website metadata যোগ করা।
- [x] Upstream README ও license/attribution সংরক্ষণ করা।
- [x] Upstream update metadata আলাদা রাখা; verified signed release না হওয়া পর্যন্ত fake release/update দেখানো বন্ধ রাখা।
- [x] Signing key/secret repository থেকে বাদ; CI debug build-এ standard debug signing ব্যবহার।
- [x] CI workflow-তে source preparation, JDK 21, Android SDK 37, tests, debug APK build ও artifact upload ধাপ যোগ।

### 3.2 Dashboard & diagnostics
- [x] Responsive dashboard actions এবং portrait/landscape layout adjustments।
- [x] Local read-only toolchain inventory ও Manage tools shortcut।
- [x] Recycled action long-click state fix।

### 3.3 AI provider extensions
- [x] Groq, OpenRouter, Z.AI/GLM এবং Alibaba Qwen-এর OpenAI-compatible HTTP transport যোগ।
- [x] User-entered model ID support।
- [x] নতুন provider credentials Android Keystore-backed encrypted storage-এ রাখার ব্যবস্থা।
- [x] Minimal prompt connection test; connection test-এ project context পাঠানো হয় না।
- [x] AI write scope current project-এ সীমিত করার ব্যবস্থা, bounded context ও heuristic secret filtering।
- [x] AI manager edit-এর আগে/পরে review ও user approval flow।
- [x] Complete-file fenced parser, bounded snapshot, sensitive path/conflict checks, atomic per-file write, backup ও conflict-aware undo।
- [x] Read-only Ask, Code Review, Project Analysis এবং pasted build-log modes।
- [x] Unit test source যোগ।

**সীমাবদ্ধতা:** secret filtering heuristic; কোনোভাবেই secret leak না হওয়ার নিশ্চয়তা নয়। Existing upstream provider credential storage আলাদা থাকতে পারে। Live provider validation ও বাস্তব ডিভাইস পরীক্ষা বাকি।

### 3.4 Flutter & Dart Studio
- [x] Flutter source-এর জন্য আলাদা workspace structure।
- [x] Flutter starter project: `pubspec.yaml`, `lib/main.dart`, widget test।
- [x] Project/file picker, file open/save এবং new file।
- [x] Sora-based text editor integration।
- [x] ZIP import/export-এ path validation, bounded archive size, conflict handling এবং sensitive/generated file exclusions।
- [x] Flutter analyze/test/debug APK-এর জন্য GitHub Actions workflow template তৈরি করার ব্যবস্থা।
- [x] Workspace unit test source যোগ।

**এখনো নেই/অসম্পূর্ণ:** Android app-এর ভেতরে Flutter SDK bundled নয়; Dart Analysis Server/LSP নেই; Flutter Actions workflow সরাসরি dispatch ও build log streaming নেই; Android device-এ end-to-end test বাকি। তাই এটিকে এখনই পূর্ণ Flutter IDE বলা যাবে না।

### 3.5 Git & GitHub Studio
- [x] Existing JGit local clone/commit/push/pull/branch features রাখা।
- [x] Git client navigation এবং uninitialized repo screen থেকে GitHub Studio entry।
- [x] Fine-grained GitHub Personal Access Token connect flow।
- [x] Token Android Keystore-backed EncryptedSharedPreferences-এ সংরক্ষণ।
- [x] GitHub account/repository list, remote branch list।
- [x] Repository workflows list ও manual workflow_dispatch।
- [x] Recent Actions run history।
- [x] Workflow artifacts list ও ZIP download via Android Storage Access Framework।
- [x] GitHub-এ repository create করার official web flow link।
- [x] Token log-এ না লেখা এবং artifact signed redirect host-এ token forward না করার ব্যবস্থা।
- [x] REST raw-array parsing ও encrypted storage dependency সংক্রান্ত follow-up fix commit।

**সীমাবদ্ধতা:** Fine-grained token-এর permission অনুযায়ী কাজ করবে। Repository creation এখনো app-এর ভিতরে API দিয়ে নয়, GitHub official web flow দিয়ে হয়। Build/device verification বাকি।

## 4. Pending work — priority roadmap

### P0 — Build stability & correctness
- [x] GitHub Actions run সফল (commit `7a9c575`, run 44253742681)।
- [x] GitHub Studio compile failure (`singleLine`) ও build script error ঠিক করা।
- [ ] GitHub API workflow list ও run list-এর response parsing live API response দিয়ে যাচাই করা।
- [~] Flutter workspace ও AI edit planner unit tests CI-তে pass; GitHub validation-এর আলাদা test coverage যাচাই বাকি।
- [~] Debug APK artifact তৈরি হয়েছে; ডিভাইসে install/launch test বাকি।
- [ ] Android 10/11, Android 13+ এবং Android 16-এ smoke test।
- [ ] Low RAM/storage ও network failure handling যাচাই।
- [ ] Build status, artifact link ও test evidence এই ফাইলে লিখে রাখা।

### P1 — Android Code Studio core usability
- [ ] SELFACS নাম, icon, About, settings ও user-facing strings-এ upstream branding audit।
- [ ] Package/application ID migration পরিকল্পনা, সব hardcoded upstream references যাচাই করে ধাপে ধাপে পরিবর্তন।
- [ ] Project create/open/import/export এবং file tree workflow বাস্তব ডিভাইসে যাচাই।
- [ ] Editor tabs, syntax highlighting, search/replace, terminal, diagnostics ও language server integration regression test।
- [ ] Gradle sync/build/install/logcat workflow নির্ভরযোগ্য করা।
- [ ] Toolchain download/management, SDK/NDK/JDK availability ও storage permission UX যাচাই।
- [ ] Keyboard, touch, external keyboard এবং বিভিন্ন screen size-এ UI পরীক্ষা।

### P2 — AI Studio completion
- [ ] প্রতিটি provider-এর real connection test ও model compatibility matrix।
- [ ] Streaming response, timeout, retry, rate-limit/quota এবং network error handling।
- [ ] Provider/model selector ও usage/error status UX উন্নত করা।
- [ ] AI edit review diff, accept/reject, undo ও multi-file conflict বাস্তব project-এ পরীক্ষা।
- [ ] Sensitive file exclusion/secret filtering আরও শক্ত করা; কোনো credential prompt/log-এ না যাওয়ার test।
- [ ] AI Studio settings, chat/session persistence এবং project context correctness যাচাই।
- [ ] User approval ছাড়া কোনো command/build/run যেন না হয়—security regression tests।

### P3 — Flutter/Dart Studio completion
- [ ] Dart Analysis Server/LSP integration।
- [ ] Flutter SDK setup/detection এবং SDK version management।
- [ ] Flutter project templates ও dependency management।
- [ ] In-app Flutter analyze/test/build trigger ও live log/result।
- [ ] GitHub Actions workflow dispatch এবং run/artifact status integration।
- [ ] APK download/install flow ও build failure diagnostics।
- [ ] Flutter app emulator/device run/debug workflow—যেখানে Android environment সমর্থন করে।
- [ ] Large project ZIP import/export performance ও SAF edge-case testing।

### P4 — GitHub Studio completion
- [ ] GitHub token permission validation ও clearer least-privilege guidance।
- [ ] Repository search/filter/sort এবং pagination।
- [ ] Branch switching/checkout ও remote branch comparison।
- [ ] Commit history/diff viewer।
- [ ] Pull request list, review ও create flow।
- [ ] Workflow run detail, step logs এবং cancel/rerun controls (যথাযথ permission সাপেক্ষে)।
- [ ] Artifact size, expiry ও download error handling।
- [ ] Token revoke/disconnect এবং account switching test।
- [ ] GitHub API rate-limit handling ও pagination tests।

### P5 — Reverse Engineering Studio (planned)
- [ ] APK picker ও safe read-only APK inspection।
- [ ] Package/version/SDK/permissions/components metadata report।
- [ ] APK ZIP structure, DEX, resources, native libraries ও assets inventory।
- [ ] JADX/decompiler integration feasibility, license ও device resource usage review।
- [ ] Manifest/resource viewer এবং exportable analysis report।
- [ ] কোনো APK স্বয়ংক্রিয়ভাবে execute/install না করা; analysis sandboxing ও safety controls।

### P6 — Release readiness
- [ ] Release application ID/package migration সম্পন্ন ও tested।
- [ ] Release signing key নিরাপদভাবে configure (কখনো repository-তে commit নয়)।
- [ ] Reproducible release build ও versioning।
- [ ] GPL-3.0-or-later compliance, upstream notices এবং third-party license report।
- [ ] Privacy/security review: tokens, AI prompts, source code transmission, logs ও local storage।
- [ ] Clean install, upgrade install, uninstall/reinstall test।
- [ ] Release notes, screenshots, known limitations এবং user documentation।
- [ ] Signed APK/AAB এবং verified release tag প্রকাশ।

## 5. Current CI/build status

- Verified commit: `7a9c575` (main)
- Workflow run: [Run 44253742681](https://github.com/faria2177/SELFACS/actions/runs/44253742681) — **success** (2026-11-18)
  - Upstream editor dependency build: সফল
  - AI edit planner + Flutter workspace unit tests: সফল
  - Debug APK assemble: সফল; artifact `SELFACS-debug-apks` (~258 MB, ARM64 + ARMv7)
- আগের failure-এর কারণ ও fix:
  - `core/app/build.gradle.kts`-এ newline-এর জায়গায় literal `\n` → ঠিক করা হয়েছে; duplicate `security-crypto` dependency সরানো।
  - `GitHubStudioFragment.kt`-এ `singleLine` unresolved → `setSingleLine(true)`।
- এখনো বাকি: APK বাস্তব ডিভাইসে install/launch test। Device test ছাড়া কোনো feature **Verified** নয়।


### Release signing
- [x] `selfacs-release.yml`: manual signed release workflow (assembleRelease, apksigner verify, SHA256SUMS, GitHub Release)।
- [x] Dry-run (temporary key) সফল: run 44277226745 — arm64-v8a ও armeabi-v7a release APK build ও sign হয়েছে।
- [x] Repository secrets যোগ করা হয়েছে: `SELFACS_KEYSTORE_BASE64`, `SELFACS_KEYSTORE_PASSWORD`, `SELFACS_KEY_PASSWORD`, `SELFACS_KEY_ALIAS`।
- Release cert SHA-256: `48:78:C6:1B:AC:FB:41:8E:3E:F9:71:E9:FB:17:35:99:5E:03:69:C9:29:71:A7:12:77:B1:9D:F8:35:6A:37:07`
- Signed release: [v0.1.0](https://github.com/faria2177/SELFACS/releases/tag/v0.1.0) (pre-release, run 44283501455, APK Signature v2, cert মিলেছে)।
- Debug release: [v0.1.0-debug.1](https://github.com/faria2177/SELFACS/releases/tag/v0.1.0-debug.1) (debug key)।
- [x] Auto-versioned release: tag ফাঁকা রাখলে পরের patch tag (v0.1.0 → v0.1.1); versionCode = 2000 + (M·10000 + m·100 + p)·10 (+1 armeabi-v7a)।
- [x] [v0.1.1](https://github.com/faria2177/SELFACS/releases/tag/v0.1.1) — versionCode 3010 (APK থেকে যাচাই), release notes commit থেকে।

### In-app updater (code present, device test বাকি)
- [x] GitHub Releases API থেকে নতুন stable tag খোঁজা (pre-release সহ), device ABI অনুযায়ী APK।
- [x] App চালু হলে "Update Available" dialog (Installed → Latest, View Update / Later)।
- [x] Updates স্ক্রিন: header, installed/latest কার্ড, Download & Install, What's New, GitHub link, Auto check toggle।
- [x] Download progress dialog; size + SHA-256 (SHA256SUMS.txt) + signing certificate যাচাই করে তবেই installer খোলা।
- [x] Dashboard-এ "Updates" action; upstream updater launch-এ বন্ধ।
- [x] `SelfacsVersionTest` CI-তে pass।
- [ ] বাস্তব ডিভাইসে v0.1.1 → পরের release update flow পরীক্ষা।

- Signing key কখনো repository-তে নয়; `.gitignore`-এ `*.jks`, `*.keystore`, `signing/`।

## 6. Definition of done

কোনো feature-কে **Complete & Verified** বলা যাবে কেবল যখন:
1. Implementation main branch-এ committed।
2. Relevant unit/instrumentation tests pass।
3. CI debug build সফল এবং APK artifact তৈরি।
4. বাস্তব Android device-এ সংশ্লিষ্ট user flow পরীক্ষা করা হয়েছে।
5. Known limitations ও test evidence এই `project.md`-এ নথিভুক্ত।

শুধু source code যোগ বা commit হলেই feature-কে verified বলা যাবে না।

## 7. Development rules

- প্রতিটি code change-এ সম্পূর্ণ file content বজায় রেখে পরিবর্তন করতে হবে; অসম্পূর্ণ snippet দিয়ে ফাইল replace করা যাবে না।
- শুধু প্রয়োজনীয় edited/added files পরিবর্তন করতে হবে।
- প্রতিটি phase শেষে tests/build চালিয়ে ফলাফল যাচাই করতে হবে।
- কোনো build/test না চালালে বা pending থাকলে সেটি স্পষ্টভাবে লিখতে হবে।
- Upstream license ও attribution সংরক্ষণ করতে হবে।
- API token, signing key, keystore password বা অন্য secret source code, commit, workflow log বা documentation-এ রাখা যাবে না।
- নতুন feature যোগের পাশাপাশি regression test করতে হবে এবং এই roadmap update করতে হবে।

---

**Next immediate action:** Run 44253742681-এর debug APK ডিভাইসে install করে launch, AI Studio, Flutter Studio ও GitHub Studio smoke test করা এবং ফলাফল এখানে লেখা; তারপর P1 package migration plan।
