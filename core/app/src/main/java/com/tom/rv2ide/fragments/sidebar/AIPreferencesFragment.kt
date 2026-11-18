package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog
import com.tom.rv2ide.artificial.agents.compatible.CloudProviders
import com.tom.rv2ide.artificial.dialogs.CloudProviderConfigDialog

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupToggles()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateModelDropdown()
        syncCodeCompletionToggle()
    }
    
    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
    }

    private fun setupProviderDropdown() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM"
        ) + CloudProviders.all.associate { it.id to it.name }
        
        val allProviderIds = listOf("gemini", "openai", "claude", "deepseek", "grok", "localllm") + CloudProviders.all.map { it.id }
        val providerNames = allProviderIds.map { providerMap[it] ?: it }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, providerNames)
        providerDropdown.setAdapter(adapter)
        
        updateProviderDropdownSelection()
        
        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedProviderId = allProviderIds[position]
            val selectedProviderName = providerNames[position]
            
            if (selectedProviderId == "localllm") {
                showLocalLLMConfigDialog(selectedProviderName)
            } else if (CloudProviders.find(selectedProviderId) != null) {
                CloudProviderConfigDialog.show(requireContext(), CloudProviders.find(selectedProviderId)!!) {
                    handleProviderChange(selectedProviderId, selectedProviderName)
                }
                updateProviderDropdownSelection()
            } else {
                handleProviderChange(selectedProviderId, selectedProviderName)
            }
        }
    }
    
    private fun showLocalLLMConfigDialog(providerName: String) {
        val dialog = LocalLLMConfigDialog { baseUrl, modelName ->
            handleProviderChange("localllm", providerName)
        }
        dialog.show(parentFragmentManager, "LocalLLMConfigDialog")
    }
    
    private fun updateProviderDropdownSelection() {
        val providerMap = mapOf(
            "gemini" to "Google Gemini",
            "openai" to "OpenAI",
            "claude" to "Anthropic Claude",
            "deepseek" to "DeepSeek",
            "grok" to "xAI Grok",
            "localllm" to "Local LLM"
        ) + CloudProviders.all.associate { it.id to it.name }
        
        val currentProviderId = agents.getProvider()
        val currentProviderName = providerMap[currentProviderId] ?: currentProviderId
        providerDropdown.setText(currentProviderName, false)
    }
    
    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()
        
        android.util.Log.d("AIPreferences", "Current provider: $currentProvider, model: $currentModel")
        
        val providerDisplayName = when(currentProvider) {
            "gemini" -> "Google Gemini"
            "openai" -> "OpenAI"
            "claude" -> "Anthropic Claude"
            "deepseek" -> "DeepSeek"
            "grok" -> "xAI Grok"
            "localllm" -> "Local LLM"
            else -> CloudProviders.find(currentProvider)?.name ?: currentProvider.uppercase()
        }
        
        currentProviderText.text = providerDisplayName
        currentModelText.text = currentModel
    }

    private fun setupModelDropdown() {
        updateModelDropdown()
        
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val currentProvider = agents.getProvider()
            val models = agents.getModelsForProvider(currentProvider)
            
            if (position < models.size) {
                val selectedModel = models[position]
                handleModelChange(selectedModel)
            }
        }
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val models = agents.getModelsForProvider(currentProvider)
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models.toList())
        modelDropdown.setAdapter(adapter)
        
        val currentModel = agents.getAgent()
        if (currentModel in models) {
            modelDropdown.setText(currentModel, false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(models[0], false)
        }
    }

    // ---------- provider health check ----------

    private fun addCheckProvidersButton() {
        val row = autoSwitchToggle.parent as? View ?: return
        val column = row.parent as? ViewGroup ?: return
        if (column.findViewWithTag<View>("selfacs_check_providers") != null) return
        val button = com.google.android.material.button.MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            tag = "selfacs_check_providers"
            text = "Check all AI providers"
            isAllCaps = false
            setOnClickListener { runProviderCheck() }
        }
        column.addView(button, column.indexOfChild(row) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun runProviderCheck() {
        val ctx = requireContext()
        val count = com.tom.rv2ide.artificial.agents.AIAgentRegistry.getAvailableProviders().size
        if (count == 0) { showSnackbar("No AI provider is configured yet."); return }
        val progress = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Checking AI providers")
            .setMessage("Sending a short test to $count provider(s) and each configured model…")
            .setCancelable(false)
            .create()
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val results = runCatching { com.tom.rv2ide.artificial.agents.ProviderHealth.checkAll(ctx) }
            progress.dismiss()
            if (!isAdded) return@launch
            val list = results.getOrElse { showSnackbar("Check failed: ${it.message}"); return@launch }
            fun secs(ms: Long) = "%.1fs".format(ms / 1000.0)
            val report = buildString {
                for (r in list) {
                    append(if (r.ok) "✅ " else "❌ ").append(r.name)
                    if (r.models.isEmpty()) append(" · ").append(secs(r.millis))
                    append('\n')
                    if (r.models.isNotEmpty()) {
                        for (m in r.models) {
                            append("   ").append(if (m.error == null) "✅ " else "❌ ").append(m.model)
                            append(" · ").append(secs(m.millis)).append('\n')
                            m.error?.let { append("      ").append(it.take(120)).append('\n') }
                        }
                    } else if (r.error != null) {
                        append("   ").append(r.error.take(160)).append('\n')
                    }
                }
                append("\nAuto-switch uses working providers first.")
            }
            val partial = list.filter { r -> r.models.any { it.error == null } && r.models.any { it.error != null } }
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("${list.count { it.ok }} of ${list.size} provider(s) working")
                .setMessage(report)
                .setPositiveButton("OK", null)
            if (partial.isNotEmpty()) {
                val failing = partial.sumOf { r -> r.models.count { it.error != null } }
                builder.setNeutralButton("Remove $failing failing model(s)") { _, _ ->
                    var removed = 0
                    for (r in partial) {
                        val provider = CloudProviders.find(r.providerId) ?: continue
                        val config = CloudProviders.get(ctx, r.providerId) ?: continue
                        val working = r.models.filter { it.error == null }.map { it.model }
                        runCatching {
                            CloudProviders.save(ctx, provider, config.copy(
                                model = com.tom.rv2ide.artificial.agents.compatible.ModelList.join(working)))
                        }.onSuccess { removed += r.models.size - working.size }
                    }
                    showSnackbar("Removed $removed failing model(s)")
                }
            }
            builder.show()
        }
    }

    private fun setupToggles() {
        addCheckProvidersButton()
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message = if (isChecked) {
                "Auto-switch enabled"
            } else {
                "Auto-switch disabled"
            }
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("AIPreferences", "Toggle changed to: $isChecked")
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message = if (isChecked) {
                "✅ Code completion enabled"
            } else {
                "❌ Code completion disabled"
            }
            showSnackbar(message)
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (savedState != isCompletionEnabled) {
                    android.util.Log.d("AIPreferences", "State mismatch detected: saved=$savedState, current=$isCompletionEnabled")
                    isCompletionEnabled = savedState
                    
                    if (codeCompletionToggle.isChecked != savedState) {
                        codeCompletionToggle.isChecked = savedState
                    }
                    
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("AIPreferences", "Applying completion state change: $enabled")
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            android.util.Log.d("AIPreferences", "Re-enabled code completion")
        } else {
            codeCompletionManager?.cleanup()
            android.util.Log.d("AIPreferences", "Disabled code completion")
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        
        android.util.Log.d("AIPreferences", "Syncing toggle: saved=$savedState")
        
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        android.util.Log.d("AIPreferences", "Switching to provider: $providerId")
        
        if (aiAgent.setProvider(providerId)) {
            updateProviderDropdownSelection()
            updateModelDropdown()
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            
            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    android.util.Log.d("AIPreferences", "Reattached completion after provider change")
                }
            }
            
            showSnackbar("Switched to $providerName")
        } else {
            updateProviderDropdownSelection()
            updateModelDropdown()
            showSnackbar("⚠️ No configured API key/model for $providerName")
        }
    }

    private fun handleModelChange(modelName: String) {
        android.util.Log.d("AIPreferences", "Switching to model: $modelName")
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                android.util.Log.d("AIPreferences", "Reattached completion after model change")
            }
        }
        
        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}