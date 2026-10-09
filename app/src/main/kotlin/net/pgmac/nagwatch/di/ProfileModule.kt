// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.pgmac.nagwatch.profile.DataStoreSelectedProfile
import net.pgmac.nagwatch.profile.KeystoreKeySource
import net.pgmac.nagwatch.profile.ProfileCleanup
import net.pgmac.nagwatch.profile.SecretKeySource
import net.pgmac.nagwatch.profile.SelectedProfile
import net.pgmac.nagwatch.profile.db.ProfileDao
import net.pgmac.nagwatch.profile.db.ProfileDatabase
import net.pgmac.nagwatch.status.FactoryClientProvider
import net.pgmac.nagwatch.status.NagiosClientProvider
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.cache.StatusCacheDao
import net.pgmac.nagwatch.status.cache.StatusDatabase
import net.pgmac.nagwatch.ui.profile.ConnectionTester
import net.pgmac.nagwatch.ui.profile.NagiosConnectionTester

/** A coroutine scope that lives as long as the app process. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileModule {
    @Binds
    abstract fun secretKeySource(source: KeystoreKeySource): SecretKeySource

    @Binds
    abstract fun connectionTester(tester: NagiosConnectionTester): ConnectionTester

    @Binds
    abstract fun clientProvider(provider: FactoryClientProvider): NagiosClientProvider

    @Binds
    abstract fun selectedProfile(selected: DataStoreSelectedProfile): SelectedProfile

    /** Deleting a profile also clears its cached status. */
    @Binds
    @IntoSet
    abstract fun statusCacheCleanup(cache: StatusCache): ProfileCleanup

    companion object {
        // No fallbackToDestructiveMigration: losing profiles silently is worse than a crash
        // that gets noticed. Every schema change ships with a migration.
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): ProfileDatabase =
            Room.databaseBuilder(context, ProfileDatabase::class.java, ProfileDatabase.FILE_NAME).build()

        @Provides
        @Singleton
        fun clock(): Clock = Clock.systemUTC()

        @Provides
        fun profileDao(database: ProfileDatabase): ProfileDao = database.profiles()

        // Destructive on purpose, unlike the profiles database: this one only holds what
        // can be fetched again (docs/adr/0005).
        @Provides
        @Singleton
        fun statusDatabase(@ApplicationContext context: Context): StatusDatabase =
            Room.databaseBuilder(context, StatusDatabase::class.java, StatusDatabase.FILE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        fun statusCacheDao(database: StatusDatabase): StatusCacheDao = database.cache()

        @Provides
        @Singleton
        fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SETTINGS_FILE) }

        /** Work that should outlive any one screen, such as startup housekeeping. */
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private const val SETTINGS_FILE = "settings"
    }
}
