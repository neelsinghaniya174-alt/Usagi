package org.draken.usagi.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.draken.usagi.core.plugin.PluginRegistry
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.CopyOnWriteArrayList

object MangaSourceRegistry {
	val sources: MutableList<MangaSource> = CopyOnWriteArrayList()

	@Volatile
	var version: Int = 0
		private set

	val entries: List<MangaSource>
		get() = sources

	val updates = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)

	fun incrementVersion() {
		version++
	}

	// 🆕 Static reference to PluginRegistry (set in Application)
	private var pluginRegistry: PluginRegistry? = null

	fun setPluginRegistry(registry: PluginRegistry) {
		pluginRegistry = registry
	}

	/**
	 * Refresh the list of plugin sources:
	 * - Remove all existing PluginMangaSource entries
	 * - Add currently loaded plugin sources
	 */
	fun refreshPlugins() {
		val registry = pluginRegistry ?: return
		sources.removeAll { it is PluginMangaSource }
		val pluginSources = registry.getAllLoadedSources()
		sources.addAll(pluginSources.map { PluginMangaSource(it, it.name) })
		incrementVersion()
		updates.tryEmit(Unit)
	}
}
