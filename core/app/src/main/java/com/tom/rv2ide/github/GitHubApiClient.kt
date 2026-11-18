package com.tom.rv2ide.github

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubUser(val login: String, val name: String)
data class GitHubRepository(
    val owner: String,
    val name: String,
    val fullName: String,
    val description: String,
    val private: Boolean,
    val defaultBranch: String,
    val htmlUrl: String
)
data class GitHubBranch(val name: String, val commitSha: String)
data class GitHubWorkflow(val id: Long, val name: String, val path: String, val state: String)
data class GitHubRun(
    val id: Long,
    val name: String,
    val branch: String,
    val status: String,
    val conclusion: String,
    val htmlUrl: String,
    val createdAt: String
)
data class GitHubArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val createdAt: String
)

object GitHubValidation {
    private val segment = Regex("[A-Za-z0-9_.-]{1,100}")
    fun isValidOwnerOrRepo(value: String): Boolean =
        value.matches(segment) && value != "." && value != ".."
    fun isValidToken(value: String): Boolean =
        value.length in 20..512 && value.none { it.isWhitespace() }
    fun isValidBranch(value: String): Boolean =
        value.isNotBlank() && value.length <= 255 &&
            !value.startsWith("-") && !value.contains("..") &&
            !value.contains("//") && value.none { it.isWhitespace() || it in "~^:?*[\\\\]" } &&
            !value.endsWith("/") && !value.endsWith(".") && !value.contains("@{")
}

/** Minimal HTTPS-only GitHub REST client. All calls must run on Dispatchers.IO. */
class GitHubApiClient(private val token: String) {
    init { require(GitHubValidation.isValidToken(token)) { "Invalid token format" } }

    fun authenticatedUser(): GitHubUser {
        val json = requestObject("/user")
        return GitHubUser(json.optString("login"), json.optString("name").ifBlank { json.optString("login") })
    }

    fun repositories(): List<GitHubRepository> {
        val array = requestArray("/user/repos?per_page=100&sort=updated&direction=desc")
        return array.mapObjects { item ->
            val full = item.optString("full_name")
            val parts = full.split('/')
            if (parts.size != 2 || !GitHubValidation.isValidOwnerOrRepo(parts[0]) ||
                !GitHubValidation.isValidOwnerOrRepo(parts[1])) return@mapNotNull null
            GitHubRepository(
                parts[0], parts[1], full, item.optString("description").takeUnless { it == "null" }.orEmpty(),
                item.optBoolean("private"), item.optString("default_branch").ifBlank { "main" },
                item.optString("html_url")
            )
        }
    }

    fun branches(repo: GitHubRepository): List<GitHubBranch> {
        val array = requestArray("/repos/${part(repo.owner)}/${part(repo.name)}/branches?per_page=100")
        return array.mapObjects { item ->
            val name = item.optString("name")
            if (!GitHubValidation.isValidBranch(name)) return@mapNotNull null
            GitHubBranch(name, item.optJSONObject("commit")?.optString("sha").orEmpty())
        }
    }

    fun workflows(repo: GitHubRepository): List<GitHubWorkflow> {
        val array = requestObject("/repos/${part(repo.owner)}/${part(repo.name)}/actions/workflows?per_page=100")
            .optJSONArray("workflows") ?: JSONArray()
        return array.mapObjects { item ->
            val id = item.optLong("id")
            if (id <= 0) return@mapNotNull null
            GitHubWorkflow(id, item.optString("name"), item.optString("path"), item.optString("state"))
        }
    }

    fun runs(repo: GitHubRepository): List<GitHubRun> {
        val array = requestObject("/repos/${part(repo.owner)}/${part(repo.name)}/actions/runs?per_page=25")
            .optJSONArray("workflow_runs") ?: JSONArray()
        return array.mapObjects { item ->
            val id = item.optLong("id")
            if (id <= 0) return@mapNotNull null
            GitHubRun(
                id, item.optString("name").ifBlank { "Workflow run" }, item.optString("head_branch"),
                item.optString("status"), item.optString("conclusion").takeUnless { it == "null" }.orEmpty(),
                item.optString("html_url"), item.optString("created_at")
            )
        }
    }

    fun artifacts(repo: GitHubRepository): List<GitHubArtifact> {
        val array = requestObject("/repos/${part(repo.owner)}/${part(repo.name)}/actions/artifacts?per_page=100")
            .optJSONArray("artifacts") ?: JSONArray()
        return array.mapObjects { item ->
            val id = item.optLong("id")
            if (id <= 0) return@mapNotNull null
            GitHubArtifact(id, item.optString("name"), item.optLong("size_in_bytes"),
                item.optBoolean("expired"), item.optString("created_at"))
        }
    }

    fun dispatchWorkflow(repo: GitHubRepository, workflow: GitHubWorkflow, branch: String) {
        require(GitHubValidation.isValidBranch(branch)) { "Invalid branch name" }
        request(
            "/repos/${part(repo.owner)}/${part(repo.name)}/actions/workflows/${workflow.id}/dispatches",
            "POST", JSONObject().put("ref", branch)
        )
    }

    /** Streams an artifact ZIP. The bearer token is never sent to the signed redirect URL. */
    fun downloadArtifact(repo: GitHubRepository, artifact: GitHubArtifact, output: OutputStream): Long {
        require(artifact.id > 0 && !artifact.expired) { "Artifact is not available" }
        var current = URL("https://api.github.com/repos/${part(repo.owner)}/${part(repo.name)}/actions/artifacts/${artifact.id}/zip")
        var sendToken = true
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 45000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "SELFACS-Android")
                if (sendToken) {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            }
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 5) { "Too many artifact redirects" }
                    val location = connection.getHeaderField("Location") ?: error("Missing artifact download URL")
                    val next = URL(current, location)
                    require(next.protocol == "https") { "Refusing non-HTTPS artifact redirect" }
                    current = next
                    sendToken = false
                    continue
                }
                if (code !in 200..299) throw IOException("Artifact download failed (HTTP $code)")
                val contentLength = connection.contentLengthLong
                require(contentLength <= MAX_ARTIFACT_BYTES) { "Artifact exceeds 512 MiB limit" }
                var total = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARTIFACT_BYTES) { "Artifact exceeds 512 MiB limit" }
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                return total
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun requestObject(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        request(path, method, body) as? JSONObject ?: throw IOException("Unexpected GitHub API response")

    private fun requestArray(path: String): JSONArray =
        request(path) as? JSONArray ?: throw IOException("Unexpected GitHub API response")

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Any {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid API path" }
        val connection = (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "SELFACS-Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(output.length + count <= MAX_RESPONSE_CHARS) { "GitHub response too large" }
                    output.append(buffer, 0, count)
                }
                output.toString()
            }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw IOException("GitHub API HTTP $code: ${message.ifBlank { "Request failed" }.take(240)}")
            }
            return if (text.isBlank()) JSONObject() else JSONTokener(text).nextValue() ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }

    private fun part(value: String): String {
        require(GitHubValidation.isValidOwnerOrRepo(value)) { "Invalid repository path" }
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
        private const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    }
}

private inline fun <T : Any> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> {
    val result = mutableListOf<T>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        transform(item)?.let(result::add)
    }
    return result
}
