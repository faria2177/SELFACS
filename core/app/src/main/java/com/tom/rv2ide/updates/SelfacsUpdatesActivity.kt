/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.updates

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.updates.SelfacsUpdateUi.color
import com.tom.rv2ide.updates.SelfacsUpdateUi.dp
import com.tom.rv2ide.updates.SelfacsUpdateUi.iconBox
import com.tom.rv2ide.updates.SelfacsUpdateUi.onPrimaryContainer
import com.tom.rv2ide.updates.SelfacsUpdateUi.onSurface
import com.tom.rv2ide.updates.SelfacsUpdateUi.onSurfaceVariant
import com.tom.rv2ide.updates.SelfacsUpdateUi.pillButton
import com.tom.rv2ide.updates.SelfacsUpdateUi.primary
import com.tom.rv2ide.updates.SelfacsUpdateUi.primaryContainer
import com.tom.rv2ide.updates.SelfacsUpdateUi.rounded
import com.tom.rv2ide.updates.SelfacsUpdateUi.surfaceHigh
import com.tom.rv2ide.updates.SelfacsUpdateUi.surfaceHighest
import com.tom.rv2ide.updates.SelfacsUpdateUi.text
import com.tom.rv2ide.updates.SelfacsUpdateUi.visible
import kotlinx.coroutines.launch
import com.google.android.material.R as MR

/** "Updates" screen: installed vs latest, Download & Install, What's New, auto-check option. */
class SelfacsUpdatesActivity : AppCompatActivity() {

  private lateinit var updater: SelfacsUpdater
  private lateinit var content: LinearLayout
  private lateinit var loading: CircularProgressIndicator
  private lateinit var body: LinearLayout

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    updater = SelfacsUpdater(this)

