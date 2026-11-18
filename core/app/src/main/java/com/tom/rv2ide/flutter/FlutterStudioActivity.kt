package com.tom.rv2ide.flutter

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import android.view.Gravity
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import java.io.File

/** Flutter editing workspace. SDK execution is delegated to the exported CI workflow. */
class FlutterStudioActivity : AppCompatActivity() {
    private val root by lazy { File(filesDir, "selfacs-flutter").apply { mkdirs() } }
    private lateinit var editor: CodeEditor
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private lateinit var toolbar: MaterialToolbar
    private var project: File? = null
    private var relative: String? = null
    private var baseline: String? = null
    private var busy = false
    private var pendingImportName: String? = null
    private var pendingExportProject: String? = null

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = pendingImportName
        pendingImportName = null
        if (uri != null && name != null) runTask("Importing ZIP…", {
            contentResolver.openInputStream(uri)?.use { FlutterWorkspace.importZip(root, name, it) }
                ?: error("Cannot read ZIP")
        }) { openProject(it) }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val name = pendingExportProject
        pendingExportProject = null
        if (uri != null && name != null) runTask("Exporting ZIP…", {
            val folder = FlutterWorkspace.safeTarget(root, name)
            contentResolver.openOutputStream(uri, "wt")?.use { FlutterWorkspace.exportZip(folder, it) }
                ?: error("Cannot write ZIP")
        }) { status.text = "ZIP exported. Extract and upload to your GitHub repository." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        pendingImportName = savedInstanceState?.getString("pendingImport")
        pendingExportProject = savedInstanceState?.getString("pendingExport")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            title = "Flutter & Dart Studio"
            subtitle = "Source workspace · remote APK build"
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@FlutterStudioActivity, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        content.addView(toolbar)
        val commands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) {
            commands.addView(MaterialButton(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { if (!busy) action() }
            })
        }
        button("New") { confirmDiscard { askName("New Flutter project") { name ->
            runTask("Creating project…", { FlutterWorkspace.create(root, name) }) { openProject(it) }
        } } }
        button("Open") { confirmDiscard { chooseProject() } }
        button("Import ZIP") { confirmDiscard { askName("Import workspace name") { name ->
            pendingImportName = name
            importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        } } }
        button("Files") { confirmDiscard { chooseFile() } }
        button("New file") { confirmDiscard { createFile() } }
        button("Save") { saveFile() }
        button("Build setup") { confirmDiscard { setupBuild() } }
        button("Export ZIP") { confirmDiscard {
            val folder = project ?: return@confirmDiscard message("Open a project first.")
            MaterialAlertDialogBuilder(this).setTitle("Export project")
                .setMessage("The ZIP excludes common generated and secret files, but source may contain other credentials. Review before sharing. Existing workflow files are included.")
                .setPositiveButton("Export") { _, _ ->
                    pendingExportProject = folder.name
                    exportPicker.launch(folder.name + ".zip")
                }.setNegativeButton("Cancel", null).show()
        } }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(commands) })
        fileLabel = TextView(this).apply { text = "No file open"; setPadding(16, 8, 16, 8) }
        content.addView(fileLabel)
        editor = CodeEditor(this).apply { setText(""); isEnabled = false }
        content.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "Create, open or import a Flutter project."; setPadding(16, 12, 16, 12) }
        content.addView(status)
        setContentView(content)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!busy) confirmDiscard { finish() }
            }
        })
        val restored = savedInstanceState?.getString("project")
        if (restored != null) {
            val folder = FlutterWorkspace.projects(root).firstOrNull { it.name == restored }
            if (folder != null) {
                project = folder
                toolbar.subtitle = folder.name
                val path = savedInstanceState.getString("relative")
                if (path != null) openFile(path)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("project", project?.name)
        outState.putString("relative", relative)
        outState.putString("pendingImport", pendingImportName)
        outState.putString("pendingExport", pendingExportProject)
        // Avoid placing source code in the saved-state Bundle. Keep the last saved file on disk.
        super.onSaveInstanceState(outState)
    }

    private fun askName(title: String, action: (String) -> Unit) {
        val input = EditText(this).apply { hint = "my_flutter_app"; setSingleLine(true) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setPositiveButton("Continue", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (!FlutterWorkspace.validName(name)) input.error = "Use lowercase letters, digits and underscores"
                else { dialog.dismiss(); action(name) }
            }
        }
        dialog.show()
    }

    private fun chooseProject() {
        val folders = FlutterWorkspace.projects(root)
        if (folders.isEmpty()) return message("No local Flutter workspaces yet.")
        MaterialAlertDialogBuilder(this).setTitle("Open Flutter workspace")
            .setItems(folders.map { it.name }.toTypedArray()) { _, index -> openProject(folders[index]) }.show()
    }
    private fun openProject(folder: File) {
        project = folder
        relative = null
        baseline = null
        editor.setText("")
        editor.isEnabled = false
        toolbar.subtitle = folder.name
        fileLabel.text = "No file open"
        if (File(folder, "lib/main.dart").isFile) openFile("lib/main.dart") else chooseFile()
    }
    private fun chooseFile() {
        val folder = project ?: return message("Open a project first.")
        runTask("Reading files…", { FlutterWorkspace.files(folder) }) { paths ->
            if (paths.isEmpty()) message("No supported text source files.")
            else MaterialAlertDialogBuilder(this).setTitle("Project files")
                .setItems(paths.toTypedArray()) { _, index -> openFile(paths[index]) }.show()
        }
    }
    private fun openFile(path: String) {
        val folder = project ?: return
        runTask("Opening file…", { FlutterWorkspace.read(folder, path) }) { text ->
            relative = path
            baseline = text
            editor.setText(text)
            editor.isEnabled = true
            fileLabel.text = path
            status.text = "Editing $path"
        }
    }
    private fun createFile() {
        val folder = project ?: return message("Open a project first.")
        val input = EditText(this).apply { hint = "lib/screens/home.dart"; setSingleLine(true) }
        MaterialAlertDialogBuilder(this).setTitle("New source file").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val path = input.text.toString().trim()
                runTask("Creating file…", { FlutterWorkspace.save(folder, path, "", null) }) { openFile(path) }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun saveFile() {
        val folder = project ?: return message("Open a project first.")
        val path = relative ?: return message("Open a file first.")
        val expected = baseline ?: return
        val text = editor.text.toString()
        runTask("Saving…", { FlutterWorkspace.save(folder, path, text, expected) }) {
            baseline = text
            status.text = "Saved $path"
        }
    }
    private fun confirmDiscard(action: () -> Unit) {
        if (relative == null || editor.text.toString() == baseline) { action(); return }
        MaterialAlertDialogBuilder(this).setTitle("Unsaved source changes")
            .setMessage("Save first or discard changes before continuing.")
            .setPositiveButton("Discard") { _, _ ->
                editor.setText(baseline.orEmpty())
                action()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun setupBuild() {
        val folder = project ?: return message("Open a project first.")
        MaterialAlertDialogBuilder(this).setTitle("GitHub Actions APK build")
            .setMessage("Generate a workflow that runs Flutter stable, initializes Android scaffolding if absent, analyzes and tests the project, and uploads a debug APK. Export the ZIP, review the code, extract it into your GitHub repository, then use Actions. This device does not contain a Flutter SDK and does not start a remote job automatically.")
            .setPositiveButton("Generate workflow") { _, _ ->
                runTask("Generating workflow…", { FlutterWorkspace.addBuildWorkflow(folder) }) {
                    status.text = "Build workflow added. Export ZIP and push to GitHub."
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun <T> runTask(label: String, work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        editor.isEnabled = false
        status.text = label
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                busy = false
                editor.isEnabled = relative != null
                done(result)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                busy = false
                editor.isEnabled = relative != null
                status.text = e.message ?: "Operation failed"
                message(status.text.toString())
            }
        }
    }
}
