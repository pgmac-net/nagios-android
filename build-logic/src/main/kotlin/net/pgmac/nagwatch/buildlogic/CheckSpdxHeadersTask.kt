// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails if any Kotlin source or build script does not start with the SPDX licence header. */
abstract class CheckSpdxHeadersTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @TaskAction
    fun check() {
        val root = rootDir.get().asFile
        val files = sources.files
        if (files.isEmpty()) {
            throw GradleException("SPDX check found no source files to inspect; the check is misconfigured.")
        }
        val missing = files
            .filter { file -> file.useLines { lines -> lines.firstOrNull()?.trim() != HEADER } }
            .map { it.relativeTo(root).path }
            .sorted()

        if (missing.isNotEmpty()) {
            throw GradleException(
                "SPDX check failed: ${missing.size} files do not start with \"$HEADER\":\n" +
                    missing.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("SPDX check passed: ${files.size} files.")
    }

    companion object {
        const val HEADER = "// SPDX-License-Identifier: GPL-3.0-or-later"
    }
}
