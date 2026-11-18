/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.updates

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.google.android.material.R as MR

/** Shared view helpers + launch prompt + download dialog for the SELFACS updater. */
object SelfacsUpdateUi {

  /** Last result of a successful check, so "View Update" opens instantly. */
  @Volatile var lastAvailable: SelfacsVersion.Candidate? = null

  // ---------- small view helpers ----------

  fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

  fun Context.color(attr: Int, fallback: Int): Int = MaterialColors.getColor(this, attr, fallback)

  fun Context.primary() = color(androidx.appcompat.R.attr.colorPrimary, 0xff5b6abf.toInt())
  fun Context.onPrimary() = color(MR.attr.colorOnPrimary, 0xffffffff.toInt())
  fun Context.primaryContainer() = color(MR.attr.colorPrimaryContainer, 0xff34407a.toInt())
  fun Context.onPrimaryContainer() = color(MR.attr.colorOnPrimaryContainer, 0xffdde1ff.toInt())
  fun Context.surfaceHigh() = color(MR.attr.colorSurfaceContainerHigh, 0xff2a2c33.toInt())
  fun Context.surfaceHighest() = color(MR.attr.colorSurfaceContainerHighest, 0xff34363d.toInt())
  fun Context.onSurface() = color(MR.attr.colorOnSurface, 0xffe3e2e9.toInt())
  fun Context.onSurfaceVariant() = color(MR.attr.colorOnSurfaceVariant, 0xffc5c6d0.toInt())

