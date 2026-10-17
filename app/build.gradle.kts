// SPDX-License-Identifier: GPL-3.0-or-later

import net.pgmac.nagwatch.buildlogic.CheckReadmeBadgesTask
import net.pgmac.nagwatch.buildlogic.FossCheckPlugin

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.licensee)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.kover)
    id("nagwatch.foss-check")
    id("nagwatch.kotlin-quality")
}

android {
    namespace = "net.pgmac.nagwatch"
    compileSdk = 37

    defaultConfig {
        // Changing applicationId after release creates a different app. See docs/adr/0003.
        applicationId = "net.pgmac.nagwatch"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.0"
    }

    buildTypes {
        debug {
            // Lets a debug build sit beside a release build on one device.
            applicationIdSuffix = ".debug"
        }
        release {
            // Unsigned here on purpose. The release workflow signs what this build produces, in a
            // job of its own, so the key is never present while Gradle runs. See docs/adr/0011.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    dependenciesInfo {
        // Otherwise the APK carries a list of its dependencies encrypted with a key only Google
        // holds: nobody else can read it, and F-Droid refuses an APK that has one. The readable
        // list is the SBOM attached to each release.
        includeInApk = false
        includeInBundle = false
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

androidComponents {
    // The release build's unit tests are off by default. The tests that pin what a release build
    // does not do (src/testRelease: no debug indicator) need them on; see testReleaseUnitTest below.
    beforeVariants(selector().withBuildType("release")) { builder ->
        builder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// The app's tests are written against the debug build (the Compose test activity is a debug-only
// library), so the release unit test task runs only the tests that are about the release build.
tasks.withType<Test>().matching { it.name == "testReleaseUnitTest" }.configureEach {
    filter.includeTestsMatching("net.pgmac.nagwatch.debug.DebugChromeTest")
}

// The SBOM attached to a release (docs/development.md, "Releasing"): what is in the APK and
// nothing else. Build tools, test libraries and the fuzzer are not shipped, so they are not listed.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("releaseRuntimeClasspath")
    componentGroup = "net.pgmac"
    componentName = "nagwatch"
    componentVersion = android.defaultConfig.versionName
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    includeBuildSystem = false
    jsonOutput = layout.buildDirectory.file("reports/sbom/nagwatch.cdx.json")
    xmlOutput.unsetConvention()
}

// The release workflow refuses a tag that does not say what this prints.
tasks.register("printVersionName") {
    group = "help"
    description = "Prints versionName and nothing else."
    val versionName = android.defaultConfig.versionName
    doLast { println(versionName) }
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
    // Ships the dependency report in the APK for the About screen's licence list.
    bundleAndroidAsset = true
}

dependencies {
    // The Nagios client, a plain Kotlin module of its own (docs/adr/0015).
    implementation(project(":nagios"))
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
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
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
    testImplementation(libs.work.testing)
    testImplementation(libs.glance.appwidget.testing)
    // The graph captures, for the graph screens' tests.
    testImplementation(testFixtures(project(":nagios")))

    // One coverage report for the app and the client together: the one Codecov reads.
    kover(project(":nagios"))
}

// Coverage is read, not enforced: it goes to Codecov as information (codecov.yml). Generated code is
// left out so the number is about code somebody wrote.
kover {
    // The report Codecov reads (koverXmlReportApp): the debug build's tests and, through the
    // variant of the same name in :nagios, the client's.
    currentProject {
        createVariant("app") {
            add("debug")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    "*_Factory",
                    "*_Factory\$*",
                    "*_HiltModules*",
                    "*_MembersInjector",
                    "Hilt_*",
                    "dagger.hilt.*",
                    "hilt_aggregated_deps.*",
                    "*_Impl",
                    "*_Impl\$*",
                    "*.BuildConfig",
                    "*ComposableSingletons*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}

// The README's Dependency-Track badge names a version, and Dependency-Track has no "latest".
tasks.register<CheckReadmeBadgesTask>("checkReadmeBadges") {
    group = "verification"
    description = "Fails if the README's Dependency-Track badge is not for versionName."
    readme.set(rootProject.layout.projectDirectory.file("README.md"))
    versionName.set(android.defaultConfig.versionName)
}
