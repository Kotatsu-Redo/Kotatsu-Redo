package org.koitharu.kotatsu.core.parser.bundle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The parsers in use right now, for the whole of the app.
 *
 * The app is built with a set of them, and can put a newer set it has downloaded in its place while it runs, see
 * [ParsersUpdater]. So nothing but this is to be asked which sources there are or handed a parser by: the parsers
 * the app was compiled with are only where it starts from.
 *
 * Kept outside of dependency injection because sources are looked up by name from places that have none, see
 * [org.koitharu.kotatsu.core.model.MangaSource].
 */
object ActiveParsers {

	/** What is waited for the parsers kept from the last run, by whoever needs the sources before they are loaded. */
	private const val STARTUP_TIMEOUT_MS = 1_000L

	/** The parsers this app was built with: what it starts from, and goes back to if newer ones turn out bad. */
	@OptIn(DirectParsersAccess::class)
	val builtIn: ParsersBundle = ParsersBundleImpl

	private val generations = MutableStateFlow(Generation(builtIn, number = 0))

	@Volatile
	private var startup: Startup? = null

	val bundle: ParsersBundle
		get() = current().bundle

	/** Goes up by one every time other parsers are put in use. */
	val generation: Int
		get() = current().number

	val isBuiltIn: Boolean
		get() = current().bundle === builtIn

	/** Every source there is, the broken ones included, always in the same order. */
	val sources: List<ParserMangaSource>
		get() = current().bundle.sources

	/** The sources to offer, in that same order. */
	val workingSources: Set<ParserMangaSource>
		get() = current().working

	fun findSource(name: String): ParserMangaSource? = current().bundle.findSource(name)

	/** The current [generation], then every later one. */
	fun observe(): Flow<Int> = generations.map { it.number }.distinctUntilChanged()

	/**
	 * Says that parsers kept from the last run are about to be loaded.
	 *
	 * Until [Startup.finish] is called, whoever asks for the sources waits, for a short time at most: a source that
	 * only the newer parsers have would otherwise be an unknown one for the first moments of every launch.
	 */
	internal fun beginStartup(): Startup {
		val result = Startup()
		startup = result
		return result
	}

	internal fun activate(bundle: ParsersBundle) {
		generations.update { previous ->
			if (previous.bundle === bundle) previous else Generation(bundle, previous.number + 1)
		}
	}

	/** Puts the parsers the app was built with back in use, if [bundle] is what is in use. */
	internal fun deactivate(bundle: ParsersBundle) {
		generations.update { previous ->
			if (previous.bundle === bundle && bundle !== builtIn) Generation(builtIn, previous.number + 1) else previous
		}
	}

	/** The parsers in use and their [generation], as of one same moment. */
	internal fun snapshot(): Generation = current()

	private fun current(): Generation {
		val pending = startup
		if (pending != null && Thread.currentThread() !== pending.loader) {
			if (!pending.await(STARTUP_TIMEOUT_MS)) {
				// Taking too long. Nobody else is to wait for it: the parsers come into use when they are ready
				startup = null
			}
		}
		return generations.value
	}

	internal class Startup {

		private val latch = CountDownLatch(1)

		/** The thread doing the loading, which is not to wait for itself. */
		@Volatile
		var loader: Thread? = null

		/** @return `false` if the loading was still going on after [timeoutMs] */
		fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

		fun finish() {
			if (startup === this) {
				startup = null
			}
			latch.countDown()
		}
	}

	internal class Generation(
		val bundle: ParsersBundle,
		val number: Int,
	) {

		val working: Set<ParserMangaSource> by lazy(LazyThreadSafetyMode.PUBLICATION) {
			Collections.unmodifiableSet(bundle.sources.filterNotTo(LinkedHashSet()) { it.isBroken })
		}
	}
}
