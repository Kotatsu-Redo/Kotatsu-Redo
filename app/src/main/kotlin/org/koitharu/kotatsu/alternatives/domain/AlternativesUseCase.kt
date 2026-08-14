package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val presetsRepository: SourcePresetsRepository,
) {

	suspend operator fun invoke(manga: Manga, options: AlternativesSearchOptions): Flow<AlternativeSearchEvent> {
		val query = options.query.trim()
		if (query.isEmpty()) {
			return emptyFlow()
		}
		val sources = getSources(manga.source, options)
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			val completedSources = AtomicInteger()
			send(AlternativeSearchEvent.Progress(0, sources.size))
			for (source in sources) {
				launch {
					try {
						val searchHelper = searchHelperFactory.create(source)
						val list = runCatchingCancellable {
							semaphore.withPermit {
								searchHelper(query, SearchKind.TITLE)?.manga
							}
						}.getOrNull()
						coroutineScope {
							list?.forEach { m ->
								if (m.id != manga.id) {
									launch {
										val details = runCatchingCancellable {
											mangaRepositoryFactory.create(m.source).getDetails(m)
										}.getOrDefault(m)
										send(AlternativeSearchEvent.Result(details))
									}
								}
							}
						}
					} finally {
						send(AlternativeSearchEvent.Progress(completedSources.incrementAndGet(), sources.size))
					}
				}
			}
		}
	}

	private suspend fun getSources(ref: MangaSource, options: AlternativesSearchOptions): List<MangaSource> {
		val sources = when (options.sourceScope) {
			AlternativeSourceScope.ENABLED -> getEnabledSources()
			AlternativeSourceScope.PINNED -> sourcesRepository.getPinnedSources().toList()
			AlternativeSourceScope.ALL -> buildList {
				addAll(sourcesRepository.getEnabledSources())
				addAll(sourcesRepository.getDisabledSources())
			}
		}
		return sources.asSequence()
			.distinctBy(MangaSource::name)
			.filter { it != ref }
			.filterNot { settings.isNsfwContentDisabled && it.isNsfw() }
			.filter { source ->
				if (!options.sameLanguageOnly && !options.sameContentTypeOnly) {
					return@filter true
				}
				val parserSource = source as? MangaParserSource ?: return@filter false
				val parserRef = ref as? MangaParserSource ?: return@filter false
				(!options.sameLanguageOnly || parserSource.locale == parserRef.locale) &&
					(!options.sameContentTypeOnly || parserSource.contentType == parserRef.contentType)
			}
			.sortedWith(compareByDescending<MangaSource> { it.priority(ref) }.thenBy { it.name })
			.toList()
	}

	private suspend fun getEnabledSources(): List<MangaSource> {
		val presetId = settings.activeSourcePresetId
		if (presetId != 0L) {
			val preset = presetsRepository.getById(presetId)
			if (preset != null) {
				if (preset.sources.isEmpty()) return emptyList()
				val skipNsfw = settings.isNsfwContentDisabled
				return sourcesRepository.allMangaSources.filter { source ->
					source.name in preset.sources && (!skipNsfw || !source.isNsfw())
				}
			}
		}
		return sourcesRepository.getEnabledSources()
	}

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
