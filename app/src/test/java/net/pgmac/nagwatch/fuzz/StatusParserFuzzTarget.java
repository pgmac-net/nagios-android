// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Fuzz target for the parsers for hosts, services and server info, and the classifier after them. Run with <code>./gradlew :app:fuzz</code>, or on its own with
 * <code>./gradlew :app:fuzz -PfuzzTarget=StatusParser</code>. See docs/development.md, "Fuzzing".
 */
public final class StatusParserFuzzTarget {
    private StatusParserFuzzTarget() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        FuzzEntryPoints.statusParser(data.consumeRemainingAsBytes());
    }
}
