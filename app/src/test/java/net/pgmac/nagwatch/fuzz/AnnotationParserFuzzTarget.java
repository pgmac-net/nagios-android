// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Fuzz target for the parsers for comments and downtimes. Run with <code>./gradlew :app:fuzz</code>, or on its own with
 * <code>./gradlew :app:fuzz -PfuzzTarget=AnnotationParser</code>. See docs/development.md, "Fuzzing".
 */
public final class AnnotationParserFuzzTarget {
    private AnnotationParserFuzzTarget() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        FuzzEntryPoints.annotationParser(data.consumeRemainingAsBytes());
    }
}
