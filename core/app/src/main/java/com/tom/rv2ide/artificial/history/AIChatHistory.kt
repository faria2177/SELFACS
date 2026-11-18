/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.artificial.history

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-project AI request history, stored in app-private storage (never inside the project, so it
 * is not committed to git and is not visible to the AI agent's file tools).
 */
class AIChatHistory(context: Context, projectPath: String) {

  data class Entry(
      val time: Long,
      val mode: String,
      val prompt: String,
      val response: String,
      val appliedFiles: Int,
      val error: Boolean,
  )

  companion object {
    const val MAX_ENTRIES = 50
    const val MAX_PROMPT = 4_000
    const val MAX_RESPONSE = 20_000

    fun key(projectPath: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(File(projectPath).absolutePath.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

    /** Bounds one entry before it is stored. */
    fun bounded(e: Entry): Entry =
        e.copy(
            prompt = e.prompt.trim().take(MAX_PROMPT),
            response =
                e.response.trim().let {
                  if (it.length <= MAX_RESPONSE) it else it.take(MAX_RESPONSE) + "\n… [truncated]"
                },
        )

    /** Newest first, capped. */
    fun merged(existing: List<Entry>, new: Entry): List<Entry> =
        (listOf(bounded(new)) + existing).take(MAX_ENTRIES)
  }

  private val file =
      File(File(context.applicationContext.filesDir, "ai_history").apply { mkdirs() }, "${key(projectPath)}.json")

  @Synchronized
  fun load(): List<Entry> =
      try {
        if (!file.isFile) emptyList()
        else {
          val arr = JSONArray(file.readText())
          (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                o.optLong("time"),
                o.optString("mode"),
                o.optString("prompt"),
                o.optString("response"),
                o.optInt("applied"),
                o.optBoolean("error"),
            )
          }
        }
      } catch (e: Exception) {
        emptyList()
      }

  @Synchronized
  fun add(entry: Entry) {
    val all = merged(load(), entry)
    val arr = JSONArray()
    all.forEach { e ->
      arr.put(
          JSONObject()
              .put("time", e.time)
              .put("mode", e.mode)
              .put("prompt", e.prompt)
              .put("response", e.response)
              .put("applied", e.appliedFiles)
              .put("error", e.error)
      )
    }
    val tmp = File(file.path + ".tmp")
    tmp.writeText(arr.toString())
    if (!tmp.renameTo(file)) {
      file.writeText(arr.toString())
      tmp.delete()
    }
  }

  @Synchronized
  fun clear() {
    file.delete()
  }
}
