// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("fossCheck") {
            id = "nagwatch.foss-check"
            implementationClass = "net.pgmac.nagwatch.buildlogic.FossCheckPlugin"
        }
    }
}
