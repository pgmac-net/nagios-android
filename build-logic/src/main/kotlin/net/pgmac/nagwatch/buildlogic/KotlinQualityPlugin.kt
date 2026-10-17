// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.buildlogic

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * What every Kotlin module of the app holds itself to, in one place (pgmac-net/nagios-android#115):
 *
 * - Java 21 to build with, Java 17 bytecode, and every compiler warning an error.
 * - detekt with the project's configuration, and ktlint.
 * - The security floors for the tools those bring (docs/development.md, "Security floors").
 * - Locked dependencies, refreshed with `./gradlew resolveAndLockAll --write-locks`.
 *
 * The module applies its Kotlin plugin (Kotlin/JVM, or Android's built-in Kotlin) itself.
 */
class KotlinQualityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                kotlinToolchain()
                // Java's own target must agree with Kotlin's; Android sets it in compileOptions.
                extensions.configure<JavaPluginExtension> {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            pluginManager.withPlugin("com.android.base") { kotlinToolchain() }
            tasks.withType<KotlinJvmCompile>().configureEach { task ->
                task.compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(true)
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                config.setFrom(rootProject.file("config/detekt/detekt.yml"))
                source.setFrom(
                    listOf("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin")
                        .filter { file(it).isDirectory },
                )
            }
            extensions.configure<KtlintExtension> {
                version.set(libs.findVersion("ktlint").get().requiredVersion)
                android.set(false)
            }
            pluginManager.withPlugin("com.android.base") {
                extensions.configure<KtlintExtension> { android.set(true) }
            }

            // Tools that run during the build have classpaths of their own, separate from the
            // module's and from the build script's. The same security floors are applied to them.
            val toolFloors = mapOf(
                "androidLintTool" to listOf("floor-bcprov", "floor-bcpkix", "floor-bcutil", "floor-commons-lang3", "floor-httpclient"),
                "ktlint" to listOf("floor-logback-core", "floor-logback-classic"),
            )
            configurations.matching { it.name in toolFloors }.configureEach { configuration ->
                toolFloors.getValue(configuration.name).forEach { floor ->
                    dependencies.constraints.add(configuration.name, libs.findLibrary(floor).get()) {
                        it.because("security floor: the tool asks for a version with a known vulnerability")
                    }
                }
            }

            // Locked so builds are reproducible and the FOSS checks see a fixed graph.
            // The self-test deliberately adds an unlocked, banned dependency.
            if (!FossCheckPlugin.isSelfTest(project)) {
                dependencyLocking { it.lockAllConfigurations() }
            }
            tasks.register("resolveAndLockAll") { task ->
                task.group = "help"
                task.description = "Resolves every resolvable configuration; run with --write-locks to refresh gradle.lockfile."
                task.notCompatibleWithConfigurationCache("Resolves configurations at execution time.")
                task.doFirst {
                    require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks." }
                }
                task.doLast {
                    // Graph resolution is what locking records; resolving files would need
                    // artifact attributes that many AGP configurations only get from their tasks.
                    configurations.filter { it.isCanBeResolved }.forEach { it.incoming.resolutionResult.root }
                }
            }
        }
    }

    private fun Project.kotlinToolchain() {
        extensions.getByType<KotlinProjectExtension>().jvmToolchain(JAVA_TOOLCHAIN)
    }

    private companion object {
        /** The JDK that builds the app. CI's setup-java must name the same one. */
        const val JAVA_TOOLCHAIN = 21
    }
}
