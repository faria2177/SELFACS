/*
 * SELFACS contribution, GPL-3.0-or-later.
 */
package com.tom.rv2ide.utils

import androidx.annotation.StringRes
import com.tom.rv2ide.resources.R
import java.io.File

/** Read-only local toolchain inventory. Does not launch a shell or download anything. */
object SelfacsToolchainStatus {
  data class Check(@StringRes val label: Int, val available: Boolean, val detail: String)

  fun inspect(files: File, projects: File): List<Check> {
    val prefix = File(files, "usr")
    val home = File(files, "home")
    val shell = File(prefix, "bin/bash")
    val javaHomes = listOf("java-17-openjdk", "java-21-openjdk")
        .map { File(prefix, "lib/jvm/$it/bin/java") }
    val java = javaHomes.firstOrNull { it.isFile && it.canExecute() } ?: javaHomes.first()
    val platforms = File(home, "android-sdk/platforms")
    val installed = platforms.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "android.jar").isFile }
        .map { it.name }.sorted()
    val gradle = File(prefix, "bin/gradle")
    return listOf(
        Check(R.string.selfacs_diag_shell, shell.isFile && shell.canExecute(), shell.path),
        Check(R.string.selfacs_diag_java, java.isFile && java.canExecute(), java.path),
        Check(R.string.selfacs_diag_sdk, installed.isNotEmpty(),
            if (installed.isEmpty()) platforms.path else installed.joinToString(", ")),
        Check(R.string.selfacs_diag_gradle, gradle.isFile && gradle.canExecute(), gradle.path),
        Check(R.string.selfacs_diag_projects, projects.isDirectory && projects.canRead() && projects.canWrite(), projects.path),
    )
  }
}
