// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Walks the resolved dependency graph of each shipped classpath and fails on
 * any module whose group matches a denylist prefix, reporting the chain of
 * dependencies that pulled it in.
 */
abstract class CheckFossDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val denylistFile: RegularFileProperty

    @get:Input
    abstract val configurationNames: ListProperty<String>

    @get:Input
    abstract val rootComponents: ListProperty<ResolvedComponentResult>

    @TaskAction
    fun check() {
        val denied = readDenylist()
        val names = configurationNames.get()
        if (names.isEmpty()) {
            throw GradleException("FOSS check found no shipped classpaths to inspect; the check is misconfigured.")
        }

        val violations = sortedMapOf<String, String>()
        rootComponents.get().forEachIndexed { index, root ->
            findViolations(root, denied).forEach { (module, path) ->
                violations.putIfAbsent(module, "${names[index]}: $path")
            }
        }

        if (violations.isNotEmpty()) {
            val report = violations.entries.joinToString("\n") { (module, path) -> "  - $module\n      via $path" }
            throw GradleException(
                "$FAILURE_PREFIX ${violations.size} denied dependencies on a shipped classpath:\n" +
                    "$report\n" +
                    "Nagwatch ships FOSS-only dependencies. See CONTRIBUTING.md and ${FossCheckPlugin.DENYLIST_PATH}.",
            )
        }
        logger.lifecycle("FOSS check passed: ${names.size} classpaths, ${denied.size} denied prefixes.")
    }

    private fun readDenylist(): List<String> {
        val prefixes = denylistFile.get().asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        if (prefixes.isEmpty()) {
            throw GradleException("FOSS denylist ${FossCheckPlugin.DENYLIST_PATH} is empty; refusing to pass.")
        }
        return prefixes
    }

    /** Breadth-first so the reported path is a shortest one. */
    private fun findViolations(root: ResolvedComponentResult, denied: List<String>): Map<String, String> {
        val found = linkedMapOf<String, String>()
        val parents = hashMapOf<ResolvedComponentResult, ResolvedComponentResult?>(root to null)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            val id = component.id
            if (id is ModuleComponentIdentifier && denied.any { matches(id.group, it) }) {
                found.putIfAbsent(id.displayName, pathTo(component, parents))
            }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { edge ->
                if (edge.selected !in parents) {
                    parents[edge.selected] = component
                    queue.addLast(edge.selected)
                }
            }
        }
        return found
    }

    private fun pathTo(
        component: ResolvedComponentResult,
        parents: Map<ResolvedComponentResult, ResolvedComponentResult?>,
    ): String = generateSequence(component) { parents[it] }
        .map { it.id.displayName }
        .toList()
        .reversed()
        .joinToString(" -> ")

    companion object {
        const val FAILURE_PREFIX = "FOSS check failed:"

        fun matches(group: String, prefix: String): Boolean = group == prefix || group.startsWith("$prefix.")
    }
}
