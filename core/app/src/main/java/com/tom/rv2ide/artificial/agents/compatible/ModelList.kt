/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.artificial.agents.compatible

import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import java.net.UnknownHostException

/** Pure helpers for multi-model provider configs (Android-free, unit tested). */
object ModelList {

  const val MAX_MODELS = 40
  private val validId = Regex("""^[A-Za-z0-9._:/@+\-]{1,200}$""")

  /** "a, b\nc" → [a, b, c]; trims, drops blanks/duplicates, keeps order, caps the count. */
  fun parse(text: String): List<String> =
      text.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_MODELS)

  fun isValid(models: List<String>): Boolean =
      models.isNotEmpty() && models.size <= MAX_MODELS && models.all { validId.matches(it) }

  fun join(models: List<String>): String = models.joinToString(",")

  /**
   * Whether the next model of the same provider may succeed after [error]. Key/billing problems
   * and a missing network affect every model, so they stop the model fallback.
   */
  fun tryNextModel(error: Throwable): Boolean =
      when (error) {
        is InvalidApiKeyException, is InsufficientBalanceException, is UnknownHostException -> false
        else -> true
      }

  data class FreeModel(val id: String, val name: String, val contextLength: Int)

  /** OpenRouter /models entries that cost nothing, coding models first, then larger context. */
  fun freeModels(all: List<Triple<String, String, Pair<String, Int>>>): List<FreeModel> =
      all.asSequence()
          .filter { (id, _, pc) -> id.endsWith(":free") || pc.first == "free" }
          .map { (id, name, pc) -> FreeModel(id, name, pc.second) }
          .distinctBy { it.id }
          .sortedWith(
              compareByDescending<FreeModel> { "coder" in it.id || "code" in it.name.lowercase() }
                  .thenByDescending { it.contextLength }
                  .thenBy { it.id }
          )
          .toList()
}
