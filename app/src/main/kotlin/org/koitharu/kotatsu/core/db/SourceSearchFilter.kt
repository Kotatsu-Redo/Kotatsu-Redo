package org.koitharu.kotatsu.core.db

import android.database.DatabaseUtils.sqlEscapeString
import org.koitharu.kotatsu.parsers.model.MangaParserSource

/**
 * Resolves search text to the source names stored in `manga.source`.
 *
 * The column holds the enum name (`MANGAFIRE_EN`, `MANGA_DISTRICT`) while everything the user sees -
 * the search bar, the suggestion row - carries the parser's display title ("MangaFire (English)",
 * "Manga District"). The two are not derivable from one another: titles spell out what enum names
 * abbreviate, so no amount of reshaping the text turns one into the other. Matching the text against
 * the real source list and emitting the exact names is the only correct way.
 *
 * An exact title match wins, which is what a tapped suggestion produces and what keeps sibling locales
 * apart - picking "MangaFire (English)" must not also select `MANGAFIRE_PTBR`. Typed text falls back to
 * a contains match, where selecting every MangaFire locale is the reasonable reading.
 */
internal fun sourceNamesFor(query: String): List<String> {
	val q = query.trim()
	if (q.length < MIN_QUERY_LENGTH) {
		return emptyList()
	}
	val exact = MangaParserSource.entries.filter { it.title.equals(q, ignoreCase = true) }
	if (exact.isNotEmpty()) {
		return exact.map { it.name }
	}
	return MangaParserSource.entries
		.filter { it.title.contains(q, ignoreCase = true) }
		.take(MAX_SOURCES)
		.map { it.name }
}

/** `OR manga.source IN (...)`, or an empty string when the text names no source. */
internal fun sourceCondition(query: String): String {
	val names = sourceNamesFor(query)
	if (names.isEmpty()) {
		return ""
	}
	return "OR manga.source IN (${names.joinToString(", ") { sqlEscapeString(it) }}) "
}

/** Two characters is where a contains match stops selecting most of the source list. */
private const val MIN_QUERY_LENGTH = 2

private const val MAX_SOURCES = 24
