package org.koitharu.kotatsu.core.parser.bundle

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import javax.inject.Inject

/**
 * The rest of the app keeping up with parsers that change while it runs: the repositories it hands out and the table
 * of sources. Needs nothing downloaded: the parsers put in use here are the app's own with a source added.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SourcesFollowTheParsersTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var factory: MangaRepository.Factory

	@Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	@Inject
	lateinit var database: MangaDatabase

	@Before
	fun setUp() {
		hiltRule.inject()
		ActiveParsers.activate(ActiveParsers.builtIn)
	}

	@After
	fun tearDown() {
		ActiveParsers.activate(ActiveParsers.builtIn)
		database.openHelper.writableDatabase.execSQL("DELETE FROM sources WHERE source LIKE '$PREFIX%'")
	}

	@Test
	fun aSourceTheParsersInUseDoNotHaveGetsARepositoryThatSaysSoAndOnlyForAsLongAsThatLasts() {
		val source = newSource("${PREFIX}A")
		assertTrue(factory.create(source) is EmptyMangaRepository)

		ActiveParsers.activate(WiderBundle(ActiveParsers.builtIn, listOf(source)))
		assertTrue(
			"Not held against the source once parsers that have it are in use",
			factory.create(source) is ParserMangaRepository,
		)
	}

	@Test
	fun sourcesThatComeWithNewerParsersGetTheirRowWhateverElseIsWrittenMeanwhile() = runBlocking {
		val dao = database.getSourcesDao()
		// Written back exactly as it is: the table of whoever runs this is not to be left changed
		val enabledNames = dao.findAllEnabledNames().toSet()
		repeat(8) { i ->
			val added = newSource("$PREFIX$i")
			ActiveParsers.activate(WiderBundle(ActiveParsers.builtIn, listOf(added)))
			val enabled = sourcesRepository.allMangaSources.filterTo(HashSet()) { it.name in enabledNames }
			// One taking the new source in from outside a transaction, one from inside its own: with a lock around
			// the taking in, the two waited for each other and nothing was ever written again
			withTimeout(30_000) {
				coroutineScope {
					launch(Dispatchers.Default) { sourcesRepository.getEnabledSources() }
					launch(Dispatchers.Default) { sourcesRepository.setSourcesEnabledExclusive(enabled) }
					launch(Dispatchers.Default) { sourcesRepository.getPinnedSources() }
				}
			}
			assertTrue("${added.name} has its row", dao.findAll().any { it.source == added.name })
		}
		assertTrue(dao.findAllEnabledNames().toSet().containsAll(enabledNames))
	}

	private fun newSource(name: String): ParserMangaSource = object : ParserMangaSource(
		name = name,
		title = name,
		locale = "en",
		contentType = ContentType.MANGA,
		isBroken = false,
	) {}

	/** The parsers given, and sources that only these have. */
	private class WiderBundle(
		private val real: ParsersBundle,
		private val added: List<ParserMangaSource>,
	) : ParsersBundle by real {

		override val sources: List<ParserMangaSource>
			get() = real.sources + added

		override fun findSource(name: String): ParserMangaSource? {
			return added.firstOrNull { it.name == name } ?: real.findSource(name)
		}

		override fun newParser(source: ParserMangaSource, context: MangaLoaderContext): MangaParser {
			return real.newParser(if (source in added) checkNotNull(real.findSource("MANGADEX")) else source, context)
		}
	}

	private companion object {

		const val PREFIX = "ONLY_IN_NEWER_PARSERS_"
	}
}
