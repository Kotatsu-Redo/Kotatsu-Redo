package org.koitharu.kotatsu.history.data

/**
 * One manga in a reader's library with nothing but what working out their taste needs.
 *
 * @param tags tag titles, separated by `|`
 * @param percent how far they got, if they have opened it at all
 */
data class LibraryRow(
	val id: Long,
	val title: String,
	val altTitles: String?,
	val source: String,
	val url: String,
	val percent: Float?,
	val updatedAt: Long?,
	/** How many chapters it had when last read, 0 or null if not known. */
	val chapters: Int?,
	val tags: String?,
)
