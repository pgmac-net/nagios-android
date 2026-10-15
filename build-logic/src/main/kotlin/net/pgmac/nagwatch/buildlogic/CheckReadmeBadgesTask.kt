// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails if the README's Dependency-Track badge names a version other than `versionName`.
 *
 * Dependency-Track has no badge for "the latest version": its routes take a project name and a
 * version, or a UUID that belongs to one version. So the README pins the released version, and a
 * version bump that forgets the badge is caught here instead of showing "not found" to readers.
 */
abstract class CheckReadmeBadgesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readme: RegularFileProperty

    @get:Input
    abstract val versionName: Property<String>

    @TaskAction
    fun check() {
        val expected = versionName.get()
        val versions = BADGE.findAll(readme.get().asFile.readText()).map { it.groupValues[1] }.toList()
        if (versions.isEmpty()) {
            throw GradleException(
                "README badge check found no Dependency-Track vulnerability badge " +
                    "(.../api/v1/badge/vulns/project/<name>/<version>); the check is misconfigured or the badge was removed.",
            )
        }
        val wrong = versions.filter { it != expected }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "README badge check failed: the Dependency-Track badge names version ${wrong.joinToString()}, " +
                    "but versionName is $expected. Update the badge in README.md with the version bump.",
            )
        }
        logger.lifecycle("README badge check passed: Dependency-Track badge is $expected.")
    }

    companion object {
        private val BADGE = Regex("""/api/v1/badge/vulns/project/[^/\s)]+/([^/\s)?]+)""")
    }
}
