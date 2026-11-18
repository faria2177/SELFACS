package com.tom.rv2ide.handlers

import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AIRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiAgent: AIAgentManager,
    private val statusText: MaterialTextView,
    private val summaryText: MaterialTextView,
    private val progressIndicator: CircularProgressIndicator,
    private val executeBtn: MaterialButton,
    private val fileModificationList: RecyclerView,
    private val fileModificationAdapter: FileModificationAdapter,
    private val summaryCard: LinearLayout,
    private val onFileOpen: (String) -> Unit,
    private val onTypeText: (String, Long) -> Unit,
    private val getCurrentFile: () -> File?,
    private val refreshEditor: () -> Unit
) {
    
    private var executionJob: Job? = null
    /** How a request ended. [appliedFiles] counts files written after user approval. */
    data class Outcome(val appliedFiles: Int, val text: String, val error: Boolean)

    private var onFinished: ((Outcome) -> Unit)? = null

    /** @param onDone called on the main thread when the request ends. */
    fun execute(userRequest: String, allowEdits: Boolean = true, onDone: ((Outcome) -> Unit)? = null) {
        executionJob?.cancel()
        onFinished = onDone
        executionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = false
                    progressIndicator.visibility = View.VISIBLE
                    summaryCard.visibility = View.GONE
                    fileModificationAdapter.clear()
                    fileModificationList.visibility = View.GONE
                }
                
                executeAIRequest(userRequest, allowEdits)
                
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = true
                    progressIndicator.visibility = View.GONE
                    statusText.text = "❌ Error: ${e.message}"
                    finish(Outcome(0, "Error: ${e.message}", true))
                }
            }
        }
    }
    
    private suspend fun executeAIRequest(userRequest: String, allowEdits: Boolean) {
        aiAgent.executeRequest(userRequest, object : AIAgentManager.AIAgentCallback {
            override fun onProcessing(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = message
                }
            }

            override fun onFileModifying(filePath: String, fileName: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    if (fileModificationList.visibility == View.GONE) {
                        fileModificationList.visibility = View.VISIBLE
                    }
                    fileModificationAdapter.addItem(fileName)
                }
            }

            override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                lifecycleScope.launch(Dispatchers.Main) {
                    fileModificationAdapter.updateItemStatus(fileName, success)
                    
                    if (getCurrentFile()?.name == fileName && success) {
                        refreshEditor()
                    }
                }
            }

            override fun onSuccess(
                response: String,
                modifications: List<AIAgentManager.ModificationResult>,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleSuccess(response, modifications, summary)
                }
            }

            override fun onTextResponse(
                response: String,
                summary: AIAgentManager.ModificationSummary
            ) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleTextResponse(response)
                }
            }

            override fun onError(message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    handleError(message)
                }
            }

            override fun onRetry(attemptNumber: Int, message: String) {
                lifecycleScope.launch(Dispatchers.Main) {
                    statusText.text = "🔄 Retry #$attemptNumber: $message"
                }
            }
        }, allowEdits = allowEdits)
    }
    
    private fun handleSuccess(
        response: String,
        modifications: List<AIAgentManager.ModificationResult>,
        summary: AIAgentManager.ModificationSummary
    ) {
        progressIndicator.visibility = View.GONE
        statusText.text = if (summary.failedFiles == 0) "✅ Approved changes applied" else "⚠️ Some changes were not applied. Review summary."
        summaryText.text = buildSummaryText(summary)
        summaryCard.visibility = View.VISIBLE
        
        if (modifications.isNotEmpty()) {
            val firstMod = modifications.firstOrNull { it.success }
            val file = firstMod?.let { File(it.filePath) }
            if (file != null && file.exists()) {
                onFileOpen(file.name)
            }
        }
        
        executeBtn.isEnabled = true
        finish(Outcome(summary.successfulFiles, buildSummaryText(summary), false))
    }

    private fun finish(outcome: Outcome) {
        val callback = onFinished
        onFinished = null
        callback?.invoke(outcome)
    }

    private fun handleTextResponse(response: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        statusText.text = response
        summaryCard.visibility = View.GONE
        fileModificationList.visibility = View.GONE
        finish(Outcome(0, response, false))
    }
    
    private fun handleError(message: String) {
        progressIndicator.visibility = View.GONE
        executeBtn.isEnabled = true
        
        statusText.text = """
❌ ERROR OCCURRED

$message

Please check the error message and try again.
        """.trimIndent()
        finish(Outcome(0, message, true))
    }
    
    private fun buildSummaryText(summary: AIAgentManager.ModificationSummary): String {
        val builder = StringBuilder()
        builder.append("📊 Total Files: ${summary.totalFiles}\n")
        builder.append("✅ Successful: ${summary.successfulFiles}\n")
        if (summary.failedFiles > 0) {
            builder.append("❌ Failed: ${summary.failedFiles}\n")
        }
        builder.append("🆕 New Files: ${summary.newFiles}\n")
        builder.append("✏️ Modified Files: ${summary.modifiedFiles}\n\n")
        
        builder.append("Files:\n")
        summary.fileDetails.forEach { detail ->
            val icon = if (detail.status == AIAgentManager.FileStatus.SUCCESS) "✅" else "❌"
            val type = if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created" else "Modified"
            builder.append("$icon $type: ${detail.fileName}\n")
        }
        
        return builder.toString()
    }
    
    fun cancel() {
        onFinished = null
        executionJob?.cancel()
    }
}