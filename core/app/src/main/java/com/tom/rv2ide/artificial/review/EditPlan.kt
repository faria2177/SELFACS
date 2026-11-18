package com.tom.rv2ide.artificial.review

import java.io.File

data class ProposedEdit(val file: File, val before: String?, val after: String)

/** Bounded, read-only planning. No shell commands or file writes. */
object EditPlan {
    private const val MAX_FILE = 256000
    private val extensions = setOf("kt", "java", "xml", "gradle", "kts", "dart", "md", "json", "yaml", "yml", "c", "cpp", "h", "txt")
    private val excluded = setOf("build", "node_modules", "signing", "secrets", "credentials")
    fun safeTarget(root: File, path: String): File? = try {
        val base = root.canonicalFile
        val raw = File(path)
        val target = raw.canonicalFile
        val parts = target.relativeTo(base).invariantSeparatorsPath.lowercase().split('/')
        val name = target.name.lowercase()
        if (!raw.isAbsolute || raw.absolutePath != target.path ||
            !target.path.startsWith(base.path + File.separator) ||
            parts.any { it.startsWith(".") || it in excluded } ||
            target.extension.lowercase() !in extensions ||
            name == "google-services.json" || name.contains("secret") || name.contains("credential") ||
            name.contains("apikey") || name.contains("api_key") || name.contains("keystore") ||
            (target.exists() && !target.isFile)) null else target
    } catch (_: Exception) { null }

    fun snapshot(root: File): Map<String, String> {
        val base = root.canonicalFile
        val result = linkedMapOf<String, String>()
        var remaining = 4000000L
        var visited = 0
        for (file in base.walkTopDown().onEnter { dir ->
            dir == base || (dir.name.lowercase() !in excluded && !dir.name.startsWith(".") &&
                runCatching { dir.absolutePath == dir.canonicalPath }.getOrDefault(false))
        }) {
            if (++visited > 15000 || remaining <= 0 || result.size >= 1000) break
            if (!file.isFile || file.length() > MAX_FILE || file.length() > remaining) continue
            val target = safeTarget(base, file.absolutePath) ?: continue
            val content = runCatching { target.readText() }.getOrNull() ?: continue
            if (content.length > MAX_FILE || '\u0000' in content) continue
            result[target.path] = content
            remaining -= target.length()
        }
        return result
    }

    fun parse(root: File, response: String, baseline: Map<String, String>): List<ProposedEdit> {
        require(response.length <= 1000000) { "Response exceeds review limit" }
        val lines = response.replace("\r\n", "\n").split('\n')
        val edits = mutableListOf<ProposedEdit>()
        val used = hashSetOf<String>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) { i++; continue }
            require(lines[i].startsWith("FILE_TO_MODIFY: ")) { "Expected a complete file proposal" }
            val path = lines[i++].removePrefix("FILE_TO_MODIFY: ").trim()
            val file = safeTarget(root, path) ?: error("Rejected path: $path")
            require(used.add(file.path) && used.size <= 20) { "Duplicate or too many proposed files" }
            require(i < lines.size && Regex("^```[\\w.+-]*$").matches(lines[i])) { "Missing opening code fence" }
            i++
            val buffer = mutableListOf<String>()
            while (i < lines.size && lines[i] != "```") buffer.add(lines[i++])
            require(i < lines.size) { "Unclosed code fence" }
            i++
            val content = buffer.joinToString("\n") + "\n"
            require(content.isNotBlank() && content.length <= MAX_FILE && '\u0000' !in content) { "Empty or oversized file" }
            val before = baseline[file.path]
            val edit = ProposedEdit(file, before, content)
            require(unchanged(root, edit)) { "File changed or was not captured: $path" }
            if (before != content) edits.add(edit)
        }
        return edits
    }

    fun unchanged(root: File, edit: ProposedEdit): Boolean = try {
        safeTarget(root, edit.file.path) == edit.file &&
            (if (edit.before == null) !edit.file.exists()
             else edit.file.isFile && edit.file.length() <= MAX_FILE && edit.file.readText() == edit.before)
    } catch (_: Exception) { false }
}
