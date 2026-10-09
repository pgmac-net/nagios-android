// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.pgmac.nagwatch.nagios.http.NagiosApi
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

internal val TEST_JSON = Json { ignoreUnknownKeys = true }
internal val FIXED_NOW: Instant = Instant.parse("2026-10-09T10:00:00Z")
internal val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

internal fun testHttpClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(2))
    .readTimeout(Duration.ofSeconds(2))
    .apply(configure)
    .build()

internal fun testFactory(client: OkHttpClient = testHttpClient()) =
    NagiosClientFactory(client, TEST_JSON, Dispatchers.IO, FIXED_CLOCK)

internal fun testApi(client: OkHttpClient = testHttpClient()) = NagiosApi(client, TEST_JSON, Dispatchers.IO)

/** Settings pointing at a local test server, which is plain HTTP unless the test set up TLS. */
internal fun settingsFor(
    baseUrl: HttpUrl,
    allowCleartext: Boolean = !baseUrl.isHttps,
    accessClientId: String? = null,
    accessClientSecret: String? = null,
    customHeaders: List<Pair<String, String>> = emptyList(),
    cgiBase: HttpUrl? = null,
) = ConnectionSettings(
    baseUrl = baseUrl,
    username = "nagwatch",
    password = "s3cr3t-p4ss",
    accessClientId = accessClientId,
    accessClientSecret = accessClientSecret,
    customHeaders = customHeaders,
    allowCleartext = allowCleartext,
    cgiBase = cgiBase,
)

internal fun ok(body: String): MockResponse =
    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

internal fun status(code: Int, body: String = "", vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder().code(code).body(body)
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

internal fun MockWebServer.respond(handler: (RecordedRequest) -> MockResponse) {
    dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
    }
}

internal fun RecordedRequest.param(name: String): String? = url.queryParameter(name)

internal val RecordedRequest.path: String get() = url.encodedPath

internal fun <T> NagiosResult<T>.valueOrFail(): T = when (this) {
    is NagiosResult.Success -> value
    is NagiosResult.Failure -> throw AssertionError("expected success but got $error")
}

internal fun NagiosResult<*>.errorOrFail(): NagiosError = when (this) {
    is NagiosResult.Success -> throw AssertionError("expected a failure but got $value")
    is NagiosResult.Failure -> error
}
