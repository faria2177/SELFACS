package com.tom.rv2ide.artificial.agents.compatible

import com.tom.rv2ide.artificial.exceptions.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object CompatibleChatTransport {
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
            when (val code = connection.responseCode) {
                401, 403 -> throw InvalidApiKeyException("${provider.name}: authentication or access denied ($code)")
                402 -> throw InsufficientBalanceException("${provider.name}: payment or credits required")
                429 -> throw RateLimitException("${provider.name}: rate limit or quota exceeded")
                !in 200..299 -> throw IllegalStateException("${provider.name}: HTTP $code; check model, key, endpoint and quota")
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
            val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            check(choice.optString("finish_reason") !in listOf("length", "content_filter")) { "Incomplete AI response; no changes applied" }
            val message = choice.getJSONObject("message")
            val answer = message.optString("content", "").trim()
            check(answer.isNotEmpty() && answer != "null") { "Provider returned no text" }
            return answer
        } finally {
            connection.disconnect()
        }
    }
}
