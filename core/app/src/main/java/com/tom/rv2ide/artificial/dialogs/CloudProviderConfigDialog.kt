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

object CloudProviderConfigDialog {
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
        val model = field("Exact model ID from provider", old?.model.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val customEndpoint = provider.id == "qwen" || provider.id == "glm"
        val endpoint = if (customEndpoint) field("HTTPS chat completions endpoint",
            old?.endpoint ?: provider.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) else null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(provider.name + " settings")
            .setMessage("Use your own API key and model ID. Requests may incur charges and send project source to this provider. Review project secrets before using AI. Auto-switch may send requests to another configured provider when enabled.")
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("Save and select", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Get API key", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyPage))) }
                    .onFailure { Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show() }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val enteredKey = key.text.toString().trim()
                val config = CloudProviders.Config(enteredKey.ifBlank { old?.key.orEmpty() },
                    model.text.toString().trim(), endpoint?.text?.toString()?.trim() ?: provider.endpoint)
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
