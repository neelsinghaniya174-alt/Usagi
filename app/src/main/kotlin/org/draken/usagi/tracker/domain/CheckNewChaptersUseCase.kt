package org.draken.usagi.tracker.domain

import android.util.Log
import android.annotation.SuppressLint
import coil3.request.CachePolicy
import org.draken.usagi.BuildConfig
import org.draken.usagi.core.model.getPreferredBranch
import org.draken.usagi.core.model.isLocal
import org.draken.usagi.core.parser.CachingMangaRepository
import org.draken.usagi.core.parser.MangaRepository
import org.draken.usagi.core.util.MultiMutex
import org.draken.usagi.core.util.ext.printStackTraceDebug
import org.draken.usagi.core.util.ext.toInstantOrNull
import org.draken.usagi.history.data.HistoryRepository
import org.draken.usagi.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.draken.usagi.tracker.domain.model.MangaTracking
import org.draken.usagi.tracker.domain.model.MangaUpdates
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("NewApi")
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates =
		mutex.withLock(track.manga.id) {
			invokeImpl(track)
		}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga)
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastNewChapterIndex = chapters.size - track.newChapters
			val lastChapter = chapters.lastOrNull()
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull()
					?: track.lastChapterDate,
				newChapters = when {
					track.newChapters == 0 -> 0
					chapterIndex < 0 -> track.newChapters
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> track.newChapters
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		Log.d("TrackerDetail", "Checking ${track.manga.title} (id=${track.manga.id})")
		Log.d("TrackerDetail", "  Last known chapter ID: ${track.lastChapterId}, date: ${track.lastChapterDate}")
		val details = getFullManga(track.manga)
		Log.d("TrackerDetail", "  Fetched details, chapters count: ${details.chapters?.size ?: 0}")
		val branch = getBranch(details, track.lastChapterId)
		Log.d("TrackerDetail", "  Detected branch: $branch")

		// 🆕 NEW: If track has no baseline but we got chapters, set baseline to latest chapter
		if (track.isEmpty() && !details.chapters.isNullOrEmpty()) {
			val latestChapter = details.getChapters(branch)?.lastOrNull()
			if (latestChapter != null) {
				Log.i("TrackerDetail", "  Initializing baseline for ${track.manga.title} with chapter ${latestChapter.id}")

				// THE TWEAK: Normalize the date so AsuraScans doesn't set the baseline to 1970
				val rawDate = latestChapter.uploadDate
				val normalizedMillis = if (rawDate in 1L..9999999999L) rawDate * 1000L else rawDate

				// Create an updated track with baseline set
				val initializedTrack = MangaTracking(
					manga = details,
					lastChapterId = latestChapter.id,
					lastCheck = Instant.now(),
					lastChapterDate = normalizedMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
					newChapters = 0,
				)
				// Save the baseline immediately
				repository.mergeWith(initializedTrack)
				// Return success with 0 new chapters (baseline just set)
				return@runCatchingCancellable MangaUpdates.Success(
					manga = details,
					branch = branch,
					newChapters = emptyList(),
					isValid = true,
				)
			}
		}

		val result = compare(track, details, branch)
		Log.d("TrackerDetail", "  Compare result: ${result.newChapters.size} new, valid=${result.isValid}")
		result
	}.getOrElse { error ->
		Log.e("TrackerDetail", "  Check failed: ${error.message}", error)
		MangaUpdates.Failure(manga = track.manga, error = error)
	}.also { updates ->
		repository.saveUpdates(updates)
	}

	private suspend fun getBranch(manga: Manga, trackChapterId: Long): String? {
		historyRepository.getOne(manga)?.let {
			manga.chapters?.findById(it.chapterId)
		}?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		return manga.getPreferredBranch(null)
	}

	private suspend fun getFullManga(manga: Manga): Manga = when {
		manga.isLocal -> fetchDetails(
			requireNotNull(localMangaRepository.getRemoteManga(manga)) {
				"Local manga is not supported"
			},
		)

		manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
		else -> manga
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		return if (repo is CachingMangaRepository) {
			repo.getDetails(manga, CachePolicy.WRITE_ONLY)
		} else {
			repo.getDetails(manga)
		}
	}

	private fun compare(track: MangaTracking, manga: Manga, branch: String?): MangaUpdates.Success {
		if (track.isEmpty()) {
			Log.w("TrackerDetail", "    Track is empty, returning invalid")
			return MangaUpdates.Success(manga, branch, emptyList(), isValid = false)
		}

		val chapters = requireNotNull(manga.getChapters(branch))
		Log.d("TrackerDetail", "    Chapters for branch '$branch': ${chapters.size}")

		// Log if lastChapterId is found
		val lastChapterFound = chapters.findById(track.lastChapterId)
		Log.d(
			"TrackerDetail",
			"    LastChapterId ${track.lastChapterId} found in chapters: ${lastChapterFound != null}"
		)

		val newChapters = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
		Log.d("TrackerDetail", "    newChapters size (takeLastWhile): ${newChapters.size}")

		return when {
			newChapters.isEmpty() -> {
				val isValid = chapters.lastOrNull()?.id == track.lastChapterId
				Log.d("TrackerDetail", "    newChapters empty, isValid=$isValid")
				MangaUpdates.Success(manga, branch, emptyList(), isValid = isValid)
			}

			newChapters.size == chapters.size -> {
				Log.w("TrackerDetail", "    Chapter ID mismatch - using date fallback")
				// lastChapterId not found — chapter IDs changed (local vs remote extension mismatch)
				// Use lastChapterDate to find chapters that are genuinely newer
				val lastKnownDate = track.lastChapterDate
				val trulyNew = if (lastKnownDate != null) {
					val epochMillis = lastKnownDate.toEpochMilli()

					chapters.filter { ch ->
						if (ch.uploadDate <= 0L) return@filter false

						// THE FIX: Normalize the extension date.
						// If the date is smaller than 10 billion, it is in seconds. Multiply by 1000.
						val chUploadMillis = if (ch.uploadDate < 10000000000L) {
							ch.uploadDate * 1000L
						} else {
							ch.uploadDate
						}

						chUploadMillis > epochMillis
					}
				} else {
					emptyList()
				}

				Log.d("TrackerDetail", "    trulyNew chapters count: ${trulyNew.size}")
				if (trulyNew.isNotEmpty()) {
					MangaUpdates.Success(manga, branch, trulyNew, isValid = true)
				} else {
					// Reset baseline silently
					Log.w(
						"Tracker",
						"No new chapters found for ${track.manga.title} (all dates invalid/zero), resetting baseline"
					)
					MangaUpdates.Success(manga, branch, emptyList(), isValid = true)
				}
			}

			else -> {
				Log.d("TrackerDetail", "    Found ${newChapters.size} new chapters")
				MangaUpdates.Success(manga, branch, newChapters, isValid = true)
			}
		}
	}
}
