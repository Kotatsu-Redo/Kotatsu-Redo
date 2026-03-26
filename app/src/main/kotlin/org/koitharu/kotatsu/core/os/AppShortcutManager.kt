package org.koitharu.kotatsu.core.os

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.room.InvalidationTracker
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import android.util.Log
import android.os.Environment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.image.ThumbnailTransformation
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppShortcutManager @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val coil: ImageLoader,
	private val historyRepository: HistoryRepository,
	private val mangaRepository: MangaDataRepository,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(TABLE_HISTORY), SharedPreferences.OnSharedPreferenceChangeListener {

	private companion object {
		const val TAG = "AppShortcutManager"
	}

	private val iconSize by lazy {
		Size(ShortcutManagerCompat.getIconMaxWidth(context), ShortcutManagerCompat.getIconMaxHeight(context))
	}
	private var shortcutsUpdateJob: Job? = null

	init {
		settings.subscribe(this)
	}

	override fun onInvalidated(tables: Set<String>) {
		if (!settings.isDynamicShortcutsEnabled) {
			return
		}
		Log.d(TAG, "onInvalidated called, tables=$tables")
		val prevJob = shortcutsUpdateJob
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			prevJob?.join()
			doUpdateShortcuts()
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_SHORTCUTS) {
			if (settings.isDynamicShortcutsEnabled) {
				onInvalidated(emptySet())
			} else {
				clearShortcuts()
			}
		}
	}

	suspend fun requestPinShortcut(manga: Manga): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(manga), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	suspend fun requestPinShortcut(source: MangaSource): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(source), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	fun getMangaShortcuts(): Set<Long> {
		val shortcuts = ShortcutManagerCompat.getShortcuts(
			context,
			ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
		)
		return shortcuts.mapNotNullToSet { it.id.toLongOrNull() }
	}

	@VisibleForTesting
	suspend fun await(): Boolean {
		val job = shortcutsUpdateJob
		if (job != null) {
			job.join()
			// After the background job completes, poll the system shortcuts briefly
			// to ensure the OS has applied them. This reduces flakiness in instrumentation tests.
			val start = System.currentTimeMillis()
			while (System.currentTimeMillis() - start < 8_000) {
				val list = ShortcutManagerCompat.getShortcuts(
					context,
					ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
				)
				if (list.isNotEmpty()) {
					Log.d(TAG, "await: shortcuts visible, count=${list.size}")
					return true
				}
				kotlinx.coroutines.delay(100)
			}
			return false
		}
		// Fallback: poll system shortcuts for a short time to reduce flakiness in tests
		return runCatchingCancellable {
			val start = System.currentTimeMillis()
			while (System.currentTimeMillis() - start < 5_000) {
				val list = ShortcutManagerCompat.getShortcuts(
					context,
					ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
				)
				if (list.isNotEmpty()) return@runCatchingCancellable true
				kotlinx.coroutines.delay(100)
			}
			false
		}.getOrDefault(false)
	}

	fun notifyMangaOpened(mangaId: Long) {
		ShortcutManagerCompat.reportShortcutUsed(context, mangaId.toString())
	}

	fun isDynamicShortcutsAvailable(): Boolean {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
			context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity > 0
	}

	private suspend fun doUpdateShortcuts() = runCatchingCancellable {
		val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(5)
		val shortcuts = historyRepository.getList(0, maxShortcuts)
			.filter { x -> x.title.isNotEmpty() }
			.map { buildShortcutInfo(it) }
		try {
				Log.d(TAG, "updateShortcutsImpl: maxShortcuts=$maxShortcuts, shortcutsCount=${shortcuts.size}, ids=${shortcuts.map { it.id }}")
				ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
				Log.d(TAG, "setDynamicShortcuts called")
				// Write debug artifacts to multiple locations to ensure at least one is pullable
				val debugText = "maxShortcuts=$maxShortcuts\nshortcutsCount=${shortcuts.size}\nids=${shortcuts.map { it.id }}\n"
				// 1) External media (what Gradle additional output expects)
				try {
					val externalBase = Environment.getExternalStorageDirectory().path
					val outDir = File(externalBase, "Android/media/${context.packageName}/additional_test_output")
					outDir.mkdirs()
					val outFile = File(outDir, "shortcuts_debug.txt")
					outFile.writeText(debugText)
					Log.d(TAG, "wrote external media debug file: ${outFile.absolutePath}")
				} catch (e: Exception) {
					Log.d(TAG, "failed writing external media artifact: ${e.message}")
				}
				// 2) App external files dir (should be accessible via adb run-as)
				try {
					val base = context.getExternalFilesDir(null) ?: context.filesDir
					val dir = File(base, "additional_test_output")
					dir.mkdirs()
					val f = File(dir, "shortcuts_debug.txt")
					f.writeText(debugText)
					Log.d(TAG, "wrote externalFilesDir debug file: ${f.absolutePath}")
				} catch (e: Exception) {
					Log.d(TAG, "failed writing externalFilesDir artifact: ${e.message}")
				}
				// 3) Internal files dir (pullable via `adb shell run-as <pkg> cat`)
				try {
					val internalDir = File(context.filesDir, "additional_test_output")
					internalDir.mkdirs()
					val fi = File(internalDir, "shortcuts_debug.txt")
					fi.writeText(debugText)
					Log.d(TAG, "wrote internal filesDir debug file: ${fi.absolutePath}")
				} catch (e: Exception) {
					Log.d(TAG, "failed writing internal artifact: ${e.message}")
				}
		} catch (e: Exception) {
			// Log and rethrow to be handled by runCatchingCancellable
			Log.d(TAG, "setDynamicShortcuts threw: ${e.message}")
			throw e
		}
	}.onFailure {
		it.printStackTraceDebug()
	}

	@VisibleForTesting
	suspend fun updateNow(): Boolean {
		// Directly perform the update and return whether shortcuts are visible afterwards.
		doUpdateShortcuts()
		// Give the OS a short moment to apply shortcuts, then check.
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5_000) {
			val list = ShortcutManagerCompat.getShortcuts(
				context,
				ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
			)
			if (list.isNotEmpty()) return true
			kotlinx.coroutines.delay(100)
		}
		return false
	}

	private fun clearShortcuts() {
		try {
			ShortcutManagerCompat.removeAllDynamicShortcuts(context)
		} catch (_: IllegalStateException) {
		}
	}

	private suspend fun buildShortcutInfo(manga: Manga): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(manga.coverUrl)
					.size(iconSize)
					.mangaSourceExtra(manga.source)
					.scale(Scale.FILL)
					.transformations(ThumbnailTransformation())
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		mangaRepository.storeManga(manga, replaceExisting = true)
		val title = manga.title.ifEmpty {
			manga.altTitles.firstOrNull()
		}.ifNullOrEmpty {
			context.getString(R.string.unknown)
		}
		ShortcutInfoCompat.Builder(context, manga.id.toString())
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(
				ReaderIntent.Builder(context)
					.mangaId(manga.id)
					.build()
					.intent,
			).build()
	}

	private suspend fun buildShortcutInfo(source: MangaSource): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(source.faviconUri())
					.mangaSourceExtra(source)
					.size(iconSize)
					.scale(Scale.FIT)
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		val title = source.getTitle(context)
		ShortcutInfoCompat.Builder(context, source.name)
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(AppRouter.listIntent(context, source, null, null))
			.build()
	}
}
