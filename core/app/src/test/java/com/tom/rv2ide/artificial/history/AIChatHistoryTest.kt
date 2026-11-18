package com.tom.rv2ide.artificial.history

import org.junit.Assert.*
import org.junit.Test

class AIChatHistoryTest {

  private fun entry(i: Int, response: String = "answer $i") =
      AIChatHistory.Entry(i.toLong(), "Ask / explain", "question $i", response, 0, false)

  @Test
  fun newestFirstAndCapped() {
    var list = emptyList<AIChatHistory.Entry>()
    for (i in 1..(AIChatHistory.MAX_ENTRIES + 5)) list = AIChatHistory.merged(list, entry(i))
    assertEquals(AIChatHistory.MAX_ENTRIES, list.size)
    assertEquals("question ${AIChatHistory.MAX_ENTRIES + 5}", list.first().prompt)
  }

  @Test
  fun boundsLongText() {
    val e = AIChatHistory.bounded(entry(1, "x".repeat(AIChatHistory.MAX_RESPONSE + 100)))
    assertTrue(e.response.endsWith("[truncated]"))
    assertTrue(e.response.length < AIChatHistory.MAX_RESPONSE + 50)
  }

  @Test
  fun keyIsStablePerProject() {
    assertEquals(AIChatHistory.key("/p/a"), AIChatHistory.key("/p/a"))
    assertNotEquals(AIChatHistory.key("/p/a"), AIChatHistory.key("/p/b"))
    assertEquals(24, AIChatHistory.key("/p/a").length)
  }
}
