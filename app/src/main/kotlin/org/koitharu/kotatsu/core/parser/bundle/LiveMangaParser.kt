package org.koitharu.kotatsu.core.parser.bundle

import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import java.io.IOException

/**
 * The parser of one source, whichever parsers are in use: it is the parser of [ActiveParsers] at every call, so
 * whoever holds it is on the newer parsers from the moment they come into use, with nothing to be told or rebuilt.
 *
 * It is also where a parser failing is taken as the sign that there may be a repaired one to have. Anything that goes
 * to the site is tried once more when [ParsersUpdater] comes back with other parsers than the ones that failed.
 *
 * @param sourceName the source is kept by name, the one thing about it that stays put from one set of parsers to
 * the next
 */
class LiveMangaParser(
	private val sourceName: String,
	private val context: MangaLoaderContext,
	private val updater: ParsersUpdater,
) : MangaParser {

	@Volatile
	private var current: Current? = null

	override val source: ParserMangaSource
		get() = call { it.source }

	override val availableSortOrders: Set<SortOrder>
		get() = call { it.availableSortOrders }

	@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
	override val searchQueryCapabilities: MangaSearchQueryCapabilities
		get() = call { it.searchQueryCapabilities }

	override val filterCapabilities: MangaListFilterCapabilities
		get() = call { it.filterCapabilities }

	override val config: MangaSourceConfig
		get() = call { it.config }

	override val authorizationProvider: MangaParserAuthProvider?
		get() = call { it.authorizationProvider }

	override val configKeyDomain: ConfigKey.Domain
		get() = call { it.configKeyDomain }

	override val domain: String
		get() = call { it.domain }

	@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
	override suspend fun getList(query: MangaSearchQuery): List<Manga> = request { it.getList(query) }

	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> = request {
		it.getList(offset, order, filter)
	}

	override suspend fun getDetails(manga: Manga): Manga = request { it.getDetails(manga) }

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = request { it.getPages(chapter) }

	override suspend fun getPageUrl(page: MangaPage): String = request { it.getPageUrl(page) }

	override suspend fun getFilterOptions(): MangaListFilterOptions = request { it.getFilterOptions() }

	override suspend fun getFavicons(): Favicons = request { it.getFavicons() }

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = request { it.getRelatedManga(seed) }

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) = call { it.onCreateConfig(keys) }

	override fun getRequestHeaders(): Headers = call { it.getRequestHeaders() }

	// Not asked again of other parsers: the request may be on its way already, and is not to be sent twice
	override fun intercept(chain: Interceptor.Chain): Response {
		val used = obtain()
		try {
			return used.parser.intercept(chain)
		} catch (e: LinkageError) {
			throw onBroken(used, e)
		}
	}

	/**
	 * Something asked of the site. When it fails and other parsers than the ones that failed can be had, it is asked
	 * once more of those, and what the caller gets is the answer to that.
	 */
	private suspend inline fun <T> request(block: (MangaParser) -> T): T {
		val used = obtain()
		val failure = try {
			return block(used.parser)
		} catch (e: CancellationException) {
			throw e
		} catch (e: LinkageError) {
			onBroken(used, e)
		} catch (e: Exception) {
			e
		}
		if (!updater.updateAfterFailure(failure, used.generation)) {
			throw failure
		}
		val replacement = obtain()
		if (replacement === used) {
			// The other parsers have nothing else for this source
			throw failure
		}
		return try {
			block(replacement.parser)
		} catch (e: LinkageError) {
			throw onBroken(replacement, e)
		}
	}

	private inline fun <T> call(block: (MangaParser) -> T): T {
		val used = obtain()
		val failure = try {
			return block(used.parser)
		} catch (e: LinkageError) {
			onBroken(used, e)
		}
		// Given up, and the parsers the app was built with are back: the answer is theirs to give
		val replacement = obtain()
		if (replacement === used) {
			// They have no such source. Asking the same parser again would only fail the same way
			throw failure
		}
		return block(replacement.parser)
	}

	private fun obtain(): Current {
		var attempts = 0
		while (true) {
			val last = current
			val now = ActiveParsers.snapshot()
			if (last != null && last.generation == now.number) {
				return last
			}
			val bundle = now.bundle
			// A set of parsers can lack a source the one before it had: taken out of the newer ones, or only ever in
			// newer ones that were given up. The parser there was goes on serving it, for as long as this is held
			val source = bundle.findSource(sourceName) ?: return last ?: throw IllegalStateException(
				"Manga source $sourceName is not among the parsers of ${bundle.revision}",
			)
			val parser = try {
				bundle.newParser(source, context)
			} catch (e: LinkageError) {
				if (++attempts > MAX_BUNDLES_TRIED || !isMisfit(bundle, e)) {
					throw e
				}
				updater.giveUp(bundle, e)
				continue
			}
			return Current(now.number, bundle, parser).also { current = it }
		}
	}

	/**
	 * Downloaded parsers reaching for something this app does not have. It is not to happen, every set being checked
	 * against what apps have before it is published, and if it does it is not this one source that is broken but
	 * the whole set: it is given up, for the parsers the app was built with.
	 *
	 * Anything else is thrown as it is, as it would be from the parsers the app was built with.
	 *
	 * @return what to throw in place of [error], which as an [Error] would take the app down
	 */
	private fun onBroken(used: Current, error: LinkageError): Exception {
		if (!isMisfit(used.bundle, error)) {
			throw error
		}
		updater.giveUp(used.bundle, error)
		return IOException("The downloaded parsers do not fit this app", error)
	}

	private class Current(
		val generation: Int,
		val bundle: ParsersBundle,
		val parser: MangaParser,
	)

	internal companion object {

		private const val MAX_BUNDLES_TRIED = 2

		/**
		 * Whether [error] is downloaded parsers and this app not fitting together: a class or a member the parsers
		 * count on and the app does not have, or the other way round.
		 *
		 * Not every [LinkageError] is. A parser whose own start-up code throws is reported as one, and so is every
		 * later use of that parser's class, of a class built on it, or from another thread while it was failing: all
		 * of that is a mistake in one parser, the same whether downloaded or not. And the request a parser passes on
		 * runs a good deal of the app's own code under it, which can fail in ways that are nothing to do with the
		 * parsers. So what is at the bottom of the error has to be of the kind, and the error has to have been
		 * raised in the parsers' code or in calling into it. When in doubt it is not one: the cost of that is one
		 * source failing, where the cost of the opposite is every downloaded parser being given up.
		 */
		fun isMisfit(bundle: ParsersBundle, error: LinkageError): Boolean {
			if (bundle === ActiveParsers.builtIn) {
				return false
			}
			// A class that could not be set up, or found, says why underneath. What is at the bottom counts, and
			// where the last of the link errors on the way down was raised
			var link: LinkageError = error
			var bottom: Throwable = error
			var depth = 0
			while ((bottom is ExceptionInInitializerError || bottom is NoClassDefFoundError) && depth++ < 4) {
				val next = bottom.cause ?: break
				bottom = next
				if (next is LinkageError) {
					link = next
				}
			}
			val isOfTheKind = when (bottom) {
				// Under a NoClassDefFoundError: the class is in neither the parsers nor the app
				is ClassNotFoundException -> true
				// With nothing underneath: start-up code that failed here, in another thread or at an earlier use
				is ExceptionInInitializerError, is NoClassDefFoundError -> false
				// A class built on one whose start-up code failed is turned down for that, and for nothing of its own
				is VerifyError -> bottom.message?.contains("erroneous class") != true
				is IncompatibleClassChangeError, is ClassFormatError -> true
				// Whatever else start-up code threw: an ordinary mistake, or the phone running out of memory
				else -> false
			}
			if (!isOfTheKind) {
				return false
			}
			val raisedIn = link.stackTrace.firstOrNull()?.className ?: return false
			return raisedIn == LiveMangaParser::class.java.name || ParserBundleStore.isCodeOf(bundle, raisedIn)
		}
	}
}
