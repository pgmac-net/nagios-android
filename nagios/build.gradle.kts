// SPDX-License-Identifier: GPL-3.0-or-later

// The Nagios client: what Nagios sends, read; what Nagwatch asks of it, written. Plain Kotlin on the
// JVM, no Android, so its tests and the fuzzer run without Robolectric. See docs/adr/0015.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-test-fixtures`
    id("nagwatch.kotlin-quality")
}

// Fuzzing (docs/development.md, "Fuzzing"). The fuzzer is a plain Java program on a
// classpath of its own, so it never touches the module's or the unit tests'. The targets are
// ordinary test classes and need nothing from it: it finds them by name.
val fuzzer = configurations.create("fuzzer") {
    description = "Jazzer, the fuzzing engine. Test tooling: not in the APK."
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Part of the app, not published on its own: the release SBOM lists it under the app's group.
group = "net.pgmac"

// Coverage: the app's "app" report variant, the one Codecov reads, merges the variant of the same
// name from each dependency. Here that is this module's tests on the JVM.
kover {
    currentProject {
        createVariant("app") {
            add("jvm")
        }
    }
}

dependencies {
    // In its public API: the app builds the client with an OkHttpClient and reads JSON through it.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

    // The fuzzing engine itself; see the "fuzzer" configuration below.
    add("fuzzer", libs.jazzer)
}

// The graph captures live with the test fixtures, for the app's tests too. This module's tests read
// them from the directory as well, ahead of the fixtures jar, because the fuzz corpus test walks
// them as files and a resource inside a jar is not one.
sourceSets.test {
    resources.srcDir("src/testFixtures/resources")
}

tasks.test {
    // Opt-in test against a real Nagios; see docs/development.md. Never set in CI.
    val live = providers.gradleProperty("nagwatchLive").getOrElse("false")
    systemProperty("nagwatch.live", live)
    testLogging.showStandardStreams = live == "true"
}

val fuzzTargets = listOf("ResponseBody", "StatusParser", "AnnotationParser", "CommandPage", "Graph", "ListFetcher")

val fuzzTasks = fuzzTargets.map { name ->
    tasks.register<JavaExec>("fuzz$name") {
        group = "verification"
        description = "Fuzzes $name for -PfuzzSeconds (default 60). Crashes land in build/fuzz/$name/crashes."
        // The compiled client, the compiled targets, and everything they run against.
        classpath = files(fuzzer, tasks.test.map { it.classpath })
        mainClass = "com.code_intelligence.jazzer.Jazzer"

        val seconds = providers.gradleProperty("fuzzSeconds").getOrElse("60")
        val work = layout.buildDirectory.dir("fuzz/$name").get().asFile
        val seedDirs = listOf(
            "src/test/resources/fixtures",
            "src/test/resources/commandpages",
            "src/test/resources/fuzz/corpus",
            "src/testFixtures/resources/graphs",
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
    }
}

tasks.register("fuzz") {
    group = "verification"
    description = "Fuzzes every target in turn for -PfuzzSeconds each (default 60). Not part of check."
    dependsOn(fuzzTasks)
}

// One after another, so a crash in one is not hidden by a crash in the next being reported first.
// Set here and not inside the task above: a task may not configure others while it is being created.
fuzzTasks.zipWithNext().forEach { (first, second) -> second.configure { mustRunAfter(first) } }
