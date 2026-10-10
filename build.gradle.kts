// SPDX-License-Identifier: GPL-3.0-or-later

// The plugins below bring their own libraries onto the build's classpath, and some of
// those have known vulnerabilities that the plugin's latest release has not picked up a
// fix for. These constraints raise them. They add nothing: a constraint only applies to
// a library something else already asks for. See "Security floors" in docs/development.md.
buildscript {
    dependencies {
        constraints {
            listOf(
                libs.floor.bcprov,
                libs.floor.bcpkix,
                libs.floor.bcutil,
                libs.floor.commons.lang3,
                libs.floor.httpclient,
                libs.floor.jdom2,
                libs.floor.jose4j,
                libs.floor.plexus.utils,
            ).forEach { floor ->
                add("classpath", floor) { because("security floor: the plugin asks for a version with a known vulnerability") }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.licensee) apply false
}
