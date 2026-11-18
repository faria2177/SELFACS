package com.tom.rv2ide.artificial.agents.compatible

import com.tom.rv2ide.artificial.agents.ProviderHealth
import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.NonRetryableProviderException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class ModelListTest {

  @Test
  fun parsesKeepsOrderAndDeduplicates() {
    assertEquals(listOf("a/b:free", "c/d", "e"), ModelList.parse(" a/b:free, c/d\n e ,a/b:free,, "))
    assertEquals(ModelList.MAX_MODELS, ModelList.parse((1..60).joinToString(",") { "m$it" }).size)
    assertEquals("x,y", ModelList.join(listOf("x", "y")))
  }

  @Test
  fun validatesIds() {
    assertTrue(ModelList.isValid(listOf("qwen/qwen3-coder:free", "openrouter/free")))
    assertFalse(ModelList.isValid(emptyList()))
    assertFalse(ModelList.isValid(listOf("bad model")))
    assertFalse(ModelList.isValid(listOf("a\"b")))
  }

  @Test
  fun fallbackStopsOnlyOnProviderWideErrors() {
    assertTrue(ModelList.tryNextModel(RateLimitException("429")))
    assertTrue(ModelList.tryNextModel(NonRetryableProviderException("404")))
    assertTrue(ModelList.tryNextModel(IllegalStateException("HTTP 503")))
    assertFalse(ModelList.tryNextModel(InvalidApiKeyException("401")))
    assertFalse(ModelList.tryNextModel(InsufficientBalanceException("402")))
    assertFalse(ModelList.tryNextModel(UnknownHostException("offline")))
  }

  @Test
  fun freeModelsFilteredAndCodersFirst() {
    val out = ModelList.freeModels(listOf(
        Triple("a/big:free", "Big", "free" to 200_000),
        Triple("b/paid", "Paid", "paid" to 1_000_000),
        Triple("c/zero", "Zero priced", "free" to 32_000),
        Triple("q/qwen3-coder:free", "Qwen3 Coder", "free" to 100_000),
    ))
    assertEquals(listOf("q/qwen3-coder:free", "a/big:free", "c/zero"), out.map { it.id })
  }

  @Test
  fun switchOrderPrefersWorkingAndForgetsOldFailures() {
    val now = 10_000_000L
    val order = ProviderHealth.SwitchOrder.order(listOf(
        "failedRecently" to ProviderHealth.Record(ProviderHealth.Status.FAILED, now - 1000),
        "unknown" to ProviderHealth.Record(ProviderHealth.Status.UNKNOWN, 0),
        "ok" to ProviderHealth.Record(ProviderHealth.Status.OK, now - 5000),
        "failedLongAgo" to ProviderHealth.Record(ProviderHealth.Status.FAILED, now - ProviderHealth.SwitchOrder.FAILURE_MEMORY_MS - 1),
    ), now)
    assertEquals(listOf("ok", "unknown", "failedLongAgo", "failedRecently"), order)
  }
}
