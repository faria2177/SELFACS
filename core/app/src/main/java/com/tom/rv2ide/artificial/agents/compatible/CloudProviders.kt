package com.tom.rv2ide.artificial.agents.compatible

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import java.net.URI

/** Additional OpenAI-compatible providers. No credentials are bundled in the app. */
object CloudProviders {
    data class Provider(val id: String, val name: String, val endpoint: String, val keyPage: String)
    /** [model] may hold several model IDs (comma separated); they are tried in order. */
    data class Config(val key: String, val model: String, val endpoint: String) {
        val models: List<String> get() = ModelList.parse(model)
    }

    val all = listOf(
        Provider("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "https://console.groq.com/keys"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "https://openrouter.ai/keys"),
        Provider("glm", "Z.AI / GLM", "https://api.z.ai/api/paas/v4/chat/completions", "https://z.ai/manage-apikey/apikey-list"),
        Provider("qwen", "Alibaba Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", "https://www.alibabacloud.com/help/en/model-studio/"),
    )
    fun find(id: String): Provider? = all.firstOrNull { it.id == id }

    @Volatile private var store: android.content.SharedPreferences? = null

    @Synchronized private fun storage(context: Context): android.content.SharedPreferences {
        store?.let { return it }
        val app = context.applicationContext
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, "selfacs_cloud_credentials", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { store = it }
    }

    fun get(context: Context, id: String): Config? {
        val provider = find(id) ?: return null
        return try {
            val prefs = storage(context)
            val key = prefs.getString("$id.key", "").orEmpty()
            val model = prefs.getString("$id.model", "").orEmpty()
            val endpoint = prefs.getString("$id.endpoint", provider.endpoint) ?: provider.endpoint
            if (key.isBlank() || model.isBlank() || !validEndpoint(provider, endpoint)) null
            else Config(key, model, endpoint)
        } catch (_: Exception) { null }
    }

    fun save(context: Context, provider: Provider, config: Config) {
        require(config.key.isNotBlank() && config.key.length <= 4096 && config.key.none { it.isWhitespace() })
        val models = config.models
        require(ModelList.isValid(models))
        require(validEndpoint(provider, config.endpoint))
        check(storage(context).edit().putString("${provider.id}.key", config.key)
            .putString("${provider.id}.model", ModelList.join(models))
            .putString("${provider.id}.endpoint", config.endpoint).commit()) { "Credential storage failed" }
    }

    fun clear(context: Context, id: String) {
        check(storage(context).edit().remove("$id.key").remove("$id.model").remove("$id.endpoint").commit())
    }

    fun validEndpoint(provider: Provider, endpoint: String): Boolean {
        if (endpoint == provider.endpoint) return true
        return try {
            val uri = URI(endpoint)
            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
                uri.query != null || uri.fragment != null) return false
            when (provider.id) {
                "glm" -> uri.host == "api.z.ai" && uri.path == "/api/coding/paas/v4/chat/completions"
                "qwen" -> (uri.host?.endsWith(".maas.aliyuncs.com") == true ||
                    uri.host in listOf("dashscope.aliyuncs.com", "dashscope-us.aliyuncs.com", "dashscope-intl.aliyuncs.com")) &&
                    uri.path == "/compatible-mode/v1/chat/completions"
                else -> false
            }
        } catch (_: Exception) { false }
    }

    fun register(context: Context) {
        val app = context.applicationContext
        all.forEach { provider ->
            AIAgentRegistry.register(provider.id, object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent = CompatibleCloudAgent(provider)
                override fun hasValidApiKey(): Boolean = get(app, provider.id) != null
                override fun getApiKey(): String? = get(app, provider.id)?.key
            })
        }
    }
}
