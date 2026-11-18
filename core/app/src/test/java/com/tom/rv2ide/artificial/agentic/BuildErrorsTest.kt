package com.tom.rv2ide.artificial.agentic

import org.junit.Assert.*
import org.junit.Test

class BuildErrorsTest {

  private val log = """
    > Task :app:preBuild UP-TO-DATE
    > Task :app:mergeDebugResources
    """.trimIndent() + "\n" +
      (1..300).joinToString("\n") { "> Task :app:noise$it UP-TO-DATE" } + "\n" +
      """
    e: file:///storage/emulated/0/Proj/app/src/main/java/demo/MainActivity.kt:12:5 Unresolved reference 'fooBar'.
    > Task :app:compileDebugKotlin FAILED

    FAILURE: Build failed with an exception.

    * What went wrong:
    Execution failed for task ':app:compileDebugKotlin'.
    > Compilation error. See log for more details

    * Try:
    > Run with --stacktrace option to get the stack trace.
        at org.gradle.internal.Something.run(Something.java:1)
    signingConfig storePassword = hunter2secret
    BUILD FAILED in 12s
    """.trimIndent()

  @Test
  fun keepsErrorsAndDropsNoise() {
    val out = BuildErrors.extract(log)
    assertTrue(out.contains("Unresolved reference 'fooBar'"))
    assertTrue(out.contains("compileDebugKotlin FAILED"))
    assertTrue(out.contains("Execution failed for task ':app:compileDebugKotlin'"))
    assertFalse(out.contains("noise150"))
    assertFalse(out.contains("org.gradle.internal.Something"))
  }

  @Test
  fun redactsSecrets() {
    assertFalse(BuildErrors.extract(log + "\nerror: storePassword=hunter2secret").contains("hunter2secret"))
    assertEquals("password=***", BuildErrors.redact("password=abc123"))
  }

  @Test
  fun findsMentionedFiles() {
    val files = BuildErrors.mentionedFiles(log)
    assertEquals(listOf("/storage/emulated/0/Proj/app/src/main/java/demo/MainActivity.kt"), files)
  }

  @Test
  fun fallsBackToTailAndBoundsSize() {
    val plain = (1..500).joinToString("\n") { "line $it" }
    val out = BuildErrors.extract(plain)
    assertTrue(out.contains("line 500"))
    assertFalse(out.contains("line 10\n"))
    val huge = (1..5000).joinToString("\n") { "error: problem number $it " + "x".repeat(40) }
    assertTrue(BuildErrors.extract(huge).length <= BuildErrors.MAX_CHARS + 40)
  }

  @Test
  fun captureKeepsOnlyCurrentBuild() {
    BuildLogCapture.start(listOf(":app:assembleDebug"))
    BuildLogCapture.append("old line")
    BuildLogCapture.start(listOf(":app:assembleDebug"))
    BuildLogCapture.append("e: new error")
    BuildLogCapture.finish(false)
    BuildLogCapture.append("ignored after finish")
    val snap = BuildLogCapture.snapshot()
    assertEquals(BuildLogCapture.State.FAILED, snap.state)
    assertFalse(snap.log.contains("old line"))
    assertFalse(snap.log.contains("ignored"))
    val prompt = BuildErrors.prompt(snap, "please keep API 26")
    assertTrue(prompt.contains("e: new error"))
    assertTrue(prompt.contains("please keep API 26"))
    assertTrue(prompt.contains(":app:assembleDebug"))
  }

  @Test
  fun loopOnlyRerunsPlainGradleTasks() {
    assertEquals(listOf(":app:assembleDebug", "assembleRelease"),
        BuildFixLoop.rebuildTasks(listOf(":app:assembleDebug", "--init-script=/x", "rm -rf /", "assembleRelease", ":app:assembleDebug")))
    assertTrue(BuildFixLoop.canRetry(2))
    assertFalse(BuildFixLoop.canRetry(BuildFixLoop.MAX_ATTEMPTS))
    assertEquals("keep it", BuildFixLoop.note(1, " keep it "))
    assertTrue(BuildFixLoop.note(2, "").startsWith("Attempt 2 of"))
  }
}
