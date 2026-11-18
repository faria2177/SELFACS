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

package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.agentic.AgentTools
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

import android.content.Context
import com.tom.rv2ide.artificial.agents.google.Gemini
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.agents.anthropic.Anthropic
import com.tom.rv2ide.artificial.agents.grok.Grok
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.project.awareness.ProjectData
import com.tom.rv2ide.artificial.secrets.ApiKey
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tom.rv2ide.artificial.review.EditPlan
import com.tom.rv2ide.artificial.review.EditReviewDialog
import com.tom.rv2ide.artificial.review.ProposedEdit
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class AIAgentManager(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = Agents(context).getProvider()
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)
    private val requestMutex = Mutex()
    private val undoHistory = mutableListOf<ProposedEdit>()

    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        com.tom.rv2ide.artificial.agents.compatible.CloudProviders.register(context)
        
        permissionManager.setFileWriteEnabled(true)
        permissionManager.setRequireConfirmation(true)
        
        setProvider(currentProviderId)
    }
    
    fun getCurrentAgent(): AIAgent? = currentAgent

    fun setProvider(providerId: String): Boolean {
        android.util.Log.d("AIAgentManager", "setProvider called with: $providerId")
        
        val factory = AIAgentRegistry.getFactory(providerId)
        if (factory == null) {
            android.util.Log.e("AIAgentManager", "No factory found for provider: $providerId")
            return false
        }
        
        if (!factory.hasValidApiKey()) {
            android.util.Log.e("AIAgentManager", "No valid API key for provider: $providerId")
            return false
        }
        
        val candidate = factory.create(context)
        val apiKey = factory.getApiKey() ?: return false
        try {
            candidate.initialize(apiKey, context)
            candidate.setContext(context)
            currentProjectRoot?.let { root -> candidate.setProjectData(ProjectData(context).showProjectTree(root)) }
        } catch (_: Exception) { return false }
        if (!candidate.isInitialized()) return false
        currentProviderId = providerId
        currentAgent = candidate
        Agents(context).setProvider(providerId)
        return true
    }

    fun getCurrentProviderId(): String = currentProviderId
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }
    
    fun getAvailableProviders(): List<ProviderInfo> {
        return AIAgentRegistry.getAvailableProviders().mapNotNull { providerId ->
            val factory = AIAgentRegistry.getFactory(providerId)
            val agent = factory?.create(context)
            agent?.let {
                ProviderInfo(
                    id = it.providerId,
                    name = it.providerName,
                    isAvailable = factory.hasValidApiKey()
                )
            }
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.isDirectory) return false
        if (currentProjectRoot?.canonicalFile != projectRoot.canonicalFile) undoHistory.clear()

        currentProjectRoot = projectRoot
        val projectData = ProjectData(context)
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.getAllowedDirectories().forEach { permissionManager.removeAllowedDirectory(it) }
        permissionManager.addAllowedDirectory(projectRoot.canonicalPath)

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback, allowEdits: Boolean = true) = requestMutex.withLock {
        executeLocked(userRequest, callback, allowEdits)
    }

    private suspend fun executeLocked(userRequest: String, callback: AIAgentCallback, allowEdits: Boolean) {
        var success = false
        var lastError: Throwable? = null
        var providerSwitched = false
        val triedProviders = mutableSetOf<String>()

        if (currentAgent?.isInitialized() != true) {
            callback.onError("Configure an AI provider and model in AI Settings before sending a request.")
            return
        }
        currentAgent?.resetAttemptCount()
        callback.onProcessing("Analyzing your request...")

        while (!success && (currentAgent?.canRetry() == true)) {
            try {
                val currentAttempt = currentAgent?.getCurrentAttemptCount() ?: 0

                if (currentAttempt > 0 && !providerSwitched) {
                    callback.onRetry(currentAttempt, "Thinking differently...")
                    delay(1000)
                }

                val root = currentProjectRoot?.canonicalFile
                val previousFileStates = if (allowEdits && root != null) withContext(Dispatchers.IO) { EditPlan.snapshot(root) } else emptyMap()

                val basePrompt = if (allowEdits) userRequest else "READ-ONLY MODE: do not use FILE_TO_MODIFY; answer only with analysis.\n\n$userRequest"
                val result = runAgentLoop(basePrompt, root, callback)

                result.fold(
                    onSuccess = { response ->
                        ProviderHealth.mark(context, currentProviderId, true)
                        
                        if (allowEdits && response.lineSequence().any { it.startsWith("FILE_TO_MODIFY: ") }) {
                            success = true // Never automatically retry an approved write batch.
                            if (root == null) {
                                callback.onError("No active project.")
                            } else {
                                val edits = try {
                                    withContext(Dispatchers.IO) { EditPlan.parse(root, response, previousFileStates) }
                                } catch (e: CancellationException) { throw e }
                                  catch (e: Exception) {
                                    callback.onError("Proposal rejected: ${e.message}. No files written.")
                                    null
                                }
                                if (edits != null) when {
                                    edits.isEmpty() -> callback.onTextResponse("No changes proposed.", ModificationSummary(0, 0, 0, 0, 0, emptyList()))
                                    EditReviewDialog.confirm(context, root, edits) -> {
                                        val results = applyReviewedEdits(root, edits, callback)
                                        callback.onSuccess(response, results, createSummary(results))
                                    }
                                    else -> callback.onTextResponse("Changes rejected. No files written.", ModificationSummary(0, 0, 0, 0, 0, emptyList()))
                                }
                            }
                        } else {
                            callback.onTextResponse(response, ModificationSummary(0, 0, 0, 0, 0, emptyList()))
                            success = true
                        }
                        Unit
                    },
                  onFailure = { error ->
                      if (error is CancellationException) throw error
                      lastError = error
                      android.util.Log.e("AIAgentManager", "Error occurred: ${error.message}", error)
                      
                      val shouldSwitchProvider = error !is java.net.UnknownHostException && (
                          error is com.tom.rv2ide.artificial.exceptions.RateLimitException ||
                          error is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ||
                          error is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ||
                          error is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException ||
                          error is com.tom.rv2ide.artificial.exceptions.NonRetryableProviderException ||
                          error is IllegalStateException ||
                          error is java.io.IOException)

                      if (shouldSwitchProvider) {
                          val currentProviderName = currentAgent?.providerName ?: "Unknown"
                          val errorMsg = (error.message ?: "Unknown error").lineSequence().first().take(140)
                          triedProviders += currentProviderId
                          ProviderHealth.mark(context, currentProviderId, false)

                          if (providerSwitchDialog.isAutoSwitchEnabled()) {
                              callback.onProcessing("⚠️ $currentProviderName: $errorMsg")
                              var switched = false
                              for (candidate in getAlternativeProviders(triedProviders)) {
                                  triedProviders += candidate
                                  if (setProvider(candidate)) { switched = true; break }
                              }
                              if (switched) {
                                  providerSwitched = true
                                  currentAgent?.resetAttemptCount()
                                  callback.onProcessing("🔄 Switched to ${currentAgent?.providerName ?: "another provider"}")
                                  delay(800)
                              } else {
                                  val errorDisplay = formatErrorMessage(error)
                                  val tried = triedProviders.size
                                  callback.onError("$errorDisplay\n\n❌ No other working AI provider (tried $tried). Add another provider or more models, or use “Check all AI providers” in AI settings.")
                                  success = true
                              }
                          } else {
                              val errorDisplay = formatErrorMessage(error)
                              callback.onError("PROVIDER_SWITCH_REQUIRED::$errorDisplay")
                              success = true
                          }
                      } else if (error is com.tom.rv2ide.artificial.exceptions.NonRetryableProviderException) {
                          // Retrying the same request cannot help (unknown model, data policy, bad request).
                          callback.onError(formatErrorMessage(error))
                          success = true
                      } else if ((currentAgent?.canRetry() == true) && !providerSwitched) {
                          callback.onRetry(
                              currentAgent?.getCurrentAttemptCount() ?: 0,
                              "Error: ${error.message?.take(50) ?: "Unknown error"}. Retrying..."
                          )
                          currentAgent?.incrementAttemptCount()
                          delay(1500)
                      } else {
                          val errorDisplay = formatErrorMessage(error)
                          callback.onError(errorDisplay)
                          success = true
                      }
                  }
                )
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                lastError = e
                if (success) { callback.onError("Operation interrupted. Inspect any already applied files."); return }
                android.util.Log.e("AIAgentManager", "Exception occurred: ${e.message}", e)
                
                if (currentAgent?.canRetry() == true) {
                    callback.onRetry(
                        currentAgent?.getCurrentAttemptCount() ?: 0,
                        "Exception: ${e.message?.take(50) ?: "Unknown"}. Trying again..."
                    )
                    currentAgent?.incrementAttemptCount()
                    delay(1500)
                } else {
                    val errorDisplay = formatErrorMessage(e)
                    callback.onError(errorDisplay)
                    success = true
                }
            }
        }

        if (!success) {
          val attemptCount = currentAgent?.getCurrentAttemptCount() ?: 0
          val agentName = currentAgent?.providerName ?: "No agent initialized"
          val detail = lastError?.let { "\n\n" + formatErrorMessage(it) } ?: "\n\nPlease check your API key and model, then try again."
          callback.onError("Failed after $attemptCount attempts with $agentName.$detail")
        }
    }

    /**
     * Lets the model gather project information with read-only tool lines (READ_FILE / LIST_DIR /
     * SEARCH) for up to [AgentTools.MAX_ROUNDS] rounds before its final answer. No writes or commands.
     */
    private suspend fun runAgentLoop(basePrompt: String, root: File?, callback: AIAgentCallback): Result<String> {
        val agent = currentAgent ?: return Result.failure(Exception("No agent initialized"))
        val gathered = mutableListOf<Pair<AgentTools.Call, String>>()
        var latest = emptyList<Pair<AgentTools.Call, String>>()
        var round = 0
        while (true) {
            coroutineContext.ensureActive()
            val prompt = when {
                root == null -> basePrompt
                round == 0 -> basePrompt + "\n\n" + AgentTools.protocol(root, round)
                // Agents with their own history already saw the request and earlier results.
                agent.keepsConversation ->
                    AgentTools.observations(latest) + "\n" + AgentTools.protocol(root, round) +
                        "\nContinue the same task."
                else -> basePrompt + "\n\n" + AgentTools.protocol(root, round) + "\n" + AgentTools.observations(gathered)
            }
            val result = agent.generateCode(prompt = prompt, context = null, language = "kotlin", projectStructure = null)
            val response = result.getOrElse { return Result.failure(it) }
            val calls = if (root != null && round < AgentTools.MAX_ROUNDS - 1) AgentTools.parseCalls(response) else emptyList()
            if (calls.isEmpty()) return Result.success(response)
            val thisRound = mutableListOf<Pair<AgentTools.Call, String>>()
            for (call in calls) {
                callback.onProcessing(when (call.kind) {
                    AgentTools.Kind.READ_FILE -> "📖 Reading ${call.arg}"
                    AgentTools.Kind.LIST_DIR -> "📂 Listing ${call.arg}"
                    AgentTools.Kind.SEARCH -> "🔎 Searching \"${call.arg.take(40)}\""
                })
                val observation = withContext(Dispatchers.IO) { AgentTools.run(root!!, call) }
                gathered.removeAll { it.first == call }
                gathered.add(call to observation)
                thisRound.add(call to observation)
            }
            latest = thisRound
            round++
        }
    }

    private fun getAlternativeProvider(): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != currentProviderId }
    }

    /** Configured providers not yet tried, recently working ones first. */
    private fun getAlternativeProviders(exclude: Set<String>): List<String> =
        ProviderHealth.orderForSwitch(context, AIAgentRegistry.getAvailableProviders().filter { it !in exclude && it != currentProviderId })

    private suspend fun applyReviewedEdits(root: File, edits: List<ProposedEdit>, callback: AIAgentCallback): List<ModificationResult> {
        val results = mutableListOf<ModificationResult>()
        val writer = AIFileWriter(context)
        var stopped = false
        for (edit in edits) {
            callback.onFileModifying(edit.file.path, edit.file.name)
            val result = if (stopped) FileWriteResult.Error("Not applied after previous failure")
                else withContext(Dispatchers.IO) {
                    if (currentProjectRoot?.canonicalFile != root || !EditPlan.unchanged(root, edit))
                        FileWriteResult.Error("Project or file changed after review")
                    else writer.writeFile(edit.file.path, edit.after, createBackup = true)
                }
            val ok = result is FileWriteResult.Success
            if (ok) {
                undoHistory.add(edit)
                currentAgent?.recordModification(edit.file.path, edit.before, edit.after, true)
            } else stopped = true
            results.add(ModificationResult(edit.file.path, edit.after, ok,
                if (ok) "Applied" else "Not applied; inspect files", edit.before == null))
            callback.onFileModified(edit.file.path, edit.file.name, ok)
        }
        return results
    }

    /** Plain-language next step for common provider errors. */
    private fun providerHint(message: String): String {
        val m = message.lowercase()
        return when {
            "data policy" in m || "no endpoints found" in m || "privacy" in m ->
                "\n\nFix: on openrouter.ai open Settings → Privacy and allow the providers/training policy required by free models, or choose another model."
            "not a valid model" in m || "model not found" in m || "(404)" in m ->
                "\n\nFix: check the exact model ID (including any :free suffix) on the provider's models page."
            "context" in m && ("length" in m || "too long" in m || "maximum" in m) ->
                "\n\nFix: the request is too large for this model. Use a model with a larger context window."
            else -> ""
        }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)
        val providerName = currentAgent?.providerName ?: "Unknown"
        
        return when (error) {
            is com.tom.rv2ide.artificial.exceptions.RateLimitException -> 
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException -> 
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException -> 
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException -> 
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.NonRetryableProviderException ->
                "❌ REQUEST REJECTED BY $providerName\n\n$errorMessage" + providerHint(errorMessage)
            is IllegalStateException ->
                "❌ $providerName ERROR\n\n$errorMessage" + providerHint(errorMessage)
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else -> 
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
                val availableModels = agents.getModelsForProvider(providerId)
                if (availableModels.isNotEmpty()) {
                    agents.setAgent(availableModels[0])
                }
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(context)
                    val availableModels = agents.getModelsForProvider(alternativeProvider)
                    if (availableModels.isNotEmpty()) {
                        agents.setAgent(availableModels[0])
                    }
                    reinitializeWithSelectedModel()
                }
            }
        )
    }
    
    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }
    
    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    fun undoLastModification(): Boolean {
        if (requestMutex.isLocked) return false
        val edit = undoHistory.lastOrNull() ?: return false
        val root = currentProjectRoot?.canonicalFile ?: return false
        if (EditPlan.safeTarget(root, edit.file.path) != edit.file) return false
        return try {
            if (!edit.file.isFile || edit.file.readText() != edit.after) return false
            val ok = if (edit.before == null) edit.file.delete()
                else AIFileWriter(context).writeFile(edit.file.path, edit.before, createBackup = true) is FileWriteResult.Success
            if (ok) undoHistory.removeAt(undoHistory.lastIndex)
            ok
        } catch (_: Exception) { false }
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        factory?.getApiKey()?.let { apiKey ->
            currentAgent?.reinitializeWithNewModel(apiKey, context)
        }
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
        return agents.getAgent()
    }
    
    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return currentAgent?.getModificationHistory()?.map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = it.attemptNumber,
                success = it.success
            )
        } ?: emptyList()
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)
        fun onSuccess(response: String, modifications: List<ModificationResult>, summary: ModificationSummary)
        fun onTextResponse(response: String, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }
    
    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )
}

data class BaseFileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)