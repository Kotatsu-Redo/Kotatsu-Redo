package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.parsers.model.Manga

data class AlternativesSearchOptions(
	val query: String,
	val sourceScope: AlternativeSourceScope = AlternativeSourceScope.ENABLED,
	val sameLanguageOnly: Boolean = false,
	val sameContentTypeOnly: Boolean = false,
	val hideNoChapters: Boolean = false,
	val sortOrder: AlternativeSortOrder = AlternativeSortOrder.BEST_MATCH,
) {

	fun isDefaultFor(title: String): Boolean = this == AlternativesSearchOptions(query = title)
}

enum class AlternativeSourceScope {
	ENABLED,
	PINNED,
	ALL,
}

enum class AlternativeSortOrder {
	BEST_MATCH,
	MOST_CHAPTERS,
	CLOSEST_CHAPTER_COUNT,
	SOURCE_PRIORITY,
}

sealed interface AlternativeSearchEvent {

	data class Result(val manga: Manga) : AlternativeSearchEvent

	data class Progress(
		val completedSources: Int,
		val totalSources: Int,
	) : AlternativeSearchEvent
}

data class AlternativeSearchStatus(
	val completedSources: Int = 0,
	val totalSources: Int = 0,
	val resultsCount: Int = 0,
	val isRunning: Boolean = false,
)
