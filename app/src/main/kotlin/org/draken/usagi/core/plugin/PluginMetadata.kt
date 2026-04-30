package org.draken.usagi.core.plugin


/**
 * Metadata required to load and manage a plugin extension.
 *
 * @property packageName The unique package name of the extension (e.g., "eu.kanade.tachiyomi.extension.en.mangadex")
 * @property name The human-readable name of the source (e.g., "MangaDex")
 * @property lang The language code of the extension (e.g., "en", "all")
 * @property versionName Human-readable version (e.g., "1.4.2")
 * @property versionCode Integer version for comparison to handle auto-updates
 * @property sourceClass Fully qualified name of the main class to instantiate
 * @property apkPath Absolute path to the downloaded APK file (stored securely in internal storage)
 * @property dexPath Absolute path to the extracted classes.dex file (computed from apkPath)
 * @property isNsfw Flag indicating if the source contains 18+ content
 */
data class PluginMetadata(
	val packageName: String,
	val name: String,
	val lang: String,
	val versionName: String,
	val versionCode: Int,
	val sourceClass: String,
	val apkPath: String,
	val dexPath: String,
	val isNsfw: Boolean
)
