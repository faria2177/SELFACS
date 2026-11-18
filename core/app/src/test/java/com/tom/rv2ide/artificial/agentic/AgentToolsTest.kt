package com.tom.rv2ide.artificial.agentic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentToolsTest {

  private fun project(): File {
    val root = Files.createTempDirectory("agent").toFile().canonicalFile
    File(root, "app/src/main/java/demo").mkdirs()
    File(root, "app/src/main/java/demo/MainActivity.kt").writeText("package demo\n\nclass MainActivity {\n  fun onCreate() {}\n}\n")
    File(root, "app/build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
    File(root, "app/build").mkdirs()
    File(root, "app/build/Generated.kt").writeText("class Generated { fun onCreate() {} }\n")
    File(root, ".git").mkdirs()
    File(root, "local.properties").writeText("sdk.dir=/x\n")
    File(root, "app/src/main/java/demo/Config.kt").writeText("const val API_KEY = \"abcdef123456\"\n")
    File(root, "app/src/main/java/demo/Big.kt").writeText("x".repeat(AgentTools.MAX_READ_CHARS + 10))
    return root
  }

  @Test
  fun parsesToolOnlyReplies() {
    val calls = AgentTools.parseCalls("```\nREAD_FILE: app/build.gradle.kts\nSEARCH: onCreate\nLIST_DIR: .\n```")
    assertEquals(3, calls.size)
    assertEquals(AgentTools.Kind.READ_FILE, calls[0].kind)
    assertEquals("app/build.gradle.kts", calls[0].arg)
    assertEquals(AgentTools.Kind.SEARCH, calls[1].kind)
  }

  @Test
  fun finalAnswersAreNotToolCalls() {
    assertTrue(AgentTools.parseCalls("Here is how it works.\nIt uses a ViewModel.").isEmpty())
    assertTrue(AgentTools.parseCalls("FILE_TO_MODIFY: /p/A.kt\n```kotlin\nREAD_FILE: x\n```").isEmpty())
    // Long prose that merely mentions a tool is a final answer.
    val prose = (1..6).joinToString("\n") { "Line $it of explanation" } + "\nREAD_FILE: a.kt"
    assertTrue(AgentTools.parseCalls(prose).isEmpty())
  }

  @Test
  fun capsCallsPerRoundAndDeduplicates() {
    val many = (1..20).joinToString("\n") { "READ_FILE: f$it.kt" } + "\nREAD_FILE: f1.kt"
    assertEquals(AgentTools.MAX_CALLS_PER_ROUND, AgentTools.parseCalls(many).size)
  }

  @Test
  fun readsProjectFilesAndTruncatesLargeOnes() {
    val root = project()
    val out = AgentTools.readFile(root, "app/src/main/java/demo/MainActivity.kt")
    assertTrue(out.contains("class MainActivity"))
    assertTrue(AgentTools.readFile(root, "app/src/main/java/demo/Big.kt").contains("[truncated"))
    assertTrue(AgentTools.readFile(root, File(root, "app/build.gradle.kts").path).contains("plugins"))
  }

  @Test
  fun deniesEscapesHiddenBuildAndSecrets() {
    val root = project()
    assertTrue(AgentTools.readFile(root, "../../etc/passwd").startsWith("denied"))
    assertTrue(AgentTools.readFile(root, "/etc/hosts").startsWith("denied"))
    assertTrue(AgentTools.readFile(root, "local.properties").startsWith("denied"))
    assertTrue(AgentTools.readFile(root, "app/build/Generated.kt").startsWith("denied"))
    assertTrue(AgentTools.readFile(root, "app/src/main/java/demo/Config.kt").startsWith("withheld"))
    assertTrue(AgentTools.listDir(root, "..").startsWith("denied"))
    assertTrue(AgentTools.listDir(root, ".git").startsWith("denied"))
  }

  @Test
  fun listsAndSearchesWithoutExcludedContent() {
    val root = project()
    val listing = AgentTools.listDir(root, ".")
    assertTrue(listing.contains("app/"))
    assertFalse(listing.contains(".git"))
    val hits = AgentTools.search(root, "onCreate")
    assertTrue(hits.contains("app/src/main/java/demo/MainActivity.kt:4:"))
    assertFalse(hits.contains("Generated"))
    assertFalse(AgentTools.search(root, "abcdef123456").contains("Config.kt"))
  }

  @Test
  fun observationsRespectBudget() {
    val call = AgentTools.Call(AgentTools.Kind.READ_FILE, "a.kt")
    val small = AgentTools.observations(listOf(call to "ok"))
    assertTrue(small.contains("### READ_FILE: a.kt"))
    val big = AgentTools.observations(listOf(call to "y".repeat(500), call to "z".repeat(500)), budget = 700)
    assertTrue(big.contains("omitted: result budget reached"))
  }

  @Test
  fun finalRoundForbidsTools() {
    assertTrue(AgentTools.protocol(null, AgentTools.MAX_ROUNDS - 1).contains("FINAL ROUND"))
    assertFalse(AgentTools.protocol(null, 0).contains("FINAL ROUND"))
  }
}
