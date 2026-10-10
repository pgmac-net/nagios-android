// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Registers the repository's own policy checks and hooks them into `check`:
 *
 * - `checkFossDependencies`: no shipped classpath may contain a denylisted group
 *   (see `config/foss-denylist.txt`).
 * - `checkSpdxHeaders`: every Kotlin source and build script carries the SPDX header.
 *
 * `-PfossSelfTest=true` adds a known-banned dependency so CI can prove the
 * dependency checks still fail when they should.
 */
class FossCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val fossCheck = project.tasks.register<CheckFossDependenciesTask>("checkFossDependencies") {
            group = "verification"
            description = "Fails if a shipped classpath contains a denylisted (non-FOSS or tracker) dependency."
            denylistFile.set(project.rootProject.layout.projectDirectory.file(DENYLIST_PATH))
            project.configurations
                .matching { isShippedClasspath(it.name) }
                .all { configuration ->
                    configurationNames.add(configuration.name)
                    rootComponents.add(configuration.incoming.resolutionResult.rootComponent)
                }
        }

        val spdxCheck = project.tasks.register<CheckSpdxHeadersTask>("checkSpdxHeaders") {
            group = "verification"
            description = "Fails if a Kotlin source or build script lacks the SPDX licence header."
            sources.from(
                project.rootProject.fileTree(project.rootProject.projectDir) { tree ->
                    tree.include("**/*.kt", "**/*.kts")
                    tree.exclude("**/build/**", "**/.gradle/**", ".claude/**", ".kotlin/**")
                },
            )
            rootDir.set(project.rootProject.layout.projectDirectory)
        }

        project.pluginManager.withPlugin("com.android.application") {
            project.tasks.named("check") { check -> check.dependsOn(fossCheck, spdxCheck) }
            if (isSelfTest(project)) {
                project.dependencies.add("implementation", SELF_TEST_DEPENDENCY)
            }
        }
    }

    companion object {
        const val DENYLIST_PATH = "config/foss-denylist.txt"
        const val SELF_TEST_PROPERTY = "fossSelfTest"

        /** Proprietary, and on the denylist: both dependency checks must reject it. */
        const val SELF_TEST_DEPENDENCY = "com.google.android.gms:play-services-base:18.11.0"

        fun isSelfTest(project: Project): Boolean =
            project.providers.gradleProperty(SELF_TEST_PROPERTY).map { it.toBoolean() }.getOrElse(false)

        /** Runtime classpaths of app variants; test classpaths never ship. */
        fun isShippedClasspath(configurationName: String): Boolean =
            configurationName.endsWith("RuntimeClasspath") && !configurationName.contains("Test")
    }
}
