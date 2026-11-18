package com.tom.rv2ide.updates

import org.junit.Assert.*
import org.junit.Test

class SelfacsVersionTest {

  private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
      SelfacsVersion.Release(
          tag = tag, title = tag, notes = "", publishedAt = "2026-11-18T00:00:00Z",
          htmlUrl = "https://example.invalid/$tag", prerelease = true, draft = draft,
          assets = assets.map { SelfacsVersion.Asset(it, "https://example.invalid/$it", 10) },
      )

  private val bothAbis = arrayOf("selfacs-arm64-v8a-release.apk", "selfacs-armeabi-v7a-release.apk", "SHA256SUMS.txt")

  @Test
  fun parsesOnlyStableTags() {
    assertEquals(SelfacsVersion.Semver(0, 1, 2), SelfacsVersion.parseTag("v0.1.2"))
    assertEquals(SelfacsVersion.Semver(1, 10, 0), SelfacsVersion.parseTag("1.10.0"))
    assertNull(SelfacsVersion.parseTag("v0.1.0-debug.1"))
    assertNull(SelfacsVersion.parseTag("v0.1"))
    assertNull(SelfacsVersion.parseTag("latest"))
  }

  @Test
  fun versionCodeMatchesWorkflowFormulaAndIncreases() {
    // Must match .github/workflows/selfacs-release.yml: 2000 + (M*10000 + m*100 + p) * 10
    assertEquals(3000, SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 1, 0)))
    assertEquals(3010, SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 1, 1)))
    assertTrue(SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 2, 0)) >
        SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 1, 99)))
    assertTrue(SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(1, 0, 0)) >
        SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 99, 99)))
    // Old v0.1.0 build used versionCode 1050/1051; every new tag must be higher.
    assertTrue(SelfacsVersion.baseVersionCode(SelfacsVersion.Semver(0, 1, 1)) > 1051)
  }

  @Test
  fun picksNewestStableReleaseForArm64() {
    val list = listOf(
        release("v0.1.0-debug.1", "selfacs-arm64-v8a-debug.apk"),
        release("v0.1.1", *bothAbis),
        release("v0.1.10", *bothAbis),
        release("v0.1.2", *bothAbis),
        release("v0.2.0", *bothAbis, draft = true),
    )
    val c = SelfacsVersion.latestCandidate(list, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))!!
    assertEquals("v0.1.10", c.release.tag)
    assertEquals("arm64-v8a", c.abi)
    assertEquals(SelfacsVersion.baseVersionCode(c.version), c.versionCode)
    assertNotNull(c.checksums)
  }

  @Test
  fun arm32DeviceGetsV7aWithIncrement() {
    val c = SelfacsVersion.latestCandidate(listOf(release("v0.1.1", *bothAbis)), listOf("armeabi-v7a", "armeabi"))!!
    assertEquals("armeabi-v7a", c.abi)
    assertEquals(3011, c.versionCode)
    assertFalse(SelfacsVersion.isNewer(c, 3011))
    assertTrue(SelfacsVersion.isNewer(c, 1051))
  }

  @Test
  fun arm64FallsBackToV7aWhenOnlyV7aPublished() {
    val c = SelfacsVersion.latestCandidate(
        listOf(release("v0.1.1", "selfacs-armeabi-v7a-release.apk")), listOf("arm64-v8a"))!!
    assertEquals("armeabi-v7a", c.abi)
  }

  @Test
  fun noCandidateWithoutMatchingApk() {
    assertNull(SelfacsVersion.latestCandidate(listOf(release("v0.1.1", "other.zip")), listOf("arm64-v8a")))
    assertNull(SelfacsVersion.latestCandidate(listOf(release("v0.1.1", *bothAbis)), listOf("x86_64")))
  }

  @Test
  fun readsSha256Sums() {
    val hash = "a".repeat(64)
    val sums = "$hash  selfacs-arm64-v8a-release.apk\n${"b".repeat(64)} *selfacs-armeabi-v7a-release.apk\n"
    assertEquals(hash, SelfacsVersion.expectedSha256(sums, "selfacs-arm64-v8a-release.apk"))
    assertEquals("b".repeat(64), SelfacsVersion.expectedSha256(sums, "selfacs-armeabi-v7a-release.apk"))
    assertNull(SelfacsVersion.expectedSha256(sums, "missing.apk"))
  }

  @Test
  fun displayNameStripsBuildMetadata() {
    assertEquals("0.1.1", SelfacsVersion.displayName("0.1.1+gh.r05"))
    assertEquals("unknown", SelfacsVersion.displayName(null))
  }

  @Test
  fun plainNotesConvertsMarkdownLists() {
    val out = SelfacsVersion.plainNotes("## Changes\n- **Fix** [link](https://x)\n* `code`")
    assertEquals("Changes\n• Fix link\n• code", out)
  }
}
