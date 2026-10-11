// SPDX-License-Identifier: GPL-3.0-or-later

import net.pgmac.nagwatch.buildlogic.FossCheckPlugin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.licensee)
    id("nagwatch.foss-check")
}

android {
    namespace = "net.pgmac.nagwatch"
    compileSdk = 37

    defaultConfig {
        // Changing applicationId after release creates a different app. See docs/adr/0003.
        applicationId = "net.pgmac.nagwatch"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Lets a debug build sit beside a release build on one device.
            applicationIdSuffix = ".debug"
        }
        release {
            // Unsigned until the release pipeline exists (M7).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Renovate owns version bumps; lint nagging about them would block unrelated PRs.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric's file-descriptor shadowing reaches into a JDK-internal package.
                test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // Opt-in test against a real Nagios; see docs/development.md. Never set in CI.
                val live = providers.gradleProperty("nagwatchLive").getOrElse("false")
                test.systemProperty("nagwatch.live", live)
                test.testLogging.showStandardStreams = live == "true"
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

// Tools that run during the build have classpaths of their own, separate from the app's
// and from the build script's. The same security floors are applied to them here.
// See "Security floors" in docs/development.md.
val toolFloors = mapOf(
    "androidLintTool" to listOf(
        libs.floor.bcprov,
        libs.floor.bcpkix,
        libs.floor.bcutil,
        libs.floor.commons.lang3,
        libs.floor.httpclient,
    ),
    "ktlint" to listOf(libs.floor.logback.core, libs.floor.logback.classic),
)
configurations.matching { it.name in toolFloors }.configureEach {
    val configurationName = name
    toolFloors.getValue(configurationName).forEach { floor ->
        project.dependencies.constraints.add(configurationName, floor) {
            because("security floor: the tool asks for a version with a known vulnerability")
        }
    }
}

// Fuzzing (docs/development.md, "Fuzzing"). The fuzzer is a plain Java program on a
// classpath of its own, so it never touches the app's or the unit tests'. The targets are
// ordinary test classes and need nothing from it: it finds them by name.
val fuzzer = configurations.create("fuzzer") {
    description = "Jazzer, the fuzzing engine. Test tooling: not in the APK."
    isCanBeConsumed = false
    isCanBeResolved = true
}

val fuzzTargets = listOf("ResponseBody", "StatusParser", "AnnotationParser", "CommandPage")

val fuzzTasks = fuzzTargets.map { name ->
    tasks.register<JavaExec>("fuzz$name") {
        group = "verification"
        description = "Fuzzes $name for -PfuzzSeconds (default 60). Crash inputs land in app/build/fuzz/$name/crashes."
        val unitTests = tasks.named<Test>("testDebugUnitTest")
        // The compiled app, the compiled targets, and everything they run against.
        classpath = files(fuzzer, unitTests.map { it.classpath })
        mainClass = "com.code_intelligence.jazzer.Jazzer"

        val seconds = providers.gradleProperty("fuzzSeconds").getOrElse("60")
        val work = layout.buildDirectory.dir("fuzz/$name").get().asFile
        val seedDirs = listOf(
            "src/test/resources/fixtures",
            "src/test/resources/commandpages",
            "src/test/resources/fuzz/corpus",
            "src/test/resources/fuzz/crashes",
        )
            .map { layout.projectDirectory.dir(it).asFile }
        val replay = providers.gradleProperty("fuzzReplay").orNull

        doFirst {
            // libFuzzer writes what it finds to the first corpus directory; keep that out of the repository.
            File(work, "corpus").mkdirs()
            File(work, "crashes").mkdirs()
        }
        argumentProviders += CommandLineArgumentProvider {
            buildList {
                add("--target_class=net.pgmac.nagwatch.fuzz.${name}FuzzTarget")
                // Only the code under test: coverage of the JDK and of OkHttp would drown it.
                add("--instrumentation_includes=net.pgmac.nagwatch.nagios.**:kotlinx.serialization.json.**")
                add("-artifact_prefix=${File(work, "crashes").path}/")
                // Without this a stand-alone Java reproducer is written to the working directory, which is the module.
                add("--reproducer_path=${File(work, "crashes").path}")
                // Whatever one input does, it must be quick and small. A hang is a finding.
                add("-timeout=10")
                // libFuzzer's resident-size limit is off: the JVM and Jazzer's native side sit at about 2.7 GB
                // before the first input, so it cannot tell the code's memory from theirs. The heap cap
                // below does that job, and turns a runaway into an OutOfMemoryError.
                add("-rss_limit_mb=0")
                add("-max_len=262144")
                if (replay != null) {
                    add(replay)
                } else {
                    add("-max_total_time=$seconds")
                    add(File(work, "corpus").path)
                    seedDirs.filter { it.isDirectory }.forEach { add(it.path) }
                }
            }
        }
        // A heap cap, so memory that one input makes the code ask for becomes an OutOfMemoryError
        // (a finding) and the collector runs. Left alone the JVM grows its heap into the machine's
        // RAM, and libFuzzer's resident-size limit then reports the JVM, not the code.
        maxHeapSize = "512m"
        // The JVM flag the unit tests need, for the same reason (Robolectric is on this classpath).
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

tasks.register("fuzz") {
    group = "verification"
    description = "Fuzzes every target in turn for -PfuzzSeconds each (default 60). Not part of check."
    dependsOn(fuzzTasks)
    // One after another, so a crash in one is not hidden by a crash in the next being reported first.
    fuzzTasks.zipWithNext().forEach { (first, second) -> second.configure { mustRunAfter(first) } }
}

// Locked so builds are reproducible and the FOSS checks see a fixed graph.
// Refresh with: ./gradlew :app:resolveAndLockAll --write-locks
// The self-test deliberately adds an unlocked, banned dependency.
if (!FossCheckPlugin.isSelfTest(project)) {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves every resolvable configuration; run with --write-locks to refresh gradle.lockfile."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time.")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks." }
    }
    doLast {
        // Graph resolution is what locking records; resolving files would need
        // artifact attributes that many AGP configurations only get from their tasks.
        configurations.filter { it.isCanBeResolved }.forEach { it.incoming.resolutionResult.root }
    }
}

ksp {
    // The schema of every database version is committed (app/schemas) so migrations can be tested.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

licensee {
    // GPL-3.0-compatible permissive licences only. Add one by PR, with the
    // dependency that needs it and why. See CONTRIBUTING.md.
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
}

ktlint {
    version = libs.versions.ktlint
    android = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

    // The fuzzing engine itself; see the "fuzzer" configuration above.
    add("fuzzer", libs.jazzer)
}
