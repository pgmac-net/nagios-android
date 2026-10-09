// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
    private val CALL_TIMEOUT: Duration = Duration.ofSeconds(45)

    /**
     * Shared connection pool and timeouts. Per-profile clients are derived from
     * this one and add their own credentials; this client itself sends none.
     */
    @Provides
    @Singleton
    fun baseOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .callTimeout(CALL_TIMEOUT)
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }
}
