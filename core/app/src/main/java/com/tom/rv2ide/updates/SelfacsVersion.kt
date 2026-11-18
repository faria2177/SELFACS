/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.updates

/**
 * Pure (Android-free) version and release selection logic for the SELFACS in-app updater.
 *
 * Release tags have the form `vMAJOR.MINOR.PATCH`. The CI release workflow derives the Android
 * versionCode from the tag with [baseVersionCode]; the build adds a per-ABI increment
 * (arm64-v8a = 0, armeabi-v7a = 1). The same formula is used here so the app can decide whether a
 * release is newer than the installed build without any extra metadata file.
 */
object SelfacsVersion {

  private val STABLE_TAG = Regex("""^v?(\d{1,2})\.(\d{1,2})\.(\d{1,2})$""")

  /** ABI → versionCode increment. Must match `flavorsAbis` in build-logic. */
  val ABI_INCREMENT = mapOf("arm64-v8a" to 0, "armeabi-v7a" to 1)

  data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)

    override fun toString() = "$major.$minor.$patch"
  }

  fun parseTag(tag: String): Semver? {
    val m = STABLE_TAG.matchEntire(tag.trim()) ?: return null
    val (a, b, c) = m.destructured
    return Semver(a.toInt(), b.toInt(), c.toInt())
  }

  /** versionCode for a tag before the ABI increment. Must match the release workflow. */
  fun baseVersionCode(v: Semver): Int = 2000 + (v.major * 10000 + v.minor * 100 + v.patch) * 10

  /** `0.1.1+gh.r05` → `0.1.1` for display. */
  fun displayName(versionName: String?): String =
      versionName?.substringBefore('+')?.trim()?.ifEmpty { null } ?: "unknown"

  data class Asset(val name: String, val url: String, val size: Long)

  data class Release(
      val tag: String,
      val title: String,
      val notes: String,
      val publishedAt: String,
      val htmlUrl: String,
      val prerelease: Boolean,
      val draft: Boolean,
      val assets: List<Asset>,
  )

  data class Candidate(
      val release: Release,
      val version: Semver,
      val abi: String,
      val apk: Asset,
      val checksums: Asset?,
      val versionCode: Int,
  )

  fun apkName(abi: String) = "selfacs-$abi-release.apk"

  /** ABIs to try for a device, in order (arm64 devices can also run armeabi-v7a). */
  fun abiPreference(supportedAbis: List<String>): List<String> {
    val out = mutableListOf<String>()
    if ("arm64-v8a" in supportedAbis) out += "arm64-v8a"
    if ("armeabi-v7a" in supportedAbis || "armeabi" in supportedAbis || "arm64-v8a" in supportedAbis) {
      out += "armeabi-v7a"
    }
    return out
  }

  /**
   * Newest stable-tagged, non-draft release that has an APK for this device. Pre-releases are
   * included because SELFACS publishes pre-releases until device testing is complete.
   */
  fun latestCandidate(releases: List<Release>, supportedAbis: List<String>): Candidate? {
    val abis = abiPreference(supportedAbis)
    return releases
        .asSequence()
        .filter { !it.draft }
        .mapNotNull { r -> parseTag(r.tag)?.let { r to it } }
        .sortedByDescending { it.second }
        .mapNotNull { (r, v) ->
          abis.firstNotNullOfOrNull { abi ->
            r.assets.firstOrNull { it.name == apkName(abi) }?.let { apk ->
              Candidate(
                  release = r,
                  version = v,
                  abi = abi,
                  apk = apk,
                  checksums = r.assets.firstOrNull { it.name == "SHA256SUMS.txt" },
                  versionCode = baseVersionCode(v) + (ABI_INCREMENT[abi] ?: 0),
              )
            }
          }
        }
        .firstOrNull()
  }

  fun isNewer(candidate: Candidate, installedVersionCode: Long): Boolean =
      candidate.versionCode.toLong() > installedVersionCode

  /** Finds the expected SHA-256 for [fileName] in a `sha256sum` formatted file. */
  fun expectedSha256(sums: String, fileName: String): String? =
      sums.lineSequence()
          .map { it.trim() }
          .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].trimStart('*') == fileName) parts[0].lowercase() else null
          }
          .firstOrNull { it.matches(Regex("[0-9a-f]{64}")) }

  /** Strips simple Markdown so release notes read cleanly in a TextView. */
  fun plainNotes(markdown: String): String =
      markdown
          .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
          .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
          .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
          .replace("`", "")
          .replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "• ")
          .replace(Regex("""\n{3,}"""), "\n\n")
          .trim()
}
