package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcePreset
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val MAX_PARALLELISM = 8
private const val MAX_PER_SOURCE_PARALLELISM = 2
private const val SOURCE_REPLACEMENT_TAG = "SourceReplacement"

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val presetsRepository: SourcePresetsRepository,
) {
	private val requestSemaphore = Semaphore(MAX_PARALLELISM)
	private val webViewSemaphore = Semaphore(1)
	private val sourceSemaphores = mutableMapOf<String, Semaphore>()
	private val webViewSources = mutableMapOf<String, Boolean>()

	suspend operator fun invoke(
		manga: Manga,
		sourceScope: AlternativeSourceScope,
		query: String = manga.title,
		loadDetails: Boolean = true,
	): Flow<Manga> {
		val sources = getCandidateSources(
			ref = manga.source,
			sourceScope = sourceScope,
			sameLanguageOnly = true,
		)
		return search(manga, sources, query, loadDetails)
	}

	/**
	 * @param loadDetails fetch each candidate's details page. Needed for chapter counts, but a caller
	 * that only wants what the search already returned - a cover, say - should pass `false`: it is one
	 * extra network round trip per result.
	 */
	suspend operator fun invoke(
		manga: Manga,
		options: AlternativesSearchOptions,
		loadDetails: Boolean = true,
	): Flow<AlternativeSearchEvent> {
		val query = options.query.trim()
		if (query.isEmpty()) return emptyFlow()
		val sources = getCandidateSources(
			ref = manga.source,
			sourceScope = options.sourceScope,
			sameLanguageOnly = options.sameLanguageOnly,
			sameContentTypeOnly = options.sameContentTypeOnly,
		)
		if (sources.isEmpty()) return emptyFlow()
		return channelFlow {
			val completedSources = AtomicInteger()
			send(AlternativeSearchEvent.Progress(0, sources.size))
			val workerCount = minOf(MAX_PARALLELISM, sources.size)
			repeat(workerCount) { workerIndex ->
				launch {
					for (sourceIndex in workerIndex until sources.size step workerCount) {
						val source = sources[sourceIndex]
						try {
							// Finish this source's candidate details before this worker starts another
							// source, so chapter requests are not starved behind every source search.
							coroutineScope {
								search(
									manga = manga,
									sources = listOf(source),
									query = query,
									loadDetails = false,
								).collect { candidate ->
									launch {
										send(
											AlternativeSearchEvent.Result(
												if (loadDetails) loadDetails(candidate) else candidate,
											),
										)
									}
								}
							}
						} finally {
							send(
								AlternativeSearchEvent.Progress(
									completedSources = completedSources.incrementAndGet(),
									totalSources = sources.size,
								),
							)
						}
					}
				}
			}
		}
	}

	suspend fun getCandidateSources(
		ref: MangaSource,
		sourceScope: AlternativeSourceScope,
		sameLanguageOnly: Boolean = true,
		sameContentTypeOnly: Boolean = false,
	): List<MangaSource> {
		val sources = when (sourceScope) {
			AlternativeSourceScope.CURRENT_PRESET -> getActivePreset()?.let(::getPresetSources).orEmpty()
			AlternativeSourceScope.ENABLED -> sourcesRepository.getEnabledSources()
		}
		return sources.asSequence()
			.distinctBy(MangaSource::name)
			.filter { it.name != ref.name }
			.filterNot { settings.isNsfwContentDisabled && it.isNsfw() }
			.filter { !sameLanguageOnly || it.hasSameLanguageAs(ref) }
			.filter { !sameContentTypeOnly || it.hasSameContentTypeAs(ref) }
			.sortedWith(compareByDescending<MangaSource> { it.priority(ref) }.thenBy(MangaSource::name))
			.toList()
	}

	suspend fun getSourceScopeOptions(
		ref: MangaSource,
		sameLanguageOnly: Boolean = false,
	): AlternativeSourceScopeOptions {
		val preset = getActivePreset()
		val hasPresetCandidates = preset != null && getPresetSources(preset).any { source ->
			source.name != ref.name &&
				(!settings.isNsfwContentDisabled || !source.isNsfw()) &&
				(!sameLanguageOnly || source.hasSameLanguageAs(ref))
		}
		return AlternativeSourceScopeOptions(
			defaultScope = if (hasPresetCandidates) {
				AlternativeSourceScope.CURRENT_PRESET
			} else {
				AlternativeSourceScope.ENABLED
			},
			presetTitle = preset?.title?.takeIf { hasPresetCandidates },
		)
	}

	suspend fun searchSource(
		manga: Manga,
		source: MangaSource,
		query: String = manga.title,
		loadDetails: Boolean = true,
		onFailure: (() -> Unit)? = null,
	): Flow<Manga> = if (source.name == manga.source.name) {
		emptyFlow()
	} else {
		search(
			manga = manga,
			sources = listOf(source),
			query = query,
			loadDetails = loadDetails,
			onSourceFailure = { onFailure?.invoke() },
		)
	}

	private fun search(
		manga: Manga,
		sources: List<MangaSource>,
		query: String,
		loadDetails: Boolean,
		onSourceFailure: (MangaSource) -> Unit = {},
	): Flow<Manga> {
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val searchResult = runCatchingCancellable {
						withSourcePermit(source) {
							searchHelper(query, SearchKind.TITLE)?.manga
						}
					}
					searchResult.exceptionOrNull()?.let { error ->
						Log.w(
							SOURCE_REPLACEMENT_TAG,
							"Search failed: source=${source.name} query=\"${query.take(120)}\"",
							error,
						)
						onSourceFailure(source)
					}
					val list = searchResult.getOrNull()
					list?.forEach { m ->
						if (m.id != manga.id) {
							if (loadDetails) {
								launch {
									send(loadDetails(m))
								}
							} else {
								send(m)
							}
						}
					}
				}
			}
		}
	}

	private suspend fun loadDetails(manga: Manga): Manga {
		val detailsResult = runCatchingCancellable {
			withSourcePermit(manga.source) {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			}
		}
		detailsResult.exceptionOrNull()?.let { error ->
			Log.w(
				SOURCE_REPLACEMENT_TAG,
				"Details load failed; using search result: source=${manga.source.name} title=\"${manga.title}\" id=${manga.id}",
				error,
			)
		}
		return detailsResult.getOrDefault(manga)
	}

	private suspend fun <T> withSourcePermit(source: MangaSource, block: suspend () -> T): T {
		return source.requestSemaphore().withPermit {
			if (source.usesWebView()) {
				webViewSemaphore.withPermit {
					requestSemaphore.withPermit { block() }
				}
			} else {
				requestSemaphore.withPermit { block() }
			}
		}
	}

	fun isWebViewSource(source: MangaSource): Boolean = source.usesWebView()

	private fun MangaSource.requestSemaphore(): Semaphore = synchronized(sourceSemaphores) {
		sourceSemaphores.getOrPut(name) {
			Semaphore(if (usesWebView()) 1 else MAX_PER_SOURCE_PARALLELISM)
		}
	}

	private fun MangaSource.usesWebView(): Boolean = synchronized(webViewSources) {
		webViewSources.getOrPut(name) {
			val repository = mangaRepositoryFactory.create(this)
			((repository as? ParserMangaRepository)
				?.getConfigKeys()
				?.filterIsInstance<ConfigKey.DisableUpdateChecking>()
				?.any { it.defaultValue }
				== true)
		}
	}

	private suspend fun getActivePreset(): SourcePreset? = settings.activeSourcePresetId
		.takeIf { it != 0L }
		?.let { presetsRepository.getById(it) }

	private fun getPresetSources(preset: SourcePreset): List<MangaSource> {
		if (preset.sources.isEmpty()) return emptyList()
		return sourcesRepository.allMangaSources.filter { it.name in preset.sources }
	}

	private fun MangaSource.hasSameLanguageAs(ref: MangaSource): Boolean {
		val refLanguage = ref.getLocale()?.language?.takeIf(String::isNotBlank) ?: return true
		return getLocale()?.language?.takeIf(String::isNotBlank) == refLanguage
	}

	private fun MangaSource.hasSameContentTypeAs(ref: MangaSource): Boolean =
		this is MangaParserSource && ref is MangaParserSource && contentType == ref.contentType

	private fun MangaSource.priority(ref: MangaSource): Int {
		var res = 0
		if (this is MangaParserSource && ref is MangaParserSource) {
			if (locale == ref.locale) {
				res += 4
			} else if (locale.toLocale() == Locale.getDefault()) {
				res += 2
			}
			if (contentType == ref.contentType) {
				res++
			}
		}
		return res
	}
}
