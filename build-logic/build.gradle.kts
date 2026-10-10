// SPDX-License-Identifier: GPL-3.0-or-later

// Not `kotlin-dsl`: that plugin brings the Kotlin Gradle plugin of the version Gradle
// itself embeds, which trails the one this project uses and cannot be raised without a
// new Gradle. Using the project's own Kotlin plugin keeps one version of it in the build.
plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // For the Kotlin extensions on Gradle's API (`tasks.register<T>` and friends).
    implementation(gradleKotlinDsl())
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        register("fossCheck") {
            id = "nagwatch.foss-check"
            implementationClass = "net.pgmac.nagwatch.buildlogic.FossCheckPlugin"
        }
    }
}
