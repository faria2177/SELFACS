/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import android.content.SharedPreferences
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.handlers.AIRequestHandler
import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom.rv2ide.artificial.agentic.BuildErrors
import com.tom.rv2ide.artificial.agentic.BuildFixLoop
import com.tom.rv2ide.artificial.agentic.BuildLogCapture
import java.io.File

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ChatFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private lateinit var promptInput: TextInputEditText
    private lateinit var executeBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var statusText: MaterialTextView
    private lateinit var summaryText: MaterialTextView
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var fileModificationList: RecyclerView
    private lateinit var summaryCard: LinearLayout
    private lateinit var fileModificationAdapter: FileModificationAdapter
    
    private lateinit var codeCompletionManager: CodeCompletionManager
    private lateinit var aiRequestHandler: AIRequestHandler
    
    private var requestMode = 0
    private val modeNames = arrayOf("Propose code edits", "Ask / explain", "Code review", "Project analysis", "Analyze build error", "Fix last build error")
    private var history: com.tom.rv2ide.artificial.history.AIChatHistory? = null
    private var typingJob: Job? = null
    private var fileMonitorJob: Job? = null
    private var completionStateMonitorJob: Job? = null
    private var lastMonitoredFile: File? = null
    private var isSettingUpCompletion = false
    
    private val userRootProject = getProjectRoot().absolutePath.toString()
    
    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "code_completion_enabled") {
            val isEnabled = prefs.getBoolean(key, true)
            android.util.Log.d("ChatFragment", "Completion preference changed: $isEnabled")
            
            lifecycleScope.launch {
                handleCompletionStateChange(isEnabled)
            }
        }
    }

    companion object {
        fun newInstance(aiAgent: AIAgentManager): ChatFragment {
            return ChatFragment().apply {
                this.aiAgent = aiAgent
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::aiAgent.isInitialized) {
            aiAgent = AIAgentManager(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupRecyclerView()
        setupManagers()
        setupListeners()
        loadProject()
        registerPreferenceListener()
    }
    
    override fun onResume() {
        super.onResume()
        startFileMonitoring()
        startCompletionStateMonitoring()
    }
    
    override fun onPause() {
        super.onPause()
        stopFileMonitoring()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        promptInput = view.findViewById(R.id.anyText)
        executeBtn = view.findViewById(R.id.executeBtn)
        clearBtn = view.findViewById(R.id.clearBtn)
        statusText = view.findViewById(R.id.statusText)
        summaryText = view.findViewById(R.id.summaryText)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        fileModificationList = view.findViewById(R.id.fileModificationList)
        summaryCard = view.findViewById(R.id.summaryCard)
        addHistoryButton()
    }

    // ---------- per-project AI history ----------

    private fun addHistoryButton() {
        val row = clearBtn.parent as? android.view.ViewGroup ?: return
        val button = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "History"
            isAllCaps = false
            setOnClickListener { showHistory() }
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        row.addView(button, 0, lp)
    }

    private fun record(mode: String, prompt: String, outcome: com.tom.rv2ide.handlers.AIRequestHandler.Outcome) {
        val store = history ?: return
        val entry = com.tom.rv2ide.artificial.history.AIChatHistory.Entry(
            System.currentTimeMillis(), mode, prompt, outcome.text, outcome.appliedFiles, outcome.error
        )
        lifecycleScope.launch(Dispatchers.IO) { runCatching { store.add(entry) } }
    }

    private fun showHistory() {
        val store = history ?: run { showSnackbar("No project loaded."); return }
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { store.load() }
            if (!isAdded) return@launch
            if (entries.isEmpty()) { showSnackbar("No AI history for this project yet."); return@launch }
            val labels = entries.map { e ->
                val time = android.text.format.DateUtils.getRelativeTimeSpanString(e.time)
                val icon = when { e.error -> "❌"; e.appliedFiles > 0 -> "✅"; else -> "💬" }
                "$icon ${e.mode} · $time\n${e.prompt.lineSequence().firstOrNull().orEmpty().take(80)}"
            }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("AI history (${entries.size})")
                .setItems(labels) { _, which -> showHistoryEntry(entries[which]) }
                .setNeutralButton("Clear history") { _, _ ->
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Delete the saved AI history for this project?")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) { store.clear() }
                            showSnackbar("History cleared.")
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showHistoryEntry(e: com.tom.rv2ide.artificial.history.AIChatHistory.Entry) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = android.widget.TextView(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            setTextIsSelectable(true)
            this.text = buildString {
                append("Request:\n").append(e.prompt.ifBlank { "(no note)" })
                append("\n\n").append(if (e.error) "Error:" else "Answer:").append("\n").append(e.response)
                if (e.appliedFiles > 0) append("\n\nFiles changed: ${e.appliedFiles}")
            }
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(text) }
        val mode = modeNames.indexOf(e.mode)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("${e.mode} · ${android.text.format.DateUtils.getRelativeTimeSpanString(e.time)}")
            .setView(scroll)
            .setPositiveButton("Reuse request") { _, _ ->
                if (mode in 0..4) {
                    requestMode = mode
                    view?.findViewById<MaterialButton>(R.id.aiModeButton)?.text = modeNames[mode]
                    promptInput.setText(e.prompt)
                } else {
                    showSnackbar("Build fixes use the latest build log; choose \"Fix last build error\" again.")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupRecyclerView() {
        fileModificationAdapter = FileModificationAdapter()
        fileModificationList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = fileModificationAdapter
            isNestedScrollingEnabled = false
        }
        
        fileModificationAdapter.setOnItemClickListener { fileName ->
            openFileInEditor(fileName)
        }
    }

    private fun setupManagers() {
        codeCompletionManager = CodeCompletionManager.getInstance(
            requireContext(),
            lifecycleScope,
            aiAgent
        )
        
        aiRequestHandler = AIRequestHandler(
            lifecycleScope,
            aiAgent,
            statusText,
            summaryText,
            progressIndicator,
            executeBtn,
            fileModificationList,
            fileModificationAdapter,
            summaryCard,
            onFileOpen = { fileName ->
                openFileInEditor(fileName)
            },
            onTypeText = { text, delay -> typeText(text, delay) },
            getCurrentFile = { getCurrentFile() },
            refreshEditor = { refreshCurrentEditor() }
        )
    }

    private fun setupListeners() {
        val modes = modeNames
        val modeButton = requireView().findViewById<MaterialButton>(R.id.aiModeButton)
        modeButton.text = modes[requestMode]
        modeButton.setOnClickListener {
            if (!executeBtn.isEnabled) return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("AI task mode")
                .setSingleChoiceItems(modes, requestMode) { dialog, index ->
                    requestMode = index
                    modeButton.text = modes[index]
                    promptInput.hint = when (index) {
                        4 -> "Paste Gradle build error or log here"
                        5 -> "Optional note (the last build log is attached automatically)"
                        else -> "Describe your request"
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        executeBtn.setOnClickListener {
            val userRequest = promptInput.text.toString()

            if (requestMode == 5) {
                startBuildFix(userRequest, attempt = 1)
                return@setOnClickListener
            }

            if (userRequest.isBlank()) {
                showSnackbar("Please enter a request")
                return@setOnClickListener
            }
            
            codeCompletionManager.clearSuggestion()
            val prefix = when (requestMode) {
                1 -> "Explain without editing files. "
                2 -> "Review code for bugs, security, compatibility and maintainability; prioritize findings. "
                3 -> "Analyze architecture, modules, dependencies and risks; state missing context. "
                4 -> "Analyze pasted build log, root cause, affected files and exact suggested fixes. Do not claim a build ran. "
                else -> ""
            }
            val modeName = modeNames[requestMode]
            aiRequestHandler.execute(prefix + userRequest, allowEdits = requestMode == 0) { outcome ->
                record(modeName, userRequest, outcome)
            }
        }
    
        clearBtn.setOnClickListener {
            clearConversation()
        }
    }
    
    // ---------- AI build-fix loop (every write and every build needs user approval) ----------

    private fun startBuildFix(userNote: String, attempt: Int) {
        val snapshot = BuildLogCapture.snapshot()
        when (snapshot.state) {
            BuildLogCapture.State.FAILED -> Unit
            BuildLogCapture.State.RUNNING -> { showSnackbar("A build is still running. Wait for it to finish."); return }
            BuildLogCapture.State.SUCCESS -> { showSnackbar("The last build succeeded. Nothing to fix."); return }
            else -> {
                showSnackbar("No failed build in this session yet. Run a build first, or paste a log in \"Analyze build error\".")
                return
            }
        }
        codeCompletionManager.clearSuggestion()
        lifecycleScope.launch {
            // Save open editors first so the AI works on what is on disk and nothing stale overwrites its fix later.
            (activity as? com.tom.rv2ide.interfaces.IEditorHandler)?.saveAll(notify = false, requestSync = false)
            val prompt = BuildErrors.prompt(
                snapshot, BuildFixLoop.note(attempt, userNote))
            aiRequestHandler.execute(prompt, allowEdits = true) { outcome ->
                val label = if (attempt > 1) "attempt $attempt" else "attempt 1"
                record(modeNames[5], listOf(label, userNote.trim()).filter { it.isNotEmpty() }.joinToString(" · "), outcome)
                if (outcome.appliedFiles > 0 && isAdded) offerRebuild(snapshot.tasks, attempt)
            }
        }
    }

    private fun offerRebuild(previousTasks: List<String>, attempt: Int) {
        val tasks = BuildFixLoop.rebuildTasks(previousTasks)
        if (tasks.isEmpty()) {
            showSnackbar("Fix applied. Run the build again to verify.")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rebuild to verify the fix?")
            .setMessage("SELFACS will run the same Gradle task(s) as the failed build:\n\n${tasks.joinToString("\n")}\n\nNothing else is executed.")
            .setPositiveButton("Run build") { _, _ -> runRebuild(tasks, attempt) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun runRebuild(tasks: List<String>, attempt: Int) {
        val service = com.tom.rv2ide.lookup.Lookup.getDefault()
            .lookup(com.tom.rv2ide.projects.builder.BuildService.KEY_BUILD_SERVICE)
        if (service == null || !service.isToolingServerStarted()) {
            showSnackbar("Build service is not ready. Open the project and wait for sync, then build manually.")
            return
        }
        if (BuildLogCapture.snapshot().state ==
            BuildLogCapture.State.RUNNING) {
            showSnackbar("A build is already running.")
            return
        }
        executeBtn.isEnabled = false
        progressIndicator.visibility = View.VISIBLE
        statusText.text = "🔨 Rebuilding to verify the AI fix (attempt $attempt of ${BuildFixLoop.MAX_ATTEMPTS})…"
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { service.executeTasks(*tasks.toTypedArray()).get() }?.isSuccessful == true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ChatFragment", "Rebuild failed to run", e)
                false
            }
            if (!isAdded) return@launch
            executeBtn.isEnabled = true
            progressIndicator.visibility = View.GONE
            if (ok) {
                statusText.text = "✅ Build succeeded after the AI fix."
                return@launch
            }
            val failed = BuildLogCapture.snapshot().state ==
                BuildLogCapture.State.FAILED
            if (!failed) {
                statusText.text = "⚠️ The build could not run. Check the build output."
            } else if (BuildFixLoop.canRetry(attempt)) {
                statusText.text = "❌ Build still fails after attempt $attempt."
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Build still fails")
                    .setMessage("Let the AI analyse the new errors and propose another fix? (attempt ${attempt + 1} of ${BuildFixLoop.MAX_ATTEMPTS})")
                    .setPositiveButton("Try again") { _, _ -> startBuildFix("", attempt + 1) }
                    .setNegativeButton("Stop", null)
                    .show()
            } else {
                statusText.text = "❌ Build still fails after ${BuildFixLoop.MAX_ATTEMPTS} AI attempts. Review the build output; you can undo AI changes."
            }
        }
    }

    private fun registerPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private fun unregisterPreferenceListener() {
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }
    
    private suspend fun handleCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("ChatFragment", "handleCompletionStateChange: $enabled")
        
        if (enabled) {
            delay(200)
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Re-enabling completion for current file")
                setupCodeCompletionForCurrentFile()
            }
        } else {
            android.util.Log.d("ChatFragment", "Disabling completion")
            codeCompletionManager.cleanup()
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            var lastKnownState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                .getBoolean("code_completion_enabled", true)
            
            while (true) {
                delay(200)
                
                val currentState = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (currentState != lastKnownState) {
                    android.util.Log.d("ChatFragment", "State change detected in monitor: $lastKnownState -> $currentState")
                    lastKnownState = currentState
                    handleCompletionStateChange(currentState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private fun loadProject() {
        lifecycleScope.launch {
            try {
                val success = aiAgent.setProjectRoot(userRootProject)
                
                if (success) {
                    statusText.text = "Project loaded successfully"
                    val store = com.tom.rv2ide.artificial.history.AIChatHistory(requireContext(), userRootProject)
                    history = store
                    val last = withContext(Dispatchers.IO) { store.load().firstOrNull() }
                    if (last != null && isAdded) {
                        statusText.text = "Last AI answer (${android.text.format.DateUtils.getRelativeTimeSpanString(last.time)} · ${last.mode}):\n\n${last.response.take(4000)}\n\nTap History for earlier requests."
                    }
                } else {
                    statusText.text = "Failed to load project"
                }
            } catch (e: Exception) {
                statusText.text = "Error loading project: ${e.message}"
            }
        }
    }
    
    private fun startFileMonitoring() {
        stopFileMonitoring()
        
        fileMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                
                if (isSettingUpCompletion) {
                    continue
                }
                
                val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("code_completion_enabled", true)
                
                if (!isEnabled) {
                    continue
                }
                
                val currentFile = getCurrentFile()
                
                if (currentFile != null && currentFile != lastMonitoredFile) {
                    android.util.Log.d("ChatFragment", "File changed detected: ${currentFile.name}")
                    lastMonitoredFile = currentFile
                    setupCodeCompletionForCurrentFile()
                }
            }
        }
    }
    
    private fun stopFileMonitoring() {
        fileMonitorJob?.cancel()
        fileMonitorJob = null
    }
    
    private fun setupCodeCompletionForCurrentFile() {
        if (isSettingUpCompletion) {
            android.util.Log.d("ChatFragment", "Already setting up, skipping")
            return
        }
        
        val prefs = requireContext().getSharedPreferences("ai_preferences", android.content.Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("code_completion_enabled", true)
        
        if (!isEnabled) {
            android.util.Log.d("ChatFragment", "Code completion is disabled, skipping setup")
            return
        }
        
        isSettingUpCompletion = true
        
        lifecycleScope.launch {
            delay(200)
            
            val editor = getCurrentEditor()
            val suggestionView = getCurrentSuggestionView()
            
            if (editor != null && suggestionView != null) {
                android.util.Log.d("ChatFragment", "Setting up code completion")
                codeCompletionManager.setup(
                    editor,
                    suggestionView,
                    onReady = {
                        android.util.Log.d("ChatFragment", "✦ Code completion ready!")
                        isSettingUpCompletion = false
                    },
                    onError = { e ->
                        android.util.Log.e("ChatFragment", "✗ Completion setup failed: ${e.message}", e)
                        isSettingUpCompletion = false
                    }
                )
            } else {
                android.util.Log.w("ChatFragment", "Editor or SuggestionView is null, cannot setup")
                isSettingUpCompletion = false
            }
        }
    }
    
    fun getCodeCompletionManager(): CodeCompletionManager {
        return codeCompletionManager
    }

    private fun openFileInEditor(fileName: String) {
        if (userRootProject.isBlank()) {
            showSnackbar("Project path not set")
            return
        }
        
        lifecycleScope.launch {
            try {
                val file = findFileInProject(File(userRootProject), fileName)
                if (file == null) {
                    showSnackbar("File not found: $fileName")
                    return@launch
                }
                
                val activity = requireActivity()
                if (activity is EditorHandlerActivity) {
                    activity.openFile(file)
                    showSnackbar("Opened: ${file.name}")
                    
                    lastMonitoredFile = file
                    delay(500)
                    setupCodeCompletionForCurrentFile()
                }
            } catch (e: Exception) {
                showSnackbar("Error opening file: ${e.message}")
            }
        }
    }
    
    private fun findFileInProject(projectRoot: File, fileName: String): File? {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return null
        }
        
        return projectRoot.walkTopDown().firstOrNull { 
            it.isFile && it.name == fileName 
        }
    }

    private fun typeText(text: String, delayMs: Long = 10L) {
        typingJob?.cancel()
        typingJob = lifecycleScope.launch {
            try {
                val editor = getCurrentEditor() ?: return@launch
                val lines = text.lines()
                val currentText = StringBuilder()
                
                for (line in lines) {
                    val words = line.split(" ")
                    for (i in words.indices) {
                        currentText.append(words[i])
                        if (i < words.size - 1) {
                            currentText.append(" ")
                        }
                        editor.setText(currentText.toString())
                        delay(delayMs)
                    }
                    currentText.append("\n")
                    editor.setText(currentText.toString())
                }
            } catch (e: Exception) {
            }
        }
    }

    fun clearConversation() {
        lifecycleScope.launch {
            try {
                typingJob?.cancel()
                codeCompletionManager.clearSuggestion()
                aiAgent.clearConversation()
                
                promptInput.text?.clear()
                statusText.text = "Conversation cleared. Ready for new request."
                fileModificationList.visibility = View.GONE
                summaryCard.visibility = View.GONE
                fileModificationAdapter.clear()
                
                showSnackbar("Conversation cleared")
            } catch (e: Exception) {
                showSnackbar("Error clearing: ${e.message}")
            }
        }
    }

    private fun getCurrentEditor() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.editor
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun getCurrentFile() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.file
        } else null
    } catch (e: Exception) {
        null
    }
    
    private fun getCurrentSuggestionView() = try {
        val activity = requireActivity()
        if (activity is EditorHandlerActivity) {
            activity.getCurrentEditor()?.suggestionView
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun refreshCurrentEditor() {
        try {
            val activity = requireActivity()
            if (activity is EditorHandlerActivity) {
                val currentEditor = activity.getCurrentEditor()
                val file = currentEditor?.file
                val newContent = file?.readText()
                val editorText = currentEditor?.editor?.text

                if (editorText != null && newContent != null) {
                    editorText.replace(0, editorText.length, newContent)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun showSnackbar(message: String) {
        val anchorView = activity?.findViewById<View>(android.R.id.content) 
            ?: view 
            ?: return
        
        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        typingJob?.cancel()
        fileMonitorJob?.cancel()
        completionStateMonitorJob?.cancel()
        aiRequestHandler.cancel()
        unregisterPreferenceListener()
        super.onDestroyView()
    }
}