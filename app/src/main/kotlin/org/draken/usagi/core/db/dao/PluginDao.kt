package org.draken.usagi.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.draken.usagi.core.db.entity.PluginEntity

@Dao
interface PluginDao {
	@Upsert
	suspend fun upsertPlugin(plugin: PluginEntity)

	@Query("UPDATE plugins SET isEnabled = :isEnabled WHERE packageName = :packageName")
	suspend fun setPluginEnabled(packageName: String, isEnabled: Boolean)

	@Query("SELECT * FROM plugins WHERE isEnabled = 1")
	fun getEnabledPluginsFlow(): Flow<List<PluginEntity>>

	@Query("SELECT * FROM plugins")
	fun getAllPluginsFlow(): Flow<List<PluginEntity>>

	@Query("DELETE FROM plugins WHERE packageName = :packageName")
	suspend fun deletePlugin(packageName: String)
}