  fun rounded(color: Int, radius: Float): GradientDrawable =
      GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
      }

  fun Context.text(
      value: CharSequence,
      sizeSp: Float,
      color: Int,
      bold: Boolean = false,
      gravity: Int = Gravity.START,
  ): TextView =
      TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        this.gravity = gravity
        if (bold) setTypeface(typeface, Typeface.BOLD)
      }

  fun Context.iconBox(iconRes: Int, bg: Int, tint: Int, sizeDp: Int = 72, iconDp: Int = 34): FrameLayout =
      FrameLayout(this).apply {
        background = rounded(bg, dp(sizeDp / 3).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(
            ImageView(context).apply {
              setImageResource(iconRes)
              imageTintList = ColorStateList.valueOf(tint)
            },
            FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER),
        )
      }

  fun Context.versionChip(label: String, version: String, highlighted: Boolean): LinearLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(if (highlighted) primaryContainer() else surfaceHighest(), dp(20).toFloat())
        val fg = if (highlighted) onPrimaryContainer() else onSurface()
        addView(text(label, 13f, fg, gravity = Gravity.CENTER))
        addView(text("v$version", 22f, fg, bold = true, gravity = Gravity.CENTER))
      }

  fun Context.pillButton(label: String, onClick: () -> Unit): MaterialButton =
      MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        cornerRadius = dp(28)
        minHeight = dp(56)
        backgroundTintList = ColorStateList.valueOf(primary())
        setTextColor(onPrimary())
        setOnClickListener { onClick() }
      }

  fun Context.textButton(label: String, onClick: () -> Unit): MaterialButton =
      MaterialButton(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        elevation = 0f
        stateListAnimator = null
        isAllCaps = false
        textSize = 15f
        setTextColor(onSurface())
        setOnClickListener { onClick() }
      }

  private fun lp(w: Int, h: Int, top: Int = 0) =
      LinearLayout.LayoutParams(w, h).apply { topMargin = top }

  // ---------- launch prompt ----------

  /** Called from MainActivity on a fresh launch. Silent on errors / when up to date. */
  fun checkOnLaunch(activity: AppCompatActivity) {
    val updater = SelfacsUpdater(activity)
    if (!updater.autoCheck) return
    activity.lifecycleScope.launch {
      val result = updater.check()
      if (result is SelfacsUpdater.CheckResult.Available && !activity.isFinishing) {
        lastAvailable = result.candidate
        showAvailableDialog(activity, updater, result.candidate)
      }
    }
  }

  fun showAvailableDialog(
      activity: AppCompatActivity,
      updater: SelfacsUpdater,
      candidate: SelfacsVersion.Candidate,
  ) {
    val ctx = activity
    var dialog: AlertDialog? = null
    val root =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER_HORIZONTAL
          with(ctx) {
            setPadding(dp(24), dp(28), dp(24), dp(12))
            addView(iconBox(R.drawable.ic_sync, primaryContainer(), onPrimaryContainer()))
            addView(text("Update Available", 24f, onSurface(), bold = true, gravity = Gravity.CENTER),
                lp(-1, -2, dp(18)))
            addView(
                text("A newer build of SELFACS is ready to download & explore.", 15f,
                    onSurfaceVariant(), gravity = Gravity.CENTER),
                lp(-1, -2, dp(6)))
            val row = LinearLayout(ctx).apply {
              orientation = LinearLayout.HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
              addView(versionChip("Installed", updater.installedVersionName(), false),
                  LinearLayout.LayoutParams(0, -2, 1f))
              addView(text("→", 22f, primary(), gravity = Gravity.CENTER),
                  LinearLayout.LayoutParams(dp(40), -2))
              addView(versionChip("Latest", candidate.version.toString(), true),
                  LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(row, lp(-1, -2, dp(20)))
            addView(pillButton("View Update  →") {
              dialog?.dismiss()
              activity.startActivity(Intent(activity, SelfacsUpdatesActivity::class.java))
            }, lp(-1, -2, dp(20)))
            addView(textButton("Later") { dialog?.dismiss() }, lp(-1, -2, dp(4)))
          }
        }
    dialog = MaterialAlertDialogBuilder(ctx).setView(root).setCancelable(true).create()
    dialog.show()
  }

  // ---------- download flow ----------

  /** Confirm → download with progress → verify → open the system installer. */
  fun startDownload(
      activity: AppCompatActivity,
      updater: SelfacsUpdater,
      candidate: SelfacsVersion.Candidate,
  ) {
    if (!updater.canInstallPackages()) {
      MaterialAlertDialogBuilder(activity)
          .setTitle("Allow installs")
          .setMessage("To install updates, allow SELFACS to install unknown apps, then tap Download again.")
          .setPositiveButton("Open settings") { _, _ ->
            activity.startActivity(updater.installPermissionIntent())
          }
          .setNegativeButton("Not now", null)
          .show()
      return
    }

    val existing = updater.apkFile(candidate)
    if (existing.exists() && existing.length() == candidate.apk.size) {
      activity.startActivity(updater.installIntent(existing))
      return
    }

    val sizeMb = if (candidate.apk.size > 0) " (%.0f MB)".format(candidate.apk.size / 1048576.0) else ""
    MaterialAlertDialogBuilder(activity)
        .setTitle("Download Update?")
        .setMessage(
            "SELFACS v${candidate.version} is available (you have v${updater.installedVersionName()}). " +
                "The ${candidate.abi} APK$sizeMb will be downloaded and verified before installing."
        )
        .setNegativeButton("Not Now", null)
        .setPositiveButton("Download") { _, _ -> runDownload(activity, updater, candidate) }
        .show()
  }

  private fun runDownload(
      activity: AppCompatActivity,
      updater: SelfacsUpdater,
      candidate: SelfacsVersion.Candidate,
  ) {
    val ctx = activity
    var job: Job? = null
    lateinit var percentText: TextView
    lateinit var statusText: TextView
    lateinit var bar: LinearProgressIndicator
    val root =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER_HORIZONTAL
          with(ctx) {
            setPadding(dp(24), dp(28), dp(24), dp(12))
            addView(iconBox(R.drawable.ic_download, surfaceHighest(), primary(), 64, 30))
            addView(text("Downloading v${candidate.version}", 24f, onSurface(), bold = true,
                gravity = Gravity.CENTER), lp(-1, -2, dp(18)))
            bar = LinearProgressIndicator(ctx).apply {
              max = 100
              isIndeterminate = true
              trackCornerRadius = dp(4)
            }
            addView(bar, lp(-1, -2, dp(20)))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            percentText = text("0%", 14f, onSurface(), bold = true)
            statusText = text("Installing when ready…", 14f, onSurfaceVariant(), gravity = Gravity.END)
            row.addView(percentText, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(statusText, LinearLayout.LayoutParams(0, -2, 2f))
            addView(row, lp(-1, -2, dp(10)))
            addView(textButton("Cancel") { job?.cancel() }, lp(-1, -2, dp(12)))
          }
        }
    val dialog = MaterialAlertDialogBuilder(ctx).setView(root).setCancelable(false).create()
    dialog.show()

    job =
        activity.lifecycleScope.launch {
          try {
            val file =
                updater.download(candidate) { percent, done, total ->
                  if (percent >= 0) {
                    bar.isIndeterminate = false
                    bar.setProgressCompat(percent, true)
                    percentText.text = "$percent%"
                  } else {
                    percentText.text = "%.1f MB".format(done / 1048576.0)
                  }
                  if (total > 0) {
                    statusText.text =
                        "%.0f / %.0f MB · installing when ready…".format(done / 1048576.0, total / 1048576.0)
                  }
                }
            statusText.text = "Verified"
            dialog.dismiss()
            activity.startActivity(updater.installIntent(file))
          } catch (e: CancellationException) {
            dialog.dismiss()
            Toast.makeText(activity, "Download cancelled", Toast.LENGTH_SHORT).show()
          } catch (e: Exception) {
            dialog.dismiss()
            MaterialAlertDialogBuilder(activity)
                .setTitle("Update failed")
                .setMessage(e.message ?: "Unknown error")
                .setPositiveButton("OK", null)
                .setNeutralButton("Open releases") { _, _ ->
                  activity.startActivity(
                      Intent(Intent.ACTION_VIEW, android.net.Uri.parse(candidate.release.htmlUrl)))
                }
                .show()
          }
        }
  }

  fun View.visible(show: Boolean) {
    visibility = if (show) View.VISIBLE else View.GONE
  }
}
