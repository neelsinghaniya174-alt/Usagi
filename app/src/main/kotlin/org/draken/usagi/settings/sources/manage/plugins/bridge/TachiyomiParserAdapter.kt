package org.draken.usagi.settings.sources.manage.plugins.bridge

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.util.LinkResolver
import java.lang.reflect.Proxy
import java.util.*

class TachiyomiParserAdapter(
	private val adapter: TachiyomiSourceAdapter
) : MangaParser {

	override val source: MangaSource = adapter
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	@Deprecated("Use getList(offset, order, filter) instead")
	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = MangaSearchQueryCapabilities()

	override val filterCapabilities: MangaListFilterCapabilities by lazy {
		try {
			val ctor = MangaListFilterCapabilities::class.java.getConstructor(
				Boolean::class.javaPrimitiveType,
				Boolean::class.javaPrimitiveType,
				Boolean::class.javaPrimitiveType,
				Boolean::class.javaPrimitiveType
			)
			ctor.newInstance(true, false, false, false)
		} catch (e: Exception) {
			MangaListFilterCapabilities::class.java.newInstance()
		}
	}

	override val config: MangaSourceConfig = createMangaSourceConfig()

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("", "")
	override val domain: String = ""
	override val authorizationProvider: MangaParserAuthProvider? = null

	@Deprecated("Use getList(offset, order, filter) instead")
	override suspend fun getList(query: MangaSearchQuery): List<Manga> = emptyList()

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> = emptyList()
	override suspend fun getDetails(manga: Manga): Manga = manga
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()
	override suspend fun getPageUrl(page: MangaPage): String = page.url
	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(emptySet(), emptySet())

	override suspend fun getFavicons(): Favicons {
		return try {
			val clazz = Favicons::class.java
			val ctor = clazz.constructors.firstOrNull() ?: return Favicons(emptyList(), "")
			val paramTypes = ctor.parameterTypes
			val args = Array(paramTypes.size) { i ->
				when (paramTypes[i]) {
					List::class.java -> emptyList<Any>()
					String::class.java -> ""
					else -> null
				}
			}
			ctor.newInstance(*args) as Favicons
		} catch (e: Exception) {
			Favicons(emptyList(), "")
		}
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {}
	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
	override fun getRequestHeaders(): Headers = Headers.headersOf()
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = null
	override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

	private fun createMangaSourceConfig(): MangaSourceConfig {
		return Proxy.newProxyInstance(
			MangaSourceConfig::class.java.classLoader,
			arrayOf(MangaSourceConfig::class.java)
		) { _, method, args ->
			when (method.name) {
				"isLoggedIn" -> false
				"get" -> throw NoSuchElementException("Config not supported")
				"set" -> null
				"toString" -> "ProxyMangaSourceConfig"
				"hashCode" -> System.identityHashCode(this)
				"equals" -> args?.firstOrNull() == this
				else -> null
			}
		} as MangaSourceConfig
	}
}
