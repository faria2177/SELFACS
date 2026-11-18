package com.tom.rv2ide.artificial.review

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.widget.*
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File

object EditReviewDialog {
    suspend fun confirm(context: Context, root: File, edits: List<ProposedEdit>): Boolean =
        withContext(Dispatchers.Main) {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return@withContext false
            suspendCancellableCoroutine { continuation ->
                val column = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, 0)
                }
                val files = Spinner(context)
                files.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                    edits.map { (if (it.before == null) "NEW " else "EDIT ") + it.file.relativeTo(root.canonicalFile).path })
                val preview = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
                    setTextIsSelectable(true)
                }
                val scroll = ScrollView(context).apply {
                    addView(preview)
                    layoutParams = LinearLayout.LayoutParams(-1, (280 * resources.displayMetrics.density).toInt())
                }
                fun select(index: Int) {
                    val edit = edits[index]
                    preview.text = "BEFORE\n" + (edit.before ?: "(new file)") + "\n\nAFTER\n" + edit.after
                    scroll.scrollTo(0, 0)
                }
                files.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { select(position) }
                }
                column.addView(files)
                column.addView(scroll)
                select(0)
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle("Review ${edits.size} proposed files")
                    .setMessage("Select each file to inspect its before and after content. Reject if you have unsaved editor changes or do not trust the proposed code.")
                    .setView(column)
                    .setPositiveButton("Apply all") { _, _ -> if (continuation.isActive) continuation.resume(true) }
                    .setNegativeButton("Reject", null)
                    .create()
                dialog.setOnDismissListener { if (continuation.isActive) continuation.resume(false) }
                continuation.invokeOnCancellation { column.post { dialog.dismiss() } }
                dialog.show()
            }
        }
}
