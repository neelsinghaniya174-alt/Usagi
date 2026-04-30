package org.draken.usagi.core.plugin

import android.content.Context
import android.os.Build
import android.util.Log
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.model.PluginMangaSource
import org.draken.usagi.core.parser.DynamicParserManager
import org.draken.usagi.settings.sources.manage.plugins.bridge.TachiyomiSourceAdapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap

class PluginRegistry(private val context: Context) {

	private val sourceInstanceCache = ConcurrentHashMap<String, MangaSource>()

	/**
	 * Loads a plugin from its metadata and returns its [MangaSource] instance.
	 * If already loaded, returns the cached instance.
	 */
	fun loadPlugin(metadata: PluginMetadata): MangaSource? {
		// Return cached instance if available
		sourceInstanceCache[metadata.packageName]?.let {
			Log.d("PluginRegistry", "Returning cached instance for ${metadata.packageName}")
			return it
		}

		Log.d("PluginRegistry", "Loading plugin: ${metadata.packageName}")

		// First, try to load as a native Kotatsu parser plugin (works for JARs and compatible APKs)
		val nativeSuccess = try {
			DynamicParserManager.loadApkPlugin(
				context = context,
				apkPath = metadata.apkPath,
				packageName = metadata.packageName
			)
		} catch (e: Exception) {
			Log.w("PluginRegistry", "Native loadApkPlugin threw exception", e)
			false
		}

		if (nativeSuccess) {
			val source = MangaSourceRegistry.sources.find {
				it is PluginMangaSource && it.jarName == metadata.packageName
			}
			if (source != null) {
				sourceInstanceCache[metadata.packageName] = source
				Log.d("PluginRegistry", "Native plugin loaded: ${source.name}")
				return source
			}
		}

		// Fallback: Tachiyomi adapter
		Log.d("PluginRegistry", "Falling back to Tachiyomi adapter for ${metadata.packageName}")
		return try {
			val classLoader = createClassLoader(metadata.apkPath, context.classLoader)
			val sourceClass = classLoader.loadClass(metadata.sourceClass)
			val instance: Any = sourceClass.getDeclaredConstructor().newInstance()

			// 🆕 Check for SourceFactory via reflection (no direct import)
			val adapters = if (instance.javaClass.interfaces.any { it.name == "eu.kanade.tachiyomi.source.SourceFactory" }) {
				// It's a SourceFactory – call createSources() via reflection
				val createSourcesMethod = instance.javaClass.getMethod("createSources")
				val sources = createSourcesMethod.invoke(instance) as? List<*> ?: emptyList<Any>()
				sources.mapNotNull { source ->
					if (source != null) {
						// Extract name and lang via reflection
						val name = source.javaClass.getMethod("getName").invoke(source) as? String ?: return@mapNotNull null
						val lang = source.javaClass.getMethod("getLang").invoke(source) as? String ?: "en"
						TachiyomiSourceAdapter(
							tachiyomiSource = source,
							sourceName = name,
							lang = lang,
							packageName = metadata.packageName
						)
					} else null
				}
			} else {
				// Assume it's a single Source
				listOf(
					TachiyomiSourceAdapter(
						tachiyomiSource = instance,
						sourceName = metadata.name,
						lang = metadata.lang,
						packageName = metadata.packageName
					)
				)
			}

			// Register all created adapters
			adapters.forEach { adapter ->
				sourceInstanceCache[adapter.name] = adapter
				MangaSourceRegistry.sources.removeAll {
					it is PluginMangaSource && it.jarName == metadata.packageName && it.name == adapter.name
				}
				MangaSourceRegistry.sources.add(PluginMangaSource(adapter, metadata.packageName))
				Log.d("PluginRegistry", "Registered Tachiyomi source: ${adapter.name}")
			}
			MangaSourceRegistry.incrementVersion()
			MangaSourceRegistry.updates.tryEmit(Unit)

			// Return the first adapter
			adapters.firstOrNull()
		} catch (e: Exception) {
			Log.e("PluginRegistry", "Failed to load plugin as Tachiyomi", e)
			null
		}
	}

	/**
	 * Unloads a plugin and releases all associated resources.
	 */
	fun unloadPlugin(packageName: String) {
		sourceInstanceCache.remove(packageName)
		try {
			DynamicParserManager.unloadApkPlugin(packageName)
		} catch (e: Exception) {
			Log.w("PluginRegistry", "Error unloading native plugin", e)
		}
		MangaSourceRegistry.sources.removeAll {
			it is PluginMangaSource && it.jarName == packageName
		}
		MangaSourceRegistry.incrementVersion()
		MangaSourceRegistry.updates.tryEmit(Unit)
		Log.d("PluginRegistry", "Plugin unloaded: $packageName")
	}

	/**
	 * Returns all currently loaded [MangaSource] instances.
	 */
	fun getAllLoadedSources(): List<MangaSource> {
		return sourceInstanceCache.values.toList()
	}

	/**
	 * Checks if a plugin is currently loaded.
	 */
	fun isPluginLoaded(packageName: String): Boolean {
		return sourceInstanceCache.containsKey(packageName)
	}

	/**
	 * Clears all loaded plugins. Use with caution.
	 */
	fun clearAll() {
		sourceInstanceCache.keys.forEach { packageName ->
			try {
				DynamicParserManager.unloadApkPlugin(packageName)
			} catch (e: Exception) {
				Log.w("PluginRegistry", "Error unloading plugin during clearAll", e)
			}
		}
		sourceInstanceCache.clear()
		MangaSourceRegistry.sources.removeAll { it is PluginMangaSource }
		MangaSourceRegistry.incrementVersion()
		MangaSourceRegistry.updates.tryEmit(Unit)
	}

	/**
	 * Returns the [MangaSource] for a given package name if already loaded.
	 * Does not trigger loading.
	 */
	fun getLoadedSource(packageName: String): MangaSource? {
		return sourceInstanceCache[packageName]
	}

	/**
	 * Creates a ClassLoader suitable for isolated plugin loading.
	 * Uses parent‑last delegation on API 27+ to prevent dependency conflicts.
	 */
	private fun createClassLoader(apkPath: String, parent: ClassLoader): ClassLoader {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
			dalvik.system.DelegateLastClassLoader(
				apkPath,
				null, // librarySearchPath
				parent
			)
		} else {
			val optimizedDir = context.codeCacheDir.absolutePath
			dalvik.system.DexClassLoader(
				apkPath,
				optimizedDir,
				null,
				parent
			)
		}
	}
}
