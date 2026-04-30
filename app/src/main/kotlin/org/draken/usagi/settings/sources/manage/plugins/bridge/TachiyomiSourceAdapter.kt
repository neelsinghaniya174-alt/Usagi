package org.draken.usagi.settings.sources.manage.plugins.bridge


import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

class TachiyomiSourceAdapter(
	private val tachiyomiSource: Any,
	val sourceName: String,
	val lang: String,
	val packageName: String
) : MangaSource {

	override val name: String = "$packageName:$sourceName"
	override val title: String = sourceName
	override val locale: String = lang
	override val contentType: ContentType = ContentType.MANGA
	override val isBroken: Boolean = false

	internal fun getRawSource(): Any = tachiyomiSource
}
