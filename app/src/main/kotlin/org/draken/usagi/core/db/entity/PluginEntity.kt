package org.draken.usagi.core.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plugins")
data class PluginEntity(
	@PrimaryKey
	val packageName: String,
	val name: String,
	val lang: String,
	val versionName: String,
	val versionCode: Int,
	val sourceClass: String,
	val apkPath: String,
	val isEnabled: Boolean = false,
	val isNsfw: Boolean = false
)
