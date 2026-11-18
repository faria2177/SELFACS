/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.artificial.agents.compatible

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Reads OpenRouter's public model catalog (no API key needed) and returns the free models. */
object OpenRouterModels {
  private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

  fun fetchFree(): List<ModelList.FreeModel> {
    val conn = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
      connectTimeout = 15000
      readTimeout = 30000
      setRequestProperty("User-Agent", "SELFACS")
      setRequestProperty("Accept", "application/json")
    }
    try {
      check(conn.responseCode == 200) { "OpenRouter models list: HTTP ${conn.responseCode}" }
      val body = conn.inputStream.bufferedReader().use { r ->
        val sb = StringBuilder(); val buf = CharArray(8192)
        while (true) { val n = r.read(buf); if (n < 0) break; check(sb.length + n <= 8_000_000) { "Model list too large" }; sb.append(buf, 0, n) }
        sb.toString()
      }
      val data = JSONObject(body).getJSONArray("data")
      val entries = (0 until data.length()).map { i ->
        val o = data.getJSONObject(i)
        val pricing = o.optJSONObject("pricing")
        val free = pricing != null && pricing.optString("prompt") in setOf("0", "0.0") &&
            pricing.optString("completion") in setOf("0", "0.0")
        Triple(o.optString("id"), o.optString("name", o.optString("id")),
            (if (free) "free" else "paid") to o.optInt("context_length"))
      }.filter { it.first.isNotBlank() }
      return ModelList.freeModels(entries)
    } finally {
      conn.disconnect()
    }
  }
}
