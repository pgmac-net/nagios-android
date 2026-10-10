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
}
