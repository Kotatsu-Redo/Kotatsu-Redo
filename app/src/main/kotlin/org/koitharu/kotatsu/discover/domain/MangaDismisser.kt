package org.koitharu.kotatsu.discover.domain

import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.discover.data.DismissedEntry
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sourcescore.domain.DismissalReporter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Not interested", from wherever the reader says it: remembers it, and tells the community server if they are on
 * it. Everything that has to follow from a pass is here, so the screens that offer it all do the same.
 *
 * Nothing is taken out of the stored suggestions: every reader of them goes through the index, and so a pass that
 * is taken back is a title that is there again.
 */
@Singleton
class MangaDismisser @Inject constructor(
	private val dismissals: DiscoverDismissals,
	private val reporter: DismissalReporter,
	private val settings: AppSettings,
) {

	/** @return what to pass to [undo] to take it back */
	fun dismiss(manga: Manga): String = dismiss(listOf(manga)).first()

	/**
	 * All of them at once: the index is rebuilt one time, and a single run tells the server, however many there are.
	 *
	 * @return what to pass to [undo] to take them back
	 */
	fun dismiss(manga: Collection<Manga>): List<String> {
		val now = System.currentTimeMillis()
		val items = manga.map {
			val key = DiscoverRanking.titleKey(it.title)
			key to DismissedEntry.of(it, key, workId = null, upload = reporter.stateFor(it), time = now)
		}
		dismissals.store.addAll(items)
		// Whoever has passed on something knows it can be done
		settings.closeTip(AppSettings.TIP_DISCOVER_DISMISS)
		val keys = items.map { it.first }
		reporter.runAsync(keys)
		return keys
	}

	fun undo(key: String) = undo(listOf(key))

	fun undo(keys: Collection<String>) {
		if (dismissals.store.undo(keys) > 0) reporter.runAsync()
	}

	/** "Show what I passed on again". */
	fun undoAll() {
		if (dismissals.store.undoAll() > 0) reporter.runAsync()
	}
}
