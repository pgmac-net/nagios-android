// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Fuzz target for the step that turns a response's text into JSON or a NagiosError. Run with <code>./gradlew :app:fuzz</code>, or on its own with
 * <code>./gradlew :app:fuzz -PfuzzTarget=ResponseBody</code>. See docs/development.md, "Fuzzing".
 */
public final class ResponseBodyFuzzTarget {
    private ResponseBodyFuzzTarget() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        FuzzEntryPoints.responseBody(data.consumeRemainingAsBytes());
    }
}
