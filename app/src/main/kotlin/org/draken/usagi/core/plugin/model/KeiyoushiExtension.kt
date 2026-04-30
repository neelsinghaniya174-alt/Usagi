package org.draken.usagi.core.plugin.model

import kotlinx.serialization.Serializable

@Serializable
data class KeiyoushiExtension(
	val name: String,
	val pkg: String,
	val apk: String,
	val lang: String,
	val code: Int,
	val version: String,
	val nsfw: Int,
	val sources: List<KeiyoushiSource>? = null,
	val hasReadme: Boolean = false,
	val hasChangelog: Boolean = false
)

@Serializable
data class KeiyoushiSource(
	val id: String,
	val name: String,
	val lang: String,
	val baseUrl: String
)
