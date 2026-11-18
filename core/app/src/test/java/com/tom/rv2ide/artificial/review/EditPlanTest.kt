package com.tom.rv2ide.artificial.review

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class EditPlanTest {
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("selfacs-review").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun proposal(file: File, code: String) = "FILE_TO_MODIFY: ${file.path}\n```kotlin\n$code\n```"

    @Test fun previewPreservesCodeAndRejectsStaleFile() = withRoot { root ->
        val file = File(root, "Example.kt").apply { writeText("class Old\n") }
        val edit = EditPlan.parse(root, proposal(file, "// copyright\nclass New"), EditPlan.snapshot(root)).single()
        assertEquals("class Old\n", file.readText())
        assertEquals("// copyright\nclass New\n", edit.after)
        assertTrue(EditPlan.unchanged(root, edit))
        file.writeText("user edit")
        assertFalse(EditPlan.unchanged(root, edit))
    }
    @Test fun rejectsOutsideAndSensitiveFiles() = withRoot { root ->
        assertNull(EditPlan.safeTarget(root, File(root.parentFile, "other.kt").path))
        assertNull(EditPlan.safeTarget(root, File(root, ".git/config").path))
        assertNull(EditPlan.safeTarget(root, File(root, "build/generated.kt").path))
        assertNull(EditPlan.safeTarget(root, File(root, "local.properties").path))
    }
    @Test fun rejectsIncompleteAndDuplicateBlocks() = withRoot { root ->
        val file = File(root, "Example.kt")
        val missingFence = "FILE_TO_MODIFY: ${file.path}\n```kotlin\nclass New"
        assertTrue(runCatching { EditPlan.parse(root, missingFence, emptyMap()) }.isFailure)
        val block = proposal(file, "class New")
        assertTrue(runCatching { EditPlan.parse(root, "$block\n$block", emptyMap()) }.isFailure)
        assertFalse(file.exists())
    }
}
