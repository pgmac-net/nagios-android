// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz

// The fuzz targets, one class each because Jazzer calls a single `fuzzerTestOneInput` on the
// class it is pointed at. They take the raw bytes: Jazzer accepts a `ByteArray` as well as its
// `FuzzedDataProvider`, and we have no use for carving the input into typed pieces.
//
// Run with `./gradlew :app:fuzz`, or one at a time with `:app:fuzz<Target>`.
// See docs/development.md, "Fuzzing".

/** The step that turns a response's text into JSON or a `NagiosError`. */
object ResponseBodyFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.responseBody(data)
}

/** The parsers for hosts, services and server info, and the classifier after them. */
object StatusParserFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.statusParser(data)
}

/** Reading the HTML pages `cmd.cgi` answers with, and the clock read from them. */
object CommandPageFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.commandPage(data)
}

/** What decides whether bytes are a graph, and whether text is a graph path. */
object GraphFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.graph(data)
}

/** The parsers for comments and downtimes. */
object AnnotationParserFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.annotationParser(data)
}

/** The list fetcher against a server that answers from the input: a conversation, not one response. */
object ListFetcherFuzzTarget {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) = FuzzEntryPoints.listFetcher(data)
}
