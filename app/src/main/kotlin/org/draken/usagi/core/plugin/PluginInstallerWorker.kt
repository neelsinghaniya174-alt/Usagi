package org.draken.usagi.core.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dalvik.system.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.draken.usagi.core.db.dao.PluginDao
import org.draken.usagi.core.db.entity.PluginEntity
import org.draken.usagi.core.network.BaseHttpClient
import org.draken.usagi.core.plugin.model.KeiyoushiExtension
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.Enumeration

@HiltWorker
class PluginInstallerWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val pluginDao: PluginDao,
	@BaseHttpClient private val okHttpClient: OkHttpClient,
	private val pluginRegistry: PluginRegistry
) : CoroutineWorker(context, params) {

	companion object {
		const val KEY_EXTENSION = "extension"
		const val EXTENSIONS_DIR = "extensions"
	}

	override suspend fun doWork(): Result {
		val extensionJson = inputData.getString(KEY_EXTENSION) ?: return Result.failure()
		val extension = try {
			Json.decodeFromString<KeiyoushiExtension>(extensionJson)
		} catch (e: Exception) {
			return Result.failure()
		}

		return try {
			installExtension(extension)
			Result.success()
		} catch (e: Exception) {
			Log.e("PluginInstaller", "Installation failed", e)
			Result.retry()
		}
	}

	private suspend fun installExtension(extension: KeiyoushiExtension) = withContext(Dispatchers.IO) {
		val extensionsDir = File(applicationContext.filesDir, EXTENSIONS_DIR)
		if (!extensionsDir.exists()) extensionsDir.mkdirs()

		val apkUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/${extension.apk}"
		val apkFile = File(extensionsDir, "${extension.pkg}.apk")

		Log.d("PluginInstaller", "Downloading from: $apkUrl")
		downloadApk(apkUrl, apkFile)
		Log.d("PluginInstaller", "Downloaded to ${apkFile.absolutePath} (size: ${apkFile.length()} bytes)")

		Log.d("PluginInstaller", "Extracting DEX files...")
		val dexPaths = extractAllDexFiles(apkFile, extensionsDir, extension.pkg)
		if (dexPaths.isEmpty()) {
			throw Exception("No .dex files found in APK")
		}

		val secureDexPaths = dexPaths.map { dexPath ->
			val secureDex = copyDexToSecureLocation(File(dexPath), extension.pkg)
			secureDex.absolutePath
		}
		val combinedDexPath = secureDexPaths.joinToString(File.pathSeparator)
		Log.d("PluginInstaller", "Secure DEX path: $combinedDexPath")

		Log.d("PluginInstaller", "Finding source class via DEX scanning...")
		val sourceClass = findSourceClass(apkFile, combinedDexPath)
			?: throw Exception("Could not find Source or SourceFactory class in APK")
		Log.d("PluginInstaller", "Entry point class: $sourceClass")

		val pluginEntity = PluginEntity(
			packageName = extension.pkg,
			name = extension.name,
			lang = extension.lang,
			versionName = extension.version,
			versionCode = extension.code,
			sourceClass = sourceClass,
			apkPath = combinedDexPath,
			isEnabled = true,
			isNsfw = extension.nsfw == 1
		)
		pluginDao.upsertPlugin(pluginEntity)
		Log.d("PluginInstaller", "Plugin saved to database")

		Log.d("PluginInstaller", "Loading plugin via PluginRegistry...")
		val metadata = PluginMetadata(
			packageName = pluginEntity.packageName,
			name = pluginEntity.name,
			lang = pluginEntity.lang,
			versionName = pluginEntity.versionName,
			versionCode = pluginEntity.versionCode,
			sourceClass = pluginEntity.sourceClass,
			apkPath = pluginEntity.apkPath,
			dexPath = pluginEntity.apkPath,
			isNsfw = pluginEntity.isNsfw
		)
		val loadedSource = pluginRegistry.loadPlugin(metadata)
		if (loadedSource != null) {
			Log.d("PluginInstaller", "Plugin loaded successfully: ${loadedSource.name}")
		} else {
			Log.e("PluginInstaller", "Plugin loading failed – returned null")
		}
	}

	private fun downloadApk(url: String, destFile: File) {
		val request = Request.Builder().url(url).build()
		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
			response.body?.byteStream()?.use { input ->
				FileOutputStream(destFile).use { output ->
					input.copyTo(output)
				}
			}
		}
	}

	private fun extractAllDexFiles(apkFile: File, destDir: File, prefix: String): List<String> {
		val dexFiles = mutableListOf<String>()
		ZipFile(apkFile).use { zip ->
			zip.entries().asSequence().forEach { entry ->
				if (entry.name.endsWith(".dex")) {
					val dexFileName = "${prefix}_${entry.name}"
					val dexFile = File(destDir, dexFileName)
					zip.getInputStream(entry).use { input ->
						FileOutputStream(dexFile).use { output ->
							input.copyTo(output)
						}
					}
					dexFiles.add(dexFile.absolutePath)
				}
			}
		}
		return dexFiles
	}

	private fun copyDexToSecureLocation(sourceDex: File, packageName: String): File {
		val secureDir = File(applicationContext.codeCacheDir, "plugins/$packageName")
		if (!secureDir.exists()) secureDir.mkdirs()
		val destDex = File(secureDir, sourceDex.name)
		sourceDex.copyTo(destDex, overwrite = true)
		destDex.setReadOnly()
		return destDex
	}

	private suspend fun findSourceClass(apkFile: File, dexPath: String): String? {
		// First try ServiceLoader
		findSourceClassViaServiceLoader(apkFile)?.let { return it }

		// Then DEX scanning
		return withContext(Dispatchers.IO) {
			var dex: DexFile? = null
			try {
				dex = DexFile(dexPath)
				val entries: Enumeration<String> = dex.entries()

				val classLoader = dalvik.system.DexClassLoader(
					dexPath,
					applicationContext.codeCacheDir.absolutePath,
					null,
					javaClass.classLoader
				)

				var sourceFactoryClass: String? = null
				var sourceClass: String? = null

				while (entries.hasMoreElements()) {
					val className = entries.nextElement().replace('/', '.')
					if (!className.startsWith("eu.kanade.tachiyomi.extension")) continue

					try {
						val clazz = classLoader.loadClass(className)
						if (clazz.interfaces.any { it.name == "eu.kanade.tachiyomi.source.SourceFactory" }) {
							sourceFactoryClass = className
							break
						}
						if (sourceClass == null && clazz.interfaces.any { it.name == "eu.kanade.tachiyomi.source.Source" }) {
							sourceClass = className
						}
					} catch (e: ClassNotFoundException) {
						// ignore
					} catch (e: NoClassDefFoundError) {
						// ignore
					}
				}

				sourceFactoryClass ?: sourceClass
			} catch (e: Exception) {
				Log.e("PluginInstaller", "DEX scanning failed", e)
				null
			} finally {
				dex?.close()
			}
		}
	}

	private fun findSourceClassViaServiceLoader(apkFile: File): String? {
		return try {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("META-INF/services/eu.kanade.tachiyomi.source.SourceFactory")
					?: zip.getEntry("META-INF/services/eu.kanade.tachiyomi.source.Source")
					?: return null
				zip.getInputStream(entry).bufferedReader().readLine()?.trim()
			}
		} catch (e: Exception) {
			null
		}
	}
}
