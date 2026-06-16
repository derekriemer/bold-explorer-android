package com.boldexplorer.di

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.boldexplorer.db.AutoWaypointRepositoryImpl
import com.boldexplorer.db.BoldExplorerDatabase
import com.boldexplorer.db.CollectionRepositoryImpl
import com.boldexplorer.db.NavPointsRepositoryImpl
import com.boldexplorer.db.TrailRepositoryImpl
import com.boldexplorer.db.WaypointRepositoryImpl
import com.boldexplorer.shared.repository.AutoWaypointRepository
import com.boldexplorer.shared.repository.CollectionRepository
import com.boldexplorer.shared.repository.NavPointsRepository
import com.boldexplorer.shared.repository.TrailRepository
import com.boldexplorer.shared.repository.WaypointRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): BoldExplorerDatabase =
        BoldExplorerDatabase(
            AndroidSqliteDriver(BoldExplorerDatabase.Schema, context, "bold_explorer.db"),
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindWaypointRepository(impl: WaypointRepositoryImpl): WaypointRepository

    @Binds
    @Singleton
    abstract fun bindTrailRepository(impl: TrailRepositoryImpl): TrailRepository

    @Binds
    @Singleton
    abstract fun bindCollectionRepository(impl: CollectionRepositoryImpl): CollectionRepository

    @Binds
    @Singleton
    abstract fun bindAutoWaypointRepository(impl: AutoWaypointRepositoryImpl): AutoWaypointRepository

    @Binds
    @Singleton
    abstract fun bindNavPointsRepository(impl: NavPointsRepositoryImpl): NavPointsRepository
}
