package com.tom.rv2ide.artificial.agents.compatible

import com.tom.rv2ide.artificial.exceptions.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object CompatibleChatTransport {
    /** Provider error body, bounded, with the API key removed. */
    private fun providerError(connection: HttpURLConnection, key: String): String {
        val body = runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val buf = CharArray(4000); val n = r.read(buf); if (n > 0) String(buf, 0, n) else ""
            }.orEmpty()
        }.getOrDefault("")
        if (body.isBlank()) return ""
        val msg = runCatching {
            val o = JSONObject(body)
            val err = o.optJSONObject("error")
            val m = err?.optString("message").orEmpty().ifBlank { o.optString("message") }
            val raw = err?.optJSONObject("metadata")?.optString("raw").orEmpty()
            listOf(m, raw).filter { it.isNotBlank() }.joinToString(" — ")
        }.getOrDefault("").ifBlank { body }
        return msg.replace(key, "***").replace(Regex("\\s+"), " ").trim().take(400)
    }

    fun complete(provider: CloudProviders.Provider, config: CloudProviders.Config, system: String, prompt: String): String {
        require(CloudProviders.validEndpoint(provider, config.endpoint))
        val connection = URL(config.endpoint).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.key}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val body = JSONObject().put("model", config.model).put("messages", messages).put("stream", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // Show the provider's own explanation (e.g. OpenRouter data-policy or unknown-model errors).
                val detail = providerError(connection, config.key)
                val suffix = if (detail.isNotEmpty()) "\n$detail" else ""
                when (code) {
                    401 -> throw InvalidApiKeyException("${provider.name}: API key rejected (401)$suffix")
                    403 -> throw InvalidApiKeyException("${provider.name}: access denied (403)$suffix")
                    402 -> throw InsufficientBalanceException("${provider.name}: payment or credits required (402)$suffix")
                    429 -> throw RateLimitException("${provider.name}: rate limit or quota exceeded (429)$suffix")
                    404 -> throw NonRetryableProviderException("${provider.name}: model or endpoint not available (404)$suffix")
                    400, 413, 422 -> throw NonRetryableProviderException("${provider.name}: request rejected ($code)$suffix")
                    else -> throw IllegalStateException("${provider.name}: HTTP $code$suffix")
                }
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    check(result.length + n <= 2_000_000) { "AI response too large" }
                    result.append(buffer, 0, n)
                }
                result.toString()
            }
            val json = JSONObject(raw)
            json.optJSONObject("error")?.let { err ->
                // Some routers answer 200 with an error object.
                throw IllegalStateException("${provider.name}: ${err.optString("message", "provider error").take(300)}")
            }
            val choices = json.optJSONArray("choices")
            check(choices != null && choices.length() > 0) { "${provider.name}: response had no choices" }
            val choice = choices.getJSONObject(0)
            val finish = choice.optString("finish_reason")
            check(finish != "content_filter") { "${provider.name}: response blocked by the provider's content filter" }
            check(finish != "length") { "${provider.name}: answer was cut off (model output limit). Try a model with a larger output limit or a smaller request." }
            val message = choice.getJSONObject("message")
            val answer = message.optString("content", "").let { if (it == "null") "" else it }.trim()
            check(answer.isNotEmpty()) {
                if (message.optString("reasoning").isNotBlank()) "${provider.name}: model returned only reasoning and no answer; try another model"
                else "${provider.name}: provider returned no text"
            }
            return answer
        } finally {
            connection.disconnect()
        }
    }
}
