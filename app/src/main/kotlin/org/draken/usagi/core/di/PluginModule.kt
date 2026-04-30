package org.draken.usagi.core.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.draken.usagi.core.db.MangaDatabase
import org.draken.usagi.core.db.dao.PluginDao
import org.draken.usagi.core.plugin.PluginRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

	@Provides
	@Singleton
	fun providePluginRegistry(
		@ApplicationContext context: Context
	): PluginRegistry {
		return PluginRegistry(context)
	}

	@Provides
	fun providePluginDao(
		database: MangaDatabase
	): PluginDao {
		return database.PluginDao()
	}
}