    val bg = color(android.R.attr.colorBackground, 0xff111318.toInt())
    val scroll = ScrollView(this).apply {
      setBackgroundColor(bg)
      isFillViewport = true
    }
    content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(32))
    }
    scroll.addView(content)
    setContentView(scroll)

    // Top bar: back pill + title
    val bar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(dp(6), dp(6), dp(22), dp(6))
      background = rounded(surfaceHigh(), dp(32).toFloat())
      addView(ImageButton(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        imageTintList = ColorStateList.valueOf(onPrimaryContainer())
        background = rounded(primaryContainer(), dp(26).toFloat())
        contentDescription = "Back"
        setOnClickListener { finish() }
      }, LinearLayout.LayoutParams(dp(52), dp(52)))
      addView(text("Updates", 26f, primary(), bold = true), LinearLayout.LayoutParams(-2, -2).apply {
        marginStart = dp(14)
      })
    }
    content.addView(bar, LinearLayout.LayoutParams(-2, -2))

    loading = CircularProgressIndicator(this).apply { isIndeterminate = true }
    content.addView(loading, LinearLayout.LayoutParams(-2, -2).apply {
      gravity = Gravity.CENTER_HORIZONTAL
      topMargin = dp(48)
    })
    body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(body, LinearLayout.LayoutParams(-1, -2))

    val cached = SelfacsUpdateUi.lastAvailable
    if (cached != null && SelfacsVersion.isNewer(cached, updater.installedVersionCode())) {
      render(SelfacsUpdater.CheckResult.Available(cached))
    } else {
      refresh()
    }
  }

  private fun refresh() {
    loading.visible(true)
    body.removeAllViews()
    lifecycleScope.launch {
      val result = updater.check()
      if (result is SelfacsUpdater.CheckResult.Available) SelfacsUpdateUi.lastAvailable = result.candidate
      render(result)
    }
  }

  private fun card(color: Int = surfaceHigh()): LinearLayout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(color, dp(28).toFloat())
      }

  private fun lp(top: Int = 16) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

  private fun section(title: String) =
      body.addView(text(title, 17f, primary(), bold = true).apply {
        setPadding(dp(8), 0, 0, 0)
      }, lp(26))

  private fun render(result: SelfacsUpdater.CheckResult) {
    loading.visible(false)
    body.removeAllViews()

    val candidate = when (result) {
      is SelfacsUpdater.CheckResult.Available -> result.candidate
      is SelfacsUpdater.CheckResult.UpToDate -> result.latest
      is SelfacsUpdater.CheckResult.Failed -> null
    }
    val available = result is SelfacsUpdater.CheckResult.Available
    val installed = updater.installedVersionName()

    // ---- Header card ----
    val header = card(surfaceHigh())
    val hero = card(if (available) primaryContainer() else surfaceHighest())
    val heroRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val iconCol = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      addView(iconBox(if (available) R.drawable.ic_download else R.drawable.ic_sync,
          surfaceHighest(), onPrimaryContainer(), 76, 34))
      if (available && candidate != null) {
        addView(text("New v${candidate.version}", 13f, primaryContainer(), bold = true,
            gravity = Gravity.CENTER).apply {
          background = rounded(onPrimaryContainer(), dp(14).toFloat())
          setPadding(dp(10), dp(3), dp(10), dp(3))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
      }
    }
    heroRow.addView(iconCol)
    val heroText = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), 0, 0, 0)
      val fg = if (available) onPrimaryContainer() else onSurface()
      addView(text("SELFACS", 12f, fg).apply { letterSpacing = 0.12f })
      addView(text(
          when (result) {
            is SelfacsUpdater.CheckResult.Available -> "Update Available!"
            is SelfacsUpdater.CheckResult.UpToDate -> "You're up to date"
            is SelfacsUpdater.CheckResult.Failed -> "Couldn't check"
          }, 24f, fg, bold = true))
      addView(text(
          when (result) {
            is SelfacsUpdater.CheckResult.Available -> "Version v${result.candidate.version} is ready to download"
            is SelfacsUpdater.CheckResult.UpToDate -> "No newer release on GitHub"
            is SelfacsUpdater.CheckResult.Failed -> result.message
          }, 14f, fg))
    }
    heroRow.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
    hero.addView(heroRow)
    header.addView(hero)

    val versions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    versions.addView(versionCard(R.drawable.ic_info, "v$installed", "Installed", false),
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
    versions.addView(versionCard(R.drawable.ic_download,
        candidate?.let { "v${it.version}" } ?: "—", "Latest Release", true),
        LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
    header.addView(versions, lp(12))

    val action = if (available && candidate != null) {
      pillButton("Download & Install v${candidate.version}") {
        SelfacsUpdateUi.startDownload(this, updater, candidate)
      }.apply { setIconResource(R.drawable.ic_download); iconTint = ColorStateList.valueOf(currentTextColor) }
    } else {
      pillButton("Check for updates") { refresh() }.apply {
        setIconResource(R.drawable.ic_sync); iconTint = ColorStateList.valueOf(currentTextColor)
      }
    }
    header.addView(action, lp(14))
    body.addView(header, lp(20))

    // ---- What's New ----
    if (candidate != null) {
      section("What's New")
      val notesCard = card()
      val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      top.addView(iconBox(R.drawable.ic_sync, surfaceHighest(), primary(), 46, 22))
      top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, 0, 0)
        addView(text("v${candidate.version}", 20f, onSurface(), bold = true))
        addView(text(if (candidate.release.prerelease) "Pre-release" else "New Release", 14f, primary()))
      }, LinearLayout.LayoutParams(0, -2, 1f))
      candidate.release.publishedAt.take(10).takeIf { it.isNotBlank() }?.let { date ->
        top.addView(text("📅 $date", 13f, onSurfaceVariant()).apply {
          background = rounded(surfaceHighest(), dp(16).toFloat())
          setPadding(dp(12), dp(6), dp(12), dp(6))
        })
      }
      notesCard.addView(top)

      val notesBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(surfaceHighest(), dp(20).toFloat())
        addView(text("📢 SELFACS Update: v${candidate.version}", 17f, onSurface(), bold = true))
        val notes = SelfacsVersion.plainNotes(candidate.release.notes).ifBlank { "No release notes." }
        addView(text(notes, 14.5f, onSurfaceVariant()).apply {
          setLineSpacing(dp(4).toFloat(), 1f)
          setTextIsSelectable(true)
        }, lp(12))
      }
      notesCard.addView(notesBox, lp(14))
      body.addView(notesCard, lp(10))

      body.addView(linkRow("View on GitHub", "Open the release page with all files") {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.release.htmlUrl)))
      }, lp(16))
    } else {
      body.addView(linkRow("All releases", "Open SELFACS releases on GitHub") {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SelfacsUpdater.RELEASES_PAGE)))
      }, lp(16))
    }

    // ---- Options ----
    section("Options")
    val opt = card()
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(iconBox(R.drawable.ic_sync, surfaceHighest(), primary(), 52, 24))
    row.addView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(14), 0, dp(8), 0)
      addView(text("Auto Check For Updates", 17f, onSurface(), bold = true))
      addView(text("Automatically check for new releases when the app launches", 13.5f, onSurfaceVariant()))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(MaterialSwitch(this).apply {
      isChecked = updater.autoCheck
      setOnCheckedChangeListener { _, checked -> updater.autoCheck = checked }
    })
    opt.addView(row)
    val last = updater.lastCheck
    if (last > 0) {
      opt.addView(text("Last checked " + DateUtils.getRelativeTimeSpanString(last), 12.5f,
          onSurfaceVariant()).apply { setPadding(dp(66), dp(8), 0, 0) })
    }
    body.addView(opt, lp(10))
  }

  private fun versionCard(icon: Int, value: String, label: String, highlighted: Boolean): LinearLayout =
      card(if (highlighted) primaryContainer() else surfaceHighest()).apply {
        val fg = if (highlighted) onPrimaryContainer() else onSurface()
        addView(iconBox(icon, if (highlighted) color(androidx.appcompat.R.attr.colorPrimary, primary()) else surfaceHigh(),
            if (highlighted) color(MR.attr.colorOnPrimary, 0xffffffff.toInt()) else fg, 44, 22))
        addView(text(value, 22f, fg, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        addView(text(label, 14f, fg))
      }

  private fun linkRow(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
      card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(iconBox(R.drawable.ic_github, surfaceHighest(), primary(), 52, 24))
        addView(LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(14), 0, dp(8), 0)
          addView(text(title, 17f, onSurface(), bold = true))
          addView(text(subtitle, 13.5f, onSurfaceVariant()))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("→", 22f, onSurfaceVariant()))
      }
}
