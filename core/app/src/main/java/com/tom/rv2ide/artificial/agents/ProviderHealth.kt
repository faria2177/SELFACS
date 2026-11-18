/*
 *  This file is part of SELFACS (derived from Android Code Studio / AndroidIDE).
 *  SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.agents.compatible.CloudProviders
import com.tom.rv2ide.artificial.agents.compatible.CompatibleChatTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Checks which configured AI providers (and which models of multi-model providers) answer, and
 * remembers the last result so auto-switch can prefer providers that worked.
 */
object ProviderHealth {

  private const val PREFS = "selfacs_ai_health"
  private const val TIMEOUT_MS = 60_000L

  data class ModelResult(val model: String, val error: String?, val millis: Long)

  data class ProviderResult(
      val providerId: String,
      val name: String,
      val ok: Boolean,
      val millis: Long,
      val error: String?,
      val models: List<ModelResult> = emptyList(),
  )

  enum class Status { OK, FAILED, UNKNOWN }

  data class Record(val status: Status, val time: Long)

  fun record(context: Context, providerId: String): Record {
    val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(providerId, null)
        ?: return Record(Status.UNKNOWN, 0)
    val parts = v.split('|')
    val status = runCatching { Status.valueOf(parts[0]) }.getOrDefault(Status.UNKNOWN)
    return Record(status, parts.getOrNull(1)?.toLongOrNull() ?: 0)
  }

  fun mark(context: Context, providerId: String, ok: Boolean) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(providerId, "${if (ok) Status.OK else Status.FAILED}|${System.currentTimeMillis()}")
        .apply()
  }

  /**
   * Order for auto-switch: recently working first, then never checked, then recently failed
   * (a failure older than 30 minutes counts as unknown again).
   */
  fun orderForSwitch(context: Context, candidates: List<String>, now: Long = System.currentTimeMillis()): List<String> =
      SwitchOrder.order(candidates.map { it to record(context, it) }, now)

  /** Pure ordering logic (unit tested). */
  object SwitchOrder {
    const val FAILURE_MEMORY_MS = 30 * 60 * 1000L

    fun order(candidates: List<Pair<String, Record>>, now: Long): List<String> =
        candidates
            .sortedBy { (_, r) ->
              when {
                r.status == Status.OK -> 0
                r.status == Status.FAILED && now - r.time < FAILURE_MEMORY_MS -> 2
                else -> 1
              }
            }
            .map { it.first }
  }

  /** Tests every configured provider in parallel. Compatible providers are tested per model. */
  suspend fun checkAll(context: Context): List<ProviderResult> = coroutineScope {
    val app = context.applicationContext
    AIAgentRegistry.getAvailableProviders()
        .mapNotNull { id -> AIAgentRegistry.getFactory(id)?.let { id to it } }
        .filter { (_, f) -> f.hasValidApiKey() }
        .map { (id, factory) -> async(Dispatchers.IO) { checkOne(app, id, factory) } }
        .awaitAll()
        .sortedWith(compareByDescending<ProviderResult> { it.ok }.thenBy { it.name })
  }

  private suspend fun checkOne(context: Context, id: String, factory: AIAgentRegistry.AgentFactory): ProviderResult {
    val provider = CloudProviders.find(id)
    val config = provider?.let { CloudProviders.get(context, id) }
    val result =
        if (provider != null && config != null) {
          val models = config.models.map { model ->
            val start = System.currentTimeMillis()
            val error = try {
              withTimeout(TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                  CompatibleChatTransport.completeOne(provider, config, model, "Reply with OK only.", "Connection test")
                }
              }
              null
            } catch (e: CancellationException) {
              if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else throw e
            } catch (e: Exception) {
              e.message ?: e.javaClass.simpleName
            }
            ModelResult(model, error, System.currentTimeMillis() - start)
          }
          val ok = models.firstOrNull { it.error == null }
          ProviderResult(id, provider.name, ok != null, ok?.millis ?: models.sumOf { it.millis },
              if (ok == null) models.lastOrNull()?.error else null, models)
        } else {
          val start = System.currentTimeMillis()
          var name = id
          val error = try {
            val agent = factory.create(context)
            name = agent.providerName
            agent.initialize(factory.getApiKey().orEmpty(), context)
            agent.setContext(context)
            val r = withTimeout(TIMEOUT_MS) {
              agent.generateCode("Connection test. Reply with OK only.", null, "text", null)
            }
            agent.clearConversation()
            r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
          } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else throw e
          } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
          }
          ProviderResult(id, name, error == null, System.currentTimeMillis() - start, error)
        }
    mark(context, id, result.ok)
    return result
  }
}
