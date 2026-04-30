package org.draken.usagi.settings.sources.manage.extensions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import org.draken.usagi.core.db.dao.PluginDao
import org.draken.usagi.core.db.entity.PluginEntity
import org.draken.usagi.core.model.MangaSourceRegistry
import org.draken.usagi.core.plugin.KeiyoushiRepository
import org.draken.usagi.core.plugin.PluginInstallerWorker
import org.draken.usagi.core.plugin.PluginMetadata
import org.draken.usagi.core.plugin.PluginRegistry
import org.draken.usagi.core.plugin.model.KeiyoushiExtension
import javax.inject.Inject

@HiltViewModel
class ExtensionsBrowserViewModel @Inject constructor(
	private val repository: KeiyoushiRepository,
	private val pluginDao: PluginDao,
	private val workManager: WorkManager,
	private val pluginRegistry: PluginRegistry
) : ViewModel() {

	private val _extensions = MutableLiveData<List<ExtensionItem>>()
	val extensions: LiveData<List<ExtensionItem>> = _extensions

	private val _isLoading = MutableLiveData(false)
	val isLoading: LiveData<Boolean> = _isLoading

	fun loadExtensions() {
		viewModelScope.launch {
			_isLoading.value = true
			try {
				val remoteList = repository.fetchExtensions()
				val installedPlugins: List<PluginEntity> = pluginDao.getAllPluginsFlow().first()
				val installedMap = installedPlugins.associateBy { it.packageName }

				val items = remoteList.map { remote ->
					val installed = installedMap[remote.pkg]
					ExtensionItem(
						remote = remote,
						installed = installed != null,
						installedVersion = installed?.versionCode,
						isEnabled = installed?.isEnabled ?: false
					)
				}
				_extensions.value = items
			} catch (e: Exception) {
				e.printStackTrace()
			} finally {
				_isLoading.value = false
			}
		}
	}

	fun installExtension(extension: KeiyoushiExtension) {
		Log.d("Keiyoushi", "Enqueuing install worker for ${extension.pkg}")
		val workRequest = OneTimeWorkRequestBuilder<PluginInstallerWorker>()
			.setInputData(
				workDataOf(
					PluginInstallerWorker.KEY_EXTENSION to Json.encodeToString(
						serializer<KeiyoushiExtension>(),
						extension
					)
				)
			)
			.build()
		workManager.enqueue(workRequest)
		Log.d("Keiyoushi", "Worker enqueued")

		viewModelScope.launch {
			delay(2000L)
			val pluginEntity = pluginDao.getAllPluginsFlow().first().find { it.packageName == extension.pkg }
			if (pluginEntity != null) {
				val metadata = PluginMetadata(
					packageName = pluginEntity.packageName,
					name = pluginEntity.name,
					lang = pluginEntity.lang,
					versionName = pluginEntity.versionName,
					versionCode = pluginEntity.versionCode,
					sourceClass = pluginEntity.sourceClass,
					apkPath = pluginEntity.apkPath,
					dexPath = pluginEntity.apkPath,   // ✅ Fix for missing dexPath
					isNsfw = pluginEntity.isNsfw
				)
				pluginRegistry.loadPlugin(metadata)
			}
			MangaSourceRegistry.refreshPlugins()
			loadExtensions()
		}
	}

	fun updateExtension(extension: KeiyoushiExtension) = installExtension(extension)

	fun uninstallExtension(extension: KeiyoushiExtension) {
		viewModelScope.launch {
			pluginDao.deletePlugin(extension.pkg)
			pluginRegistry.unloadPlugin(extension.pkg)
			MangaSourceRegistry.refreshPlugins()
			loadExtensions()
		}
	}
}

// ✅ Must be at top level, outside the ViewModel class
data class ExtensionItem(
	val remote: KeiyoushiExtension,
	val installed: Boolean,
	val installedVersion: Int?,
	val isEnabled: Boolean
)

enum class ExtensionAction { INSTALL, UPDATE, UNINSTALL }
