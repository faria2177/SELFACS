package com.tom.rv2ide.artificial.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.agents.compatible.CloudProviders
import com.tom.rv2ide.artificial.agents.compatible.CompatibleChatTransport
import kotlinx.coroutines.*

/** Messages of errors that affect every model of a provider. */
private object ModelListFatal {
    fun isFatal(message: String) = "(401)" in message || "(403)" in message || "(402)" in message
}

object CloudProviderConfigDialog {
    private fun chooseFreeModels(context: Context, button: android.widget.Button, target: EditText) {
        button.isEnabled = false
        button.text = "Loading free models…"
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { com.tom.rv2ide.artificial.agents.compatible.OpenRouterModels.fetchFree() }
            }
            button.isEnabled = true
            button.text = "Choose free models"
            val models = result.getOrElse {
                Toast.makeText(context, "Could not load models: ${it.message?.take(120)}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (models.isEmpty()) { Toast.makeText(context, "No free models listed right now", Toast.LENGTH_LONG).show(); return@launch }
            val current = com.tom.rv2ide.artificial.agents.compatible.ModelList.parse(target.text.toString()).toSet()
            val checked = BooleanArray(models.size) { models[it].id in current }
            val labels = models.map { m ->
                val ctx = if (m.contextLength > 0) " · ${m.contextLength / 1000}K" else ""
                "${m.id}$ctx"
            }.toTypedArray()
            val max = com.tom.rv2ide.artificial.agents.compatible.ModelList.MAX_MODELS
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Free OpenRouter models (${models.size})")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Use selected") { _, _ ->
                    val picked = models.filterIndexed { i, _ -> checked[i] }.map { it.id }
                    if (picked.isEmpty()) return@setPositiveButton
                    // Keep previously chosen order first, then new picks in list order.
                    val ordered = (com.tom.rv2ide.artificial.agents.compatible.ModelList.parse(target.text.toString())
                        .filter { it in picked } + picked).distinct().take(max)
                    target.setText(com.tom.rv2ide.artificial.agents.compatible.ModelList.join(ordered))
                    if (picked.size > max) Toast.makeText(context, "Only the first $max models are kept", Toast.LENGTH_LONG).show()
                }
                .setNeutralButton("Select all", null)
                .setNegativeButton("Cancel", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    for (i in checked.indices) { checked[i] = true; dialog.listView.setItemChecked(i, true) }
                }
            }
            dialog.show()
        }
    }

    fun show(context: Context, provider: CloudProviders.Provider, onSaved: () -> Unit) {
        val old = CloudProviders.get(context, provider.id)
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: String, value: String, type: Int): EditText =
            EditText(context).apply {
                hint = label
                inputType = type
                setSingleLine(true)
                setText(value)
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        val key = field(if (old == null) "API key" else "API key (blank keeps saved key)", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val model = field("Model ID(s) — comma separated, tried in order", old?.model.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setSingleLine(false)
            maxLines = 5
        }
        if (provider.id == "openrouter") {
            content.addView(android.widget.Button(context).apply {
                text = "Choose free models"
                setOnClickListener { chooseFreeModels(context, this, model) }
            })
        }
        val customEndpoint = provider.id == "qwen" || provider.id == "glm"
        val endpoint = if (customEndpoint) field("HTTPS chat completions endpoint",
            old?.endpoint ?: provider.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) else null

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fun enteredConfig() = CloudProviders.Config(key.text.toString().trim().ifBlank { old?.key.orEmpty() },
            model.text.toString().trim(), endpoint?.text?.toString()?.trim() ?: provider.endpoint)
        val testButton = android.widget.Button(context).apply { text = "Test connection" }
        content.addView(testButton)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(provider.name + " settings")
            .setMessage("Use your own API key and model ID. Requests may incur charges and send project source to this provider. Review project secrets before using AI. Auto-switch may send requests to another configured provider when enabled.")
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("Save and select", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Get API key", null)
            .create()
        dialog.setOnDismissListener { scope.cancel() }
        testButton.setOnClickListener {
            val config = enteredConfig()
            if (config.key.isBlank() || !com.tom.rv2ide.artificial.agents.compatible.ModelList.isValid(config.models) || !CloudProviders.validEndpoint(provider, config.endpoint)) {
                Toast.makeText(context, "Enter key, model and an approved HTTPS endpoint", Toast.LENGTH_LONG).show()
            } else {
                testButton.isEnabled = false
                testButton.text = "Testing…"
                scope.launch {
                    try {
                        val models = config.models
                        val results = mutableListOf<Pair<String, String?>>()
                        for ((i, m) in models.withIndex()) {
                            testButton.text = "Testing ${i + 1}/${models.size}…"
                            val error = withContext(Dispatchers.IO) {
                                try { CompatibleChatTransport.completeOne(provider, config, m, "Reply with OK only.", "Connection test"); null }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                            }
                            results += m to error
                            if (error != null && !ModelListFatal.isFatal(error)) continue
                            if (error != null) break // key/billing problem affects every model
                        }
                        val ok = results.count { it.second == null }
                        val failing = results.filter { it.second != null }.map { it.first }
                        val report = results.joinToString("\n") { (m, e) -> (if (e == null) "✅ " else "❌ ") + m + (e?.let { "\n    " + it.take(140) } ?: "") }
                        val b = MaterialAlertDialogBuilder(context)
                            .setTitle("$ok of ${models.size} model(s) working")
                            .setMessage(report)
                            .setPositiveButton("OK", null)
                        if (failing.isNotEmpty() && ok > 0) {
                            b.setNeutralButton("Remove failing") { _, _ ->
                                model.setText(com.tom.rv2ide.artificial.agents.compatible.ModelList.join(models - failing.toSet()))
                            }
                        }
                        b.show()
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        Toast.makeText(context, e.message?.take(160) ?: "Connection failed", Toast.LENGTH_LONG).show()
                    } finally {
                        testButton.text = "Test connection"
                        testButton.isEnabled = true
                    }
                }
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyPage))) }
                    .onFailure { Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show() }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val config = enteredConfig()
                try {
                    CloudProviders.save(context, provider, config)
                    dialog.dismiss()
                    onSaved()
                } catch (_: Exception) {
                    Toast.makeText(context, "Enter a valid key, model and approved HTTPS endpoint. Secure storage must be available.", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (old != null) {
            val clear = android.widget.Button(context).apply {
                text = "Remove saved credentials"
                setOnClickListener {
                    runCatching { CloudProviders.clear(context, provider.id) }
                        .onSuccess { dialog.dismiss(); Toast.makeText(context, "Credentials removed. Reopen AI to reconnect.", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(context, "Could not remove credentials", Toast.LENGTH_SHORT).show() }
                }
            }
            content.addView(clear)
        }
        dialog.show()
    }
}
