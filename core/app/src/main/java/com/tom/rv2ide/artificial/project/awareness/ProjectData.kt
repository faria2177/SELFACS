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

package com.tom.rv2ide.artificial.project.awareness

import android.content.Context
import java.io.File

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class ProjectData(ctx: Context) {
    
    // This helps the ai to see full paths of files 
    fun showProjectTree(proj: File): ProjectTreeResult {
        val sb = StringBuilder()
    
        val base = proj.canonicalFile
        var entries = 0
        fun walk(dir: File) {
            if (entries >= 12000) return
            sb.appendLine(dir.canonicalPath)
            entries++
            dir.listFiles()?.forEach { file ->
                if (entries >= 12000) return@forEach
                if (file.name.startsWith(".") || file.name in setOf("build", "node_modules", "signing")) return@forEach
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                if (canonical.path != file.absolutePath || !canonical.path.startsWith(base.path + File.separator)) return@forEach
                if (file.isDirectory) walk(file)
                else { sb.appendLine(canonical.path); entries++ }
            }
        }
    
        walk(base)
    
        return ProjectTreeResult(sb.toString(), proj)
    }
}

class ProjectTreeResult(
    val tree: String,
    private val root: File
) {
    val projectRoot: File get() = root

    fun getFileByName(filename: String): String? {

        fun search(dir: File): File? {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val found = search(file)
                    if (found != null) return found
                } else if (file.name == filename) {
                    return file
                }
            }
            return null
        }

        return search(root)?.canonicalPath
    }

    fun readFileContent(filename: String): String? {
        val path = getFileByName(filename) ?: return null
        return File(path).readText()
    }
}