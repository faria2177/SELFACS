# Application ID migration gate

Current application ID and namespace: `com.tom.rv2ide`. Proposed independent ID: `com.selfacs.ide` (not yet applied).

`termux/shared/src/main/java/com/termux/shared/termux/TermuxConstants.java` explicitly documents that compiled binaries and bootstrap use the package-specific prefix `/data/data/com.tom.rv2ide/files/usr`. The repository includes ARM64 and ARMv7 `libtermux-bootstrap.so` binaries. `core/common/.../Environment.java` derives Java, SDK and shell paths from app files. Simply editing Gradle applicationId would split the runtime paths and can break terminal and builds.

Required before migration: rebuild both bootstrap ABIs and all prefix-bound packages against the new prefix; audit installer scripts, external ACS toolchain manifests, JNI, provider authorities, permission strings, intents, updater/signing and project data migration. Preserve upstream namespace until JNI and generated resource references are mapped. Verify fresh install, terminal command execution, JDK, SDK, Gradle wrapper, Java/Kotlin LSP, APK install and coexistence on API 36. Upstream compileSdk is 37 and targetSdk is 28; target SDK changes must be tested separately against Android restrictions on executing writable app files. Do not claim Android 16 runtime support from compileSdk alone.

The application ID remains unchanged until the replacement toolchain and device smoke tests pass.