// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton
import net.pgmac.nagwatch.profile.KeystoreKeySource
import net.pgmac.nagwatch.profile.SecretKeySource
import net.pgmac.nagwatch.profile.db.ProfileDao
import net.pgmac.nagwatch.profile.db.ProfileDatabase
import net.pgmac.nagwatch.status.FactoryClientProvider
import net.pgmac.nagwatch.status.NagiosClientProvider
import net.pgmac.nagwatch.ui.profile.ConnectionTester
import net.pgmac.nagwatch.ui.profile.NagiosConnectionTester

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileModule {
    @Binds
    abstract fun secretKeySource(source: KeystoreKeySource): SecretKeySource

    @Binds
    abstract fun connectionTester(tester: NagiosConnectionTester): ConnectionTester

    @Binds
    abstract fun clientProvider(provider: FactoryClientProvider): NagiosClientProvider

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
    }
}
