package com.tom.rv2ide.flutter

import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class FlutterWorkspaceTest {
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("flutter-workspace").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun createsStarterAndWorkflow() = withRoot { root ->
        val project = FlutterWorkspace.create(root, "my_app")
        assertTrue(File(project, "pubspec.yaml").readText().contains("name: my_app"))
        assertTrue(File(project, "lib/main.dart").isFile)
        assertTrue(File(project, "test/widget_test.dart").isFile)
        FlutterWorkspace.addBuildWorkflow(project)
        assertTrue(File(project, ".github/workflows/flutter-apk.yml").readText().contains("flutter build apk --debug"))
        assertTrue(runCatching { FlutterWorkspace.addBuildWorkflow(project) }.isFailure)
    }
    @Test fun confinesFileWritesAndDetectsConflicts() = withRoot { root ->
        val project = FlutterWorkspace.create(root, "my_app")
        assertTrue(runCatching { FlutterWorkspace.save(project, "../else.dart", "x", null) }.isFailure)
        assertTrue(runCatching { FlutterWorkspace.save(project, "lib/main.dart", "x", "stale") }.isFailure)
        FlutterWorkspace.save(project, "lib/extra.dart", "void main() {}", null)
        assertEquals("void main() {}", FlutterWorkspace.read(project, "lib/extra.dart"))
    }
    @Test fun zipRoundTripAndTraversalRejection() = withRoot { root ->
        val project = FlutterWorkspace.create(root, "my_app")
        File(project, ".env").writeText("SECRET=example")
        val bytes = ByteArrayOutputStream()
        FlutterWorkspace.exportZip(project, bytes)
        val copy = FlutterWorkspace.importZip(root, "copied_app", ByteArrayInputStream(bytes.toByteArray()))
        assertTrue(File(copy, "lib/main.dart").isFile)
        assertFalse(File(copy, ".env").exists())
        val malicious = ByteArrayOutputStream()
        ZipOutputStream(malicious).use { it.putNextEntry(ZipEntry("../escape.dart")); it.write(byteArrayOf(1)); it.closeEntry() }
        assertTrue(runCatching { FlutterWorkspace.importZip(root, "unsafe", ByteArrayInputStream(malicious.toByteArray())) }.isFailure)
        assertFalse(File(root, "unsafe").exists())
    }
}
