package org.draken.usagi.core.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.plugin.model.KeiyoushiExtension
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeiyoushiRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient
) {
	private val json = Json { ignoreUnknownKeys = true }

	suspend fun fetchExtensions(): List<KeiyoushiExtension> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(KEIYOUSHI_INDEX_URL)
			.build()

		val response = okHttpClient.newCall(request).execute()

		if (!response.isSuccessful) {
			throw Exception("Failed to fetch extensions: ${response.code}")
		}

		val responseBody = response.body?.string() ?: throw Exception("Empty response body")
		json.decodeFromString(responseBody)
	}

	companion object {
		private const val KEIYOUSHI_INDEX_URL =
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
	}
}
