package com.tom.rv2ide.fragments

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.github.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GitHubStudioFragment : Fragment() {
    private lateinit var tokenStore: GitHubTokenStore
    private lateinit var content: LinearLayout
    private lateinit var tokenInput: TextInputEditText
    private lateinit var status: TextView
    private lateinit var repositoryContainer: LinearLayout
    private var api: GitHubApiClient? = null
    private var repositories: List<GitHubRepository> = emptyList()
    private var busy = false
    private var pendingArtifact: PendingArtifact? = null

    private data class PendingArtifact(val repo: GitHubRepository, val artifact: GitHubArtifact)

    private val saveArtifact = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val pending = pendingArtifact
        pendingArtifact = null
        if (uri != null && pending != null) {
            runTask("Downloading ${pending.artifact.name}…", {
                val output = requireContext().contentResolver.openOutputStream(uri, "w")
                    ?: error("Cannot open selected destination")
                output.use { apiOrThrow().downloadArtifact(pending.repo, pending.artifact, it) }
            }) { bytes ->
                status.text = "Artifact saved (${formatBytes(bytes)}). ZIP contains the workflow output."
            }
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        tokenStore = GitHubTokenStore(requireContext())
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            addView(content)
        }
        addText("Manage your GitHub repositories, branches and Actions builds.", 14f, false)
        addSpace(12)
        val tokenCard = card()
        val tokenColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        tokenColumn.addView(TextView(requireContext()).apply {
            text = "Connect GitHub"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        tokenColumn.addView(TextView(requireContext()).apply {
            text = "Use a fine-grained Personal Access Token. Read: Metadata, Contents and Actions. Add Actions: write only if you want to dispatch workflows."
            textSize = 12f
            setTextColor(0xff777777.toInt())
        })
        val tokenLayout = TextInputLayout(requireContext()).apply {
            hint = "GitHub Personal Access Token"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        tokenInput = TextInputEditText(tokenLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine = true
        }
        tokenLayout.addView(tokenInput)
        tokenColumn.addView(tokenLayout)
        val tokenButtons = horizontal()
        tokenButtons.addView(button("Connect / Save") { connect(tokenInput.text?.toString().orEmpty().trim()) }, weight())
        tokenButtons.addView(button("Forget") { forgetToken() }, weight())
        tokenColumn.addView(tokenButtons)
        tokenCard.addView(tokenColumn)
        content.addView(tokenCard, matchWidth())

        status = TextView(requireContext()).apply {
            text = "Not connected"
            textSize = 13f
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        content.addView(status)
        val actions = horizontal()
        actions.addView(button("Refresh") { refreshRepositories() }, weight())
        actions.addView(button("Create Repository") {
            openUrl("https://github.com/new")
        }, weight())
        content.addView(actions)
        content.addView(TextView(requireContext()).apply {
            text = "Your repositories"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        })
        repositoryContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(repositoryContainer, matchWidth())
        addText("Security: the token is stored encrypted with Android Keystore. It is not placed in the project, source files or logs.", 12f, false)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingArtifact = savedInstanceState?.let {
            val owner = it.getString("artifact_owner")
            val name = it.getString("artifact_repo")
            val full = it.getString("artifact_full")
            val branch = it.getString("artifact_branch")
            val url = it.getString("artifact_url")
            val id = it.getLong("artifact_id", 0L)
            val artifactName = it.getString("artifact_name")
            val size = it.getLong("artifact_size", 0L)
            val expired = it.getBoolean("artifact_expired", false)
            if (owner != null && name != null && full != null && branch != null && url != null && id > 0) {
                PendingArtifact(GitHubRepository(owner, name, full, "", false, branch, url),
                    GitHubArtifact(id, artifactName.orEmpty(), size, expired, ""))
            } else null
        }
        val saved = runCatching { tokenStore.get() }.getOrNull()
        if (!saved.isNullOrBlank()) connect(saved, persist = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingArtifact?.let { p ->
            outState.putString("artifact_owner", p.repo.owner)
            outState.putString("artifact_repo", p.repo.name)
            outState.putString("artifact_full", p.repo.fullName)
            outState.putString("artifact_branch", p.repo.defaultBranch)
            outState.putString("artifact_url", p.repo.htmlUrl)
            outState.putLong("artifact_id", p.artifact.id)
            outState.putString("artifact_name", p.artifact.name)
            outState.putLong("artifact_size", p.artifact.sizeBytes)
            outState.putBoolean("artifact_expired", p.artifact.expired)
        }
        super.onSaveInstanceState(outState)
    }

    private fun connect(value: String, persist: Boolean = true) {
        if (!GitHubValidation.isValidToken(value)) {
            status.text = "Enter a valid token. Token was not saved."
            return
        }
        runTask("Checking GitHub credentials…", {
            val client = GitHubApiClient(value)
            val user = client.authenticatedUser()
            if (persist) tokenStore.save(value)
            client to user
        }) { pair ->
            api = pair.first
            status.text = "Connected as @${pair.second.login}. Token is stored securely."
            tokenInput.setText("")
            refreshRepositories()
        }
    }

    private fun forgetToken() {
        runCatching { tokenStore.clear() }.onFailure {
            status.text = "Could not clear secure token: ${it.message}"
            return
        }
        api = null
        repositories = emptyList()
        repositoryContainer.removeAllViews()
        tokenInput.setText("")
        status.text = "GitHub token forgotten on this device."
    }

    private fun refreshRepositories() {
        if (api == null) {
            status.text = "Connect a GitHub token first."
            return
        }
        runTask("Loading repositories…", { apiOrThrow().repositories() }) { items ->
            repositories = items
            repositoryContainer.removeAllViews()
            if (items.isEmpty()) {
                addText("No repositories returned. Check token repository access.", 13f, false)
            } else {
                items.forEach { addRepositoryCard(it) }
            }
            status.text = "Loaded ${items.size} repositories."
        }
    }

    private fun addRepositoryCard(repo: GitHubRepository) {
        val c = card()
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
        }
        val top = horizontal()
        top.addView(TextView(requireContext()).apply {
            text = repo.fullName
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(requireContext()).apply {
            text = if (repo.private) "PRIVATE" else "PUBLIC"
            textSize = 10f
        })
        column.addView(top)
        if (repo.description.isNotBlank()) column.addView(TextView(requireContext()).apply {
            text = repo.description
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        })
        column.addView(TextView(requireContext()).apply {
            text = "Default branch: ${repo.defaultBranch}"
            textSize = 12f
            setTextColor(0xff777777.toInt())
        })
        column.addView(button("Manage Repository") { showRepositoryActions(repo) })
        c.addView(column)
        c.setOnClickListener { showRepositoryActions(repo) }
        repositoryContainer.addView(c, matchWidth())
    }

    private fun showRepositoryActions(repo: GitHubRepository) {
        val options = arrayOf("Branches", "Workflows / Run Build", "Recent Runs", "Build Artifacts", "Open on GitHub")
        MaterialAlertDialogBuilder(requireContext()).setTitle(repo.fullName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBranches(repo)
                    1 -> showWorkflows(repo)
                    2 -> showRuns(repo)
                    3 -> showArtifacts(repo)
                    4 -> openUrl(repo.htmlUrl)
                }
            }.show()
    }

    private fun showBranches(repo: GitHubRepository) {
        runTask("Loading remote branches…", { apiOrThrow().branches(repo) }) { branches ->
            if (branches.isEmpty()) return@runTask statusMessage("No remote branches found.")
            val labels = branches.map { (if (it.name == repo.defaultBranch) "★ " else "") + it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Remote branches")
                .setItems(labels) { _, index ->
                    val branchPath = branches[index].name.split('/').joinToString("/") { Uri.encode(it) }
                    openUrl("${repo.htmlUrl}/tree/$branchPath")
                }.setNegativeButton("Close", null).show()
        }
    }

    private fun showWorkflows(repo: GitHubRepository) {
        runTask("Loading GitHub Actions workflows…", { apiOrThrow().workflows(repo) }) { workflows ->
            if (workflows.isEmpty()) return@runTask statusMessage("No workflows found in this repository.")
            val labels = workflows.map { "${it.name} · ${it.state}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Select workflow")
                .setItems(labels) { _, index -> chooseDispatchBranch(repo, workflows[index]) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun chooseDispatchBranch(repo: GitHubRepository, workflow: GitHubWorkflow) {
        runTask("Loading branches…", { apiOrThrow().branches(repo) }) { branches ->
            if (branches.isEmpty()) return@runTask statusMessage("No branch available for workflow dispatch.")
            val labels = branches.map { if (it.name == repo.defaultBranch) "${it.name} (default)" else it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Choose branch for ${workflow.name}")
                .setItems(labels) { _, index ->
                    val branch = branches[index].name
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Run GitHub Actions?")
                        .setMessage("Workflow: ${workflow.name}\nBranch: $branch\n\nThis will start a real workflow run.")
                        .setPositiveButton("Run workflow") { _, _ ->
                            runTask("Dispatching workflow…", {
                                apiOrThrow().dispatchWorkflow(repo, workflow, branch)
                                true
                            }) { statusMessage("Workflow dispatch accepted. Refresh Recent Runs in a few seconds.") }
                        }
                        .setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun showRuns(repo: GitHubRepository) {
        runTask("Loading workflow runs…", { apiOrThrow().runs(repo) }) { runs ->
            if (runs.isEmpty()) return@runTask statusMessage("No workflow runs found.")
            val labels = runs.map {
                val result = if (it.conclusion.isNotBlank()) it.conclusion else it.status
                "${it.name}\n${it.branch} · $result · ${it.createdAt}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Recent workflow runs")
                .setItems(labels) { _, index -> openUrl(runs[index].htmlUrl) }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun showArtifacts(repo: GitHubRepository) {
        runTask("Loading build artifacts…", { apiOrThrow().artifacts(repo) }) { artifacts ->
            val available = artifacts.filterNot { it.expired }
            if (available.isEmpty()) return@runTask statusMessage("No unexpired artifacts found.")
            val labels = available.map { "${it.name} · ${formatBytes(it.sizeBytes)}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Download build artifact ZIP")
                .setItems(labels) { _, index ->
                    val selected = available[index]
                    pendingArtifact = PendingArtifact(repo, selected)
                    val fileName = selected.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "github-artifact" } + ".zip"
                    saveArtifact.launch(fileName)
                }.setNegativeButton("Close", null).show()
        }
    }

    private fun apiOrThrow(): GitHubApiClient = api ?: error("Connect GitHub first.")

    private fun openUrl(url: String) {
        if (!url.startsWith("https://github.com/")) {
            statusMessage("Refused an untrusted GitHub URL.")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { statusMessage("Cannot open browser: ${it.message}") }
    }

    private fun statusMessage(message: String) { status.text = message }

    private fun <T> runTask(label: String, task: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = label
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { task() }
                busy = false
                success(result)
            } catch (error: Exception) {
                busy = false
                status.text = error.message ?: "GitHub operation failed."
            }
        }
    }

    private fun card(): MaterialCardView = MaterialCardView(requireContext()).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        setUseCompatPadding(true)
    }

    private fun button(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

    private fun horizontal() = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun addText(value: String, size: Float, bold: Boolean) {
        content.addView(TextView(requireContext()).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }, matchWidth())
    }
    private fun addSpace(height: Int) {
        content.addView(View(requireContext()), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MiB"
        bytes >= 1024L -> "${"%.1f".format(bytes / 1024.0)} KiB"
        else -> "$bytes B"
    }
}
