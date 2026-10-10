// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays every known input through every fuzz entry point, on every build.
 *
 * The inputs are the sanitised fixtures, a small hand-made corpus, and every input
 * a fuzzer ever crashed on (`src/test/resources/fuzz/crashes`). The fuzzer finds new
 * problems; this keeps the old ones from coming back, and costs seconds.
 *
 * A failure here names the file. Replaying one by hand:
 * `./gradlew :app:fuzz -PfuzzTarget=<Name> -PfuzzReplay=<path>`.
 */
class FuzzCorpusTest {
    @Test
    fun `every known input is handled by every entry point without an exception`() {
        val inputs = FuzzCorpus.inputs()
        assertTrue("the corpus is empty: ${FuzzCorpus.directories()}", inputs.isNotEmpty())
        val failures = inputs.flatMap { file ->
            FuzzCorpus.entryPoints.mapNotNull { (name, entry) ->
                runCatching { entry(file.readBytes()) }.exceptionOrNull()?.let { "$name on ${file.name}: $it" }
            }
        }
        assertTrue("these inputs got an exception out:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the saved crash inputs are all still handled`() {
        FuzzCorpus.crashes().forEach { file ->
            FuzzCorpus.entryPoints.forEach { (name, entry) ->
                val outcome = runCatching { entry(file.readBytes()) }
                assertTrue("$name on saved crash ${file.name}: ${outcome.exceptionOrNull()}", outcome.isSuccess)
            }
        }
    }

    @Test
    fun `an entry point survives input that is not JSON, not UTF-8 and not small`() {
        val awkward = listOf(
            ByteArray(0),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00),
            "{".toByteArray(),
            "[".repeat(100_000).toByteArray(),
            "{\"a\":".repeat(20_000).toByteArray(),
            "\"\\u".toByteArray(),
            "{\"result\":{\"type_code\":99999999999999999999}}".toByteArray(),
        )
        awkward.forEach { input -> FuzzCorpus.entryPoints.forEach { (_, entry) -> entry(input) } }
    }
}

/** Where the inputs are, shared by the replay test. The Gradle `fuzz` task reads the same places. */
object FuzzCorpus {
    val entryPoints: List<Pair<String, (ByteArray) -> Unit>> = listOf(
        "responseBody" to FuzzEntryPoints::responseBody,
        "statusParser" to FuzzEntryPoints::statusParser,
        "annotationParser" to FuzzEntryPoints::annotationParser,
    )

    fun directories(): List<File> = listOf("fixtures", "fuzz/corpus", "fuzz/crashes").mapNotNull(::resource)

    fun inputs(): List<File> = directories().flatMap { it.walkTopDown().filter(File::isFile).toList() }.sorted()

    fun crashes(): List<File> =
        resource("fuzz/crashes")?.walkTopDown()?.filter(File::isFile)?.sorted()?.toList().orEmpty()

    private fun resource(path: String): File? =
        FuzzCorpus::class.java.classLoader?.getResource(path)?.let { File(it.toURI()) }
}
