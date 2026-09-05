package org.koitharu.kotatsu.core.parser

import android.net.Uri
import coil3.request.CachePolicy
import dagger.Reusable
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@Reusable
class MangaLinkResolver @Inject constructor(
	private val repositoryFactory: MangaRepository.Factory,
	private val dataRepository: MangaDataRepository,
	private val context: MangaLoaderContext,
) {

	suspend fun resolve(uri: Uri): Manga {
		return if (uri.scheme == "kotatsu" || uri.host == "kotatsu.app" || uri.host == BuildConfig.APP_LINK_HOST) {
			resolveAppLink(uri)
		} else {
			resolveExternalLink(uri.toString())
		} ?: throw NotFoundException("Cannot resolve link", uri.toString())
	}

	private suspend fun resolveAppLink(uri: Uri): Manga? {
		// "manga" is the host in kotatsu://manga?..., but the only path segment in
		// https://kotatsu.app/manga?... and in the internal kotatsu:/manga?id=... short links.
		// All three are in circulation, so accept any of them.
		require(uri.host == "manga" || uri.pathSegments.singleOrNull() == "manga") { "Invalid url" }
		uri.getQueryParameter("id")?.let { mangaId ->
			// short url
			return dataRepository.findMangaById(mangaId.toLong(), withChapters = false)
		}
		val sourceName = requireNotNull(uri.getQueryParameter("source")) { "Source is not specified" }
		val source = MangaSource(sourceName)
		require(source != UnknownMangaSource) { "Manga source $sourceName is not supported" }
		val repo = repositoryFactory.create(source)
		return repo.findExact(
			url = uri.getQueryParameter("url"),
			title = uri.getQueryParameter("name"),
		)
	}

	private suspend fun resolveExternalLink(uri: String): Manga? {
		dataRepository.findMangaByPublicUrl(uri)?.let {
			return it
		}
		return context.newLinkResolver(uri).getManga()
	}

	private suspend fun MangaRepository.findExact(url: String?, title: String?): Manga? {
		if (!title.isNullOrEmpty()) {
			val list = getList(0, null, MangaListFilter(query = title))
			if (url != null) {
				list.find { it.url == url }?.let {
					return it
				}
			}
			list.minByOrNull { it.title.levenshteinDistance(title) }
				?.takeIf { it.title.almostEquals(title, 0.2f) }
				?.let { return it }
		}
		// Fetching the details page for the exact url already yields the manga the link points at.
		val seed = getDetailsNoCache(
			getSeedManga(source, url ?: return null, title),
		)
		// The search below only tries to upgrade that into the source's own list entry, which is a
		// nice-to-have: it fails whenever the source's search cannot find its own title (punctuation
		// is a common culprit) or returns urls in a different form. That used to throw
		// NoSuchElementException out of `first` and break the whole link, so fall back to the seed.
		return runCatchingCancellable {
			val seedTitle = seed.title.ifEmpty {
				seed.altTitle
			}.ifNullOrEmpty {
				seed.author
			} ?: return@runCatchingCancellable null
			val seedList = getList(0, null, MangaListFilter(query = seedTitle))
			seedList.firstOrNull { x -> x.url == url }
		}.getOrNull()
			// Prefer an entry the app already knows about. Most parsers derive a manga id from its url,
			// which is what getSeedManga reproduces, but some derive it from a numeric id instead - for
			// those the seed would carry a different id than the same manga already has locally, and
			// splitting the id splits its history and favourites.
			?: seed.publicUrl.takeIf { it.isNotEmpty() }?.let { dataRepository.findMangaByPublicUrl(it) }
			?: seed
	}

	private suspend fun MangaRepository.getDetailsNoCache(manga: Manga): Manga = if (this is CachingMangaRepository) {
		getDetails(manga, CachePolicy.READ_ONLY)
	} else {
		getDetails(manga)
	}

	private fun getSeedManga(source: MangaSource, url: String, title: String?) = Manga(
		id = run {
			var h = 1125899906842597L
			source.name.forEach { c ->
				h = 31 * h + c.code
			}
			url.forEach { c ->
				h = 31 * h + c.code
			}
			h
		},
		title = title.orEmpty(),
		altTitle = null,
		url = url,
		publicUrl = "",
		rating = 0.0f,
		isNsfw = source.isNsfw(),
		coverUrl = "",
		tags = emptySet(),
		state = null,
		author = null,
		largeCoverUrl = null,
		description = null,
		chapters = null,
		source = source,
	)

	companion object {

		fun isValidLink(str: String): Boolean {
			return str.isHttpUrl() || str.startsWith("kotatsu://", ignoreCase = true)
		}
	}
}
