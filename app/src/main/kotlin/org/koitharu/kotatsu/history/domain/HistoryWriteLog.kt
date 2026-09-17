package org.koitharu.kotatsu.history.domain

import android.util.Log
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Traces every write to the history table, so an entry that appears without being read can be
 * attributed to whatever produced it.
 *
 * The interesting part is [caller]: a history row can be written from the reader, from "mark as
 * read", from marking a chapter as current, from a source migration, or from the progress
 * recalculation that runs when a manga's details are opened. They are indistinguishable in the
 * stored row - all that survives is a fresh `updated_at`, which moves the manga to the top of the
 * history list exactly as reading it would.
 *
 * Uses [Log.i] rather than [Log.d] on purpose: `proguard-android-optimize` strips debug and verbose
 * calls, and this has to work in a release or nightly build where the problem actually shows up.
 *
 * Read with: `adb logcat -s KotatsuHistory`
 */
object HistoryWriteLog {

	const val TAG = "KotatsuHistory"

	fun write(manga: Manga, chapterId: Long, page: Int, percent: Float, force: Boolean, isNew: Boolean) {
		Log.i(
			TAG,
			(if (isNew) "INSERT " else "UPDATE ") + manga.ref() +
				" chapter=$chapterId page=$page percent=$percent force=$force\n    from ${caller()}",
		)
	}

	fun progressRewrite(manga: Manga, oldPercent: Float, newPercent: Float, oldCount: Int, newCount: Int) {
		Log.i(
			TAG,
			"PROGRESS " + manga.ref() +
				" percent $oldPercent -> $newPercent, chapters $oldCount -> $newCount" +
				"\n    from ${caller()}",
		)
	}

	fun deleted(title: String?, mangaId: Long, reason: String) {
		Log.i(TAG, "DELETE id=$mangaId title=\"${title.orEmpty()}\" ($reason)\n    from ${caller()}")
	}

	fun skipped(manga: Manga) {
		Log.i(TAG, "SKIP (incognito) " + manga.ref())
	}

	private fun Manga.ref() = "\"${title.take(60)}\" id=$id source=${source.name}"

	/**
	 * The app frames of the current stack, which is what names the feature responsible. Everything
	 * outside the app - coroutine machinery, Room, the framework - is noise here, and this class's
	 * own frames would only ever say "HistoryWriteLog".
	 */
	private fun caller(): String = Throwable().stackTrace
		.asSequence()
		.filter { it.className.startsWith("org.koitharu.kotatsu") }
		.filterNot { it.className.startsWith(HistoryWriteLog::class.java.name) }
		.take(CALLER_FRAMES)
		.joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
		.ifEmpty { "unknown" }

	private const val CALLER_FRAMES = 6
}
