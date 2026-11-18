/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Checks GitHub Releases of faria2177/SELFACS, downloads, verifies and installs updates. */
class SelfacsUpdater(context: Context) {

  companion object {
    private const val TAG = "SelfacsUpdater"
    const val OWNER = "faria2177"
    const val REPO = "SELFACS"
    private const val RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"
    /**
     * Static copy of the releases list (same JSON shape as the API), refreshed by the release
     * workflow. Served by raw.githubusercontent.com, so it is not subject to the 60 requests/hour
     * API limit that shared mobile-network IPs hit quickly.
     */
    private const val RELEASES_FEED = "https://raw.githubusercontent.com/$OWNER/$REPO/update-feed/selfacs-update.json"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    private const val PREFS = "selfacs_updates"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_LAST_CHECK = "last_check"
  }

  private val app = context.applicationContext
  private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  var autoCheck: Boolean
    get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
    set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

  val lastCheck: Long
    get() = prefs.getLong(KEY_LAST_CHECK, 0L)

  sealed class CheckResult {
    data class Available(val candidate: SelfacsVersion.Candidate) : CheckResult()
    data class UpToDate(val latest: SelfacsVersion.Candidate?) : CheckResult()
    data class Failed(val message: String) : CheckResult()
  }

  fun installedVersionName(): String = SelfacsVersion.displayName(packageInfo()?.versionName)

  fun installedVersionCode(): Long {
    val info = packageInfo() ?: return 0L
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
  }

  private fun packageInfo(): PackageInfo? =
      try {
        app.packageManager.getPackageInfo(app.packageName, 0)
      } catch (e: PackageManager.NameNotFoundException) {
        null
      }

  suspend fun check(): CheckResult =
      withContext(Dispatchers.IO) {
        try {
          val releases = fetchReleases()
          prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
          val candidate = SelfacsVersion.latestCandidate(releases, Build.SUPPORTED_ABIS.toList())
          if (candidate != null && SelfacsVersion.isNewer(candidate, installedVersionCode())) {
            CheckResult.Available(candidate)
          } else {
            CheckResult.UpToDate(candidate)
          }
        } catch (e: Exception) {
          Log.w(TAG, "Update check failed", e)
          CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
      }

  private fun open(url: String, timeoutMs: Int = 15000): HttpURLConnection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SELFACS-Updater")
      }

  private fun fetchReleases(): List<SelfacsVersion.Release> {
    val feedError =
        try {
          return parseReleases(download(RELEASES_FEED, json = false))
        } catch (e: Exception) {
          Log.w(TAG, "Update feed unavailable, falling back to the GitHub API", e)
          e
        }
    return try {
      parseReleases(download(RELEASES_API, json = true))
    } catch (e: Exception) {
      throw IllegalStateException(e.message ?: feedError.message ?: "Update check failed", e)
    }
  }

  private fun download(url: String, json: Boolean): String {
    val conn = open(url)
    if (json) conn.setRequestProperty("Accept", "application/vnd.github+json")
    try {
      val code = conn.responseCode
      if (code != HttpURLConnection.HTTP_OK) {
        throw IllegalStateException(
            if (json && (code == 403 || code == 429)) "GitHub rate limit reached, try again later"
            else "Update server returned HTTP $code"
        )
      }
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  private fun parseReleases(body: String): List<SelfacsVersion.Release> {
      val arr = JSONArray(body)
      return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val assetsJson = o.optJSONArray("assets") ?: JSONArray()
        SelfacsVersion.Release(
            tag = o.optString("tag_name"),
            title = o.optString("name").ifBlank { o.optString("tag_name") },
            notes = o.optString("body"),
            publishedAt = o.optString("published_at"),
            htmlUrl = o.optString("html_url"),
            prerelease = o.optBoolean("prerelease"),
            draft = o.optBoolean("draft"),
            assets =
                (0 until assetsJson.length()).map { j ->
                  val a = assetsJson.getJSONObject(j)
                  SelfacsVersion.Asset(
                      a.optString("name"),
                      a.optString("browser_download_url"),
                      a.optLong("size"),
                  )
                },
        )
      }
  }

  fun apkFile(candidate: SelfacsVersion.Candidate): File {
    val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "updates").apply { mkdirs() }
    return File(dir, "selfacs-${candidate.version}-${candidate.abi}.apk")
  }

  /**
   * Downloads the APK, then verifies size, SHA-256 (from the release's SHA256SUMS.txt) and that it
   * is signed with the same certificate as the installed app. [onProgress] gets 0..100 or -1.
   */
  suspend fun download(
      candidate: SelfacsVersion.Candidate,
      onProgress: suspend (percent: Int, done: Long, total: Long) -> Unit,
  ): File =
      withContext(Dispatchers.IO) {
        val target = apkFile(candidate)
        target.parentFile?.listFiles()?.forEach { if (it != target) it.delete() }
        val part = File(target.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")

        val conn = open(candidate.apk.url, 30000)
        try {
          if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
          }
          val total = conn.contentLengthLong.takeIf { it > 0 } ?: candidate.apk.size
          var done = 0L
          var lastPercent = -2
          conn.inputStream.use { input ->
            part.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                done += n
                val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                if (percent != lastPercent) {
                  lastPercent = percent
                  onProgress(percent, done, total)
                }
              }
            }
          }
          if (candidate.apk.size > 0 && done != candidate.apk.size) {
            throw IllegalStateException("Incomplete download ($done of ${candidate.apk.size} bytes)")
          }
        } catch (e: Exception) {
          part.delete()
          throw e
        } finally {
          conn.disconnect()
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        candidate.checksums?.let { sumsAsset ->
          val sums = open(sumsAsset.url).let { c ->
            try {
              c.inputStream.bufferedReader().use { it.readText() }
            } finally {
              c.disconnect()
            }
          }
          val expected = SelfacsVersion.expectedSha256(sums, candidate.apk.name)
          if (expected != null && expected != actual) {
            part.delete()
            throw SecurityException("Checksum mismatch — download discarded")
          }
        }

        if (!sameSigner(part)) {
          part.delete()
          throw SecurityException(
              "The update is signed with a different key than the installed app. " +
                  "Uninstall this build first, then install the release manually."
          )
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Could not save the update file")
        target
      }

  private fun sameSigner(apk: File): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
    return try {
      val pm = app.packageManager
      val flags = PackageManager.GET_SIGNING_CERTIFICATES
      val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
      if (archive.packageName != app.packageName) return false
      val installed = pm.getPackageInfo(app.packageName, flags)
      val a = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
      val b = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
      a != null && b != null && a.isNotEmpty() && a == b
    } catch (e: Exception) {
      Log.w(TAG, "Signature check failed", e)
      false
    }
  }

  fun canInstallPackages(): Boolean =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

  fun installPermissionIntent(): Intent =
      Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

  fun installIntent(apk: File): Intent {
    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", apk)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }
}
