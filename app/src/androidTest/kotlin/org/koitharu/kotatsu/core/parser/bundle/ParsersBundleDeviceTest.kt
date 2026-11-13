package org.koitharu.kotatsu.core.parser.bundle

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Newer parsers taken in from files, on a real device, where the loading of code is what it is on a phone.
 *
 * The parsers themselves are not in the repository: they are megabytes, and are built from the parsers library
 * (`./gradlew parsersBundle`, twice, the second with a later `-PparsersVersion`). To run this, push them to the
 * app's own folder on the device, as `files/parsers-bundle/a` and `files/parsers-bundle/b`, the two files of each
 * build in each, with the public half of the key they are signed with as `files/parsers-bundle/key.pub`. Without
 * them every test here is skipped.
 */
@RunWith(AndroidJUnit4::class)
class ParsersBundleDeviceTest {

	private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
	private val application = context.applicationContext as Application
	// On shared storage where the shell may still write there, inside the app (through run-as) since Android 11
	private val pushed = File(context.getExternalFilesDir(null), "parsers-bundle").takeIf { File(it, "key.pub").isFile }
		?: File(context.filesDir, "parsers-bundle")
	private val older = File(pushed, "a")
	private val newer = File(pushed, "b")

	/**
	 * Parsers made not to fit: built with a class of the contract that has a method no app has, which one of their
	 * parsers (HIZOMANGA) calls, and with two sources (ZZ_FAILS_TO_START, ZZ_BUILT_ON_IT) whose own start-up code
	 * throws. Pushed as `files/parsers-bundle/c`; the tests that need them are skipped without.
	 */
	private val illFitting = File(pushed, "c")
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private val requests = AtomicInteger()

	private lateinit var keys: List<PublicKey>
	private lateinit var root: File
	private lateinit var state: SharedPreferences

	/** What the server has at the moment, or `null` for nothing. */
	@Volatile
	private var served: File? = null

	@Volatile
	private var now = 1_000_000_000_000L

	/** Requests for files whose name ends like this are kept waiting until [held] is let go. */
	@Volatile
	private var heldSuffix = ""

	@Volatile
	private var held: CountDownLatch? = null

	/** No answer at all, as with no connection or a server that is down. */
	@Volatile
	private var isUnreachable = false

	private val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
		requests.incrementAndGet()
		if (isUnreachable) {
			throw IOException("Unable to resolve host")
		}
		val name = chain.request().url.pathSegments.last()
		held?.takeIf { name.endsWith(heldSuffix) }?.await(60, TimeUnit.SECONDS)
		val file = served?.let { File(it, name) }
		val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
		if (file != null && file.isFile) {
			response.code(200).message("OK").body(file.readBytes().toResponseBody()).build()
		} else {
			response.code(404).message("Not Found").body("".toResponseBody()).build()
		}
	}.build()

	@Before
	fun setUp() {
		assumeTrue("Only where parsers are downloaded at all", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		assumeTrue("No parsers were pushed to $pushed", File(pushed, "key.pub").isFile)
		keys = ParsersManifest.readKeys(File(pushed, "key.pub").readText())
		root = File(context.codeCacheDir, "parsers-test-${System.nanoTime()}")
		state = context.getSharedPreferences("parsers_bundle_test", Context.MODE_PRIVATE)
		state.edit().clear().commit()
		ActiveParsers.activate(ActiveParsers.builtIn)
	}

	@After
	fun tearDown() {
		ActiveParsers.activate(ActiveParsers.builtIn)
		held?.countDown()
		scope.cancel()
		if (::root.isInitialized) {
			root.deleteRecursively()
		}
	}

	@Test
	fun downloadedParsersAreLoadedFromTheirOwnFilesAndNotFromTheApp() {
		val (manifest, bundle) = install(older)
		val appLoader = ParsersUpdater::class.java.classLoader
		assertNotSame(appLoader, bundle.javaClass.classLoader)
		assertNotSame(ActiveParsers.builtIn, bundle)
		// One name, two classes: this is the app's copy being left aside for the downloaded one
		assertEquals(ActiveParsers.builtIn.javaClass.name, bundle.javaClass.name)
		assertEquals(manifest.version, bundle.version)
		assertEquals(manifest.revision, bundle.revision)
		assertTrue(bundle.version > ActiveParsers.builtIn.version)
		assertTrue(bundle.sources.size > 1000)
		// What the two have in common is the app's, and one class for both
		assertSame(ParsersBundle::class.java, bundle.javaClass.interfaces.single())
	}

	@Test
	fun everySourceOfDownloadedParsersHasAParserOfTheirs() {
		val (_, bundle) = install(older)
		val loader = bundle.javaClass.classLoader
		for (source in bundle.sources) {
			val parser = bundle.newParser(source, FakeLoaderContext)
			assertSame(source.name, loader, parser.javaClass.classLoader)
			assertSame(source.name, source, parser.source)
			assertTrue(source.name, parser.configKeyDomain.presetValues.isNotEmpty())
		}
	}

	@Test
	fun aSourceOfDownloadedParsersIsTheSameSourceAsTheApps() {
		val (_, bundle) = install(older)
		val own = checkNotNull(ActiveParsers.builtIn.findSource("MANGADEX"))
		val downloaded = checkNotNull(bundle.findSource("MANGADEX"))
		assertNotSame(own, downloaded)
		assertNotSame(own.javaClass, downloaded.javaClass)
		assertEquals(own, downloaded)
		assertEquals(downloaded, own)
		assertEquals(own.hashCode(), downloaded.hashCode())
		assertTrue(hashSetOf<MangaSource>(own).contains(downloaded))
		// Asked for a parser with the app's object, downloaded parsers answer with their own
		assertSame(downloaded, bundle.newParser(own, FakeLoaderContext).source)
	}

	@Test
	fun anErrorCarryingADownloadedSourceCanBeKeptAndReadBack() {
		val (_, bundle) = install(older)
		val downloaded = checkNotNull(bundle.findSource("MANGADEX"))
		// What the app does with an error it means to show in a dialog or to report from a notification
		val error = AuthRequiredException(downloaded)
		assertTrue(error.isSerializable())
		val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(error) } }.toByteArray()
		// Nothing of the downloaded parsers is named in it: it has to be readable by an app that no longer has them
		assertFalse(String(bytes, Charsets.ISO_8859_1).contains("MangaParserSource"))
		val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as AuthRequiredException
		assertEquals(downloaded, restored.source)
		assertNotSame(downloaded, restored.source)

		// And the source read back is enough to get to the parser by
		ActiveParsers.activate(bundle)
		val live = LiveMangaParser(restored.source.name, FakeLoaderContext, newUpdater())
		assertSame(downloaded, live.source)
		assertSame(downloaded, MangaSource(restored.source.name))
	}

	@Test
	fun anArchiveThatIsNotTheSignedOneIsRefusedAndNothingOfItIsKept() {
		val store = newStore()
		val envelope = File(older, MANIFEST).readBytes()
		val manifest = store.readManifest(envelope)

		val changed = store.newDownloadFile()
		File(older, manifest.file).copyTo(changed, overwrite = true)
		val bytes = changed.readBytes()
		bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
		changed.writeBytes(bytes)
		assertThrows(IOException::class.java) { store.install(envelope, changed) }

		// Another build of the parsers, whole and well signed, under the manifest of this one
		val swapped = store.newDownloadFile()
		File(newer, store.readManifest(File(newer, MANIFEST).readBytes()).file).copyTo(swapped, overwrite = true)
		assertThrows(IOException::class.java) { store.install(envelope, swapped) }

		assertFalse(store.has(manifest.version))
		assertThrows(Exception::class.java) { store.load(manifest.version) }
		swapped.delete()
		assertTrue(root.listFiles().orEmpty().isEmpty())
	}

	@Test
	fun parsersSignedWithAnotherKeyAreRefused() {
		val stranger = KeyPairGenerator.getInstance("EC").run {
			initialize(ECGenParameterSpec("secp256r1"))
			generateKeyPair().public
		}
		val store = newStore(listOf(stranger))
		val envelope = File(older, MANIFEST).readBytes()
		val archive = store.newDownloadFile()
		File(older, newStore().readManifest(envelope).file).copyTo(archive, overwrite = true)
		assertThrows(ParsersManifestException::class.java) { store.install(envelope, archive) }

		// Installed when the key was trusted, and found there by a build that trusts another: not loaded, and not held
		// against the parsers either, which are for the server to offer again under a key this build trusts
		val (manifest, _) = install(older)
		val refusal = assertThrows(ParsersFilesException::class.java) { store.load(manifest.version) }
		assertTrue(refusal.cause is ParsersManifestException)
	}

	@Test
	fun parsersDamagedOnDiskAreNotLoaded() {
		val (manifest, _) = install(older)
		val dex = File(File(root, "v${manifest.version}"), manifest.dex.first().name)
		assertFalse("Code is loaded from files that cannot be written to", dex.canWrite())
		assertTrue(dex.setWritable(true))
		dex.writeBytes(dex.readBytes().copyOf(dex.length().toInt() / 2))
		assertThrows(IOException::class.java) { newStore().load(manifest.version) }
	}

	@Test
	fun aParserInUseFollowsTheParsersInUse() {
		val updater = newUpdater()
		val live = LiveMangaParser("MANGADEX", FakeLoaderContext, updater)
		val ownSource = live.source
		assertSame(ActiveParsers.builtIn.findSource("MANGADEX"), ownSource)
		assertSame(ownSource, MangaSource("MANGADEX"))
		val generation = ActiveParsers.generation

		val (_, bundle) = install(older)
		ActiveParsers.activate(bundle)
		assertEquals(generation + 1, ActiveParsers.generation)
		assertFalse(ActiveParsers.isBuiltIn)
		// The same object as before, now answering with the downloaded parsers
		assertSame(bundle.findSource("MANGADEX"), live.source)
		assertSame(bundle.javaClass.classLoader, live.source.javaClass.classLoader)
		assertEquals(ownSource, live.source)
		assertSame(bundle.findSource("MANGADEX"), MangaSource("MANGADEX"))
		assertTrue(live.configKeyDomain.presetValues.isNotEmpty())
		assertEquals(bundle.sources.count { !it.isBroken }, ActiveParsers.workingSources.size)

		ActiveParsers.activate(ActiveParsers.builtIn)
		assertSame(ownSource, live.source)
		assertSame(UnknownMangaSource, MangaSource("NO_SUCH_SOURCE"))
	}

	@Test
	fun newerParsersAreFoundTakenInAndPutInUse() = runBlocking {
		val updater = newUpdater()
		served = null
		assertFalse("Nothing published yet", updater.checkNow())
		assertTrue(ActiveParsers.isBuiltIn)

		served = older
		val olderManifest = newStore().readManifest(File(older, MANIFEST).readBytes())
		assertTrue(updater.checkNow())
		assertEquals(olderManifest.version, ActiveParsers.bundle.version)
		assertFalse("The same parsers are not taken twice", updater.checkNow())

		served = newer
		val newerManifest = newStore().readManifest(File(newer, MANIFEST).readBytes())
		assertTrue(newerManifest.version > olderManifest.version)
		assertTrue(updater.checkNow())
		assertEquals(newerManifest.version, ActiveParsers.bundle.version)
		// The ones before are gone from disk, and so is the download
		assertEquals(listOf("v${newerManifest.version}"), root.list().orEmpty().toList())

		served = older
		assertFalse("There is no going back to older parsers", updater.checkNow())
		assertEquals(newerManifest.version, ActiveParsers.bundle.version)
	}

	@Test
	fun parsersKeptFromTheLastRunAreInUseAgainWhenTheAppStarts() = runBlocking {
		served = older
		assertTrue(newUpdater().checkNow())
		val version = ActiveParsers.bundle.version

		// The process starting over: the app's own parsers, and a new updater that knows nothing yet
		ActiveParsers.activate(ActiveParsers.builtIn)
		served = null
		val requestsBefore = requests.get()
		val restarted = newUpdater()
		InstrumentationRegistry.getInstrumentation().runOnMainSync { restarted.start(application) }
		// Asked right away, as the app does: the answer waits for the kept parsers
		assertEquals(version, ActiveParsers.bundle.version)
		assertFalse(ActiveParsers.isBuiltIn)
		assertNotNull(ActiveParsers.findSource("MANGADEX"))
		assertEquals("Nothing is downloaded to start", requestsBefore, requests.get())
	}

	@Test
	fun aFailingParserGetsNewerParsersWhenThatMayHelp() = runBlocking {
		val updater = newUpdater()
		served = older
		assertTrue(updater.checkNow())
		val generation = ActiveParsers.generation
		val source = checkNotNull(ActiveParsers.findSource("MANGADEX"))
		served = newer

		// Just looked: a second failure right after does not go to the server again
		var requestsBefore = requests.get()
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		assertEquals(requestsBefore, requests.get())

		now += TimeUnit.MINUTES.toMillis(6)
		// Asking the user to log in is not something other parsers would do in the user's place
		assertFalse(updater.updateAfterFailure(AuthRequiredException(source), generation))
		assertEquals(requestsBefore, requests.get())

		assertTrue(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		assertEquals(generation + 1, ActiveParsers.generation)
		assertTrue(requests.get() > requestsBefore)

		// Whoever failed with the parsers before is told at once, with nothing asked of anyone
		requestsBefore = requests.get()
		assertTrue(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		assertEquals(requestsBefore, requests.get())
	}

	@Test
	fun nothingIsAskedOfTheServerMoreOftenThanItIsWorth() = runBlocking {
		val updater = newUpdater()
		served = older
		assertTrue(updater.checkNow())
		served = newer
		val requestsBefore = requests.get()
		repeat(20) { updater.onMangaOpened() }
		Thread.sleep(300)
		assertEquals(requestsBefore, requests.get())
		assertEquals(now, updater.lastCheckTime)

		now += TimeUnit.MINUTES.toMillis(61)
		updater.onMangaOpened()
		val deadline = System.currentTimeMillis() + 20_000
		while (ActiveParsers.bundle.version == readVersion(older) && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertEquals(readVersion(newer), ActiveParsers.bundle.version)
	}

	@Test
	fun parsersGivenUpAreNotTakenAgain() = runBlocking {
		val updater = newUpdater()
		served = older
		assertTrue(updater.checkNow())
		val live = LiveMangaParser("MANGADEX", FakeLoaderContext, updater)
		assertNotSame(ActiveParsers.builtIn.findSource("MANGADEX"), live.source)

		updater.giveUp(ActiveParsers.bundle, NoSuchMethodError("okhttp3.Response.somethingNew()"))
		assertTrue(ActiveParsers.isBuiltIn)
		assertSame(ActiveParsers.builtIn.findSource("MANGADEX"), live.source)
		assertFalse("Still published, and not wanted", updater.checkNow())
		assertTrue(ActiveParsers.isBuiltIn)

		// Nor after a restart
		val restarted = newUpdater()
		InstrumentationRegistry.getInstrumentation().runOnMainSync { restarted.start(application) }
		assertTrue(ActiveParsers.isBuiltIn)
		assertFalse("Still published, and still not wanted by this build", restarted.checkNow())
		assertTrue(ActiveParsers.isBuiltIn)

		// What is published next is another matter
		served = newer
		assertTrue(updater.checkNow())
		assertEquals(readVersion(newer), ActiveParsers.bundle.version)
	}

	@Test
	fun parsersGoneFromWhereTheyWereKeptAreDownloadedAgainAndNotGivenUp() = runBlocking {
		served = older
		assertTrue(newUpdater().checkNow())
		val version = ActiveParsers.bundle.version
		val manifest = newStore().readManifest(File(older, MANIFEST).readBytes())
		// Not cut short in place: what is loaded from it is still mapped
		assertTrue(File(File(root, "v$version"), manifest.dex.first().name).delete())

		// The process starting over less than an hour later, with the same parsers still published
		ActiveParsers.activate(ActiveParsers.builtIn)
		val restarted = newUpdater()
		InstrumentationRegistry.getInstrumentation().runOnMainSync { restarted.start(application) }
		val deadline = System.currentTimeMillis() + 20_000
		while (ActiveParsers.isBuiltIn && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertEquals("Downloaded again, with no hour to wait for", version, ActiveParsers.bundle.version)
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().isEmpty())
	}

	@Test
	fun aClockSetBackDoesNotPutOffTheNextLook() = runBlocking {
		val updater = newUpdater()
		served = older
		assertTrue(updater.checkNow())
		served = newer
		now -= TimeUnit.DAYS.toMillis(2)
		updater.onMangaOpened()
		val deadline = System.currentTimeMillis() + 20_000
		while (ActiveParsers.bundle.version == readVersion(older) && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertEquals(readVersion(newer), ActiveParsers.bundle.version)
	}

	@Test
	fun parsersGivenUpByAnotherBuildOfTheAppGetAnotherTry() = runBlocking {
		val updater = newUpdater()
		served = older
		assertTrue(updater.checkNow())
		updater.giveUp(ActiveParsers.bundle, NoSuchMethodError("okhttp3.Response.somethingNew()"))
		assertFalse(updater.checkNow())

		// Started again as it is: nothing is forgotten
		val same = newUpdater()
		InstrumentationRegistry.getInstrumentation().runOnMainSync { same.start(application) }
		assertFalse(same.checkNow())

		// The app updated, to a build still older than what is published
		ActiveParsers.activate(ActiveParsers.builtIn)
		val updated = newUpdater(appVersion = 2)
		InstrumentationRegistry.getInstrumentation().runOnMainSync { updated.start(application) }
		assertTrue(ActiveParsers.isBuiltIn)
		assertTrue(updated.checkNow())
		assertEquals(readVersion(older), ActiveParsers.bundle.version)
	}

	@Test
	fun onlyParsersThatDoNotFitTheAppAreTakenForThat() {
		val (_, bundle) = install(older)
		val theirs = bundle.javaClass.name
		val calling = LiveMangaParser::class.java.name
		fun <T : Throwable> T.raisedIn(className: String): T = apply {
			stackTrace = arrayOf(StackTraceElement(className, "x", null, -1))
		}
		fun missing(name: String) = NoClassDefFoundError("Failed resolution of: $name").raisedIn(theirs).apply {
			initCause(ClassNotFoundException(name))
		}
		assertTrue(LiveMangaParser.isMisfit(bundle, NoSuchMethodError("m").raisedIn(theirs)))
		assertTrue(LiveMangaParser.isMisfit(bundle, missing("Lokhttp3/SomethingNew;")))
		assertTrue(LiveMangaParser.isMisfit(bundle, AbstractMethodError("m").raisedIn(calling)))
		assertTrue(LiveMangaParser.isMisfit(bundle, VerifyError("Verifier rejected class X").raisedIn(theirs)))
		assertTrue(
			"A class of theirs that cannot be set up for what it reaches for",
			LiveMangaParser.isMisfit(bundle, ExceptionInInitializerError(NoSuchFieldError("f").raisedIn(theirs))),
		)

		// The start-up code of one parser throwing: a mistake in that parser, downloaded or not. Every shape of it
		val startUp = ExceptionInInitializerError(IllegalStateException()).raisedIn(theirs)
		assertFalse(LiveMangaParser.isMisfit(bundle, startUp))
		val again = NoClassDefFoundError("Could not initialize").raisedIn(theirs)
		again.initCause(ExceptionInInitializerError(IllegalStateException()))
		assertFalse("Used again later", LiveMangaParser.isMisfit(bundle, again))
		assertFalse(
			"Used from another thread meanwhile",
			LiveMangaParser.isMisfit(bundle, NoClassDefFoundError("<clinit> failed; see exception in other thread").raisedIn(theirs)),
		)
		assertFalse(
			"A parser built on the one that failed",
			LiveMangaParser.isMisfit(
				bundle,
				VerifyError("Rejecting class B that attempts to sub-type erroneous class A").raisedIn(theirs),
			),
		)
		val outOfMemory = NoClassDefFoundError("Could not initialize").raisedIn(theirs)
		outOfMemory.initCause(OutOfMemoryError())
		assertFalse("Start-up code the phone had no memory for", LiveMangaParser.isMisfit(bundle, outOfMemory))

		// Something of the app's failing under a request a parser passed on
		assertFalse(LiveMangaParser.isMisfit(bundle, NoSuchMethodError("m").raisedIn("okhttp3.internal.http.RealChain")))
		// Named like a parser, and not one they have loaded: the app's own copy, or anything its shrinker put there
		assertFalse(
			LiveMangaParser.isMisfit(bundle, NoSuchMethodError("m").raisedIn("org.koitharu.kotatsu.parsers.site.Nope")),
		)
		assertFalse(ParserBundleStore.isCodeOf(bundle, "org.koitharu.kotatsu.parsers.MangaParser"))
		assertFalse(LiveMangaParser.isMisfit(ActiveParsers.builtIn, NoSuchMethodError("m").raisedIn(theirs)))
	}

	@Test
	fun parsersThatDoNotFitAreGivenUpAndTheAppsOwnAnswerInTheirPlace() = runBlocking {
		val updater = newUpdater()
		val (_, real) = install(older)
		val own = ActiveParsers.builtIn
		val ownDomain = own.newParser(checkNotNull(own.findSource("MANGADEX")), FakeLoaderContext).domain
		ActiveParsers.activate(MisfitBundle(real))
		val live = LiveMangaParser("MANGADEX", FakeLoaderContext, updater)

		assertEquals("Answered by the app's own parser, not thrown", ownDomain, live.domain)
		assertTrue(ActiveParsers.isBuiltIn)
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().contains(real.version.toString()))
		assertSame(own.findSource("MANGADEX"), live.source)
	}

	@Test
	fun parsersThatCannotEvenMakeAParserAreGivenUpTheSameWay() = runBlocking {
		val updater = newUpdater()
		val (_, real) = install(older)
		ActiveParsers.activate(MisfitBundle(real, failsToMakeParsers = true))
		val live = LiveMangaParser("MANGADEX", FakeLoaderContext, updater)

		assertSame(ActiveParsers.builtIn.findSource("MANGADEX"), live.source)
		assertTrue(ActiveParsers.isBuiltIn)
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().contains(real.version.toString()))
	}

	@Test
	fun aSourceOnlyTheParsersGivenUpHadFailsAsARequestWould() = runBlocking<Unit> {
		val updater = newUpdater()
		val (_, real) = install(older)
		ActiveParsers.activate(MisfitBundle(real))
		val live = LiveMangaParser(MisfitBundle.ONLY_THEIRS.name, FakeLoaderContext, updater)

		// An error of the kind that stops the app, turned into one that fails a request
		assertThrows(IOException::class.java) { live.domain }
		assertTrue(ActiveParsers.isBuiltIn)
		// The parser it holds is the only one there is for this source, and goes on failing the same way
		assertThrows(IOException::class.java) { live.getRequestHeaders() }
	}

	@Test
	fun aManifestMadeToBringItsReaderDownIsOnlyRefused() {
		// Brackets nested by the thousand, in what nobody has signed: read before any signature can be checked
		val nested = ("{\"payload\":" + "[".repeat(60_000)).toByteArray()
		assertThrows(ParsersManifestException::class.java) { ParsersManifest.read(nested, keys) }
		val alsoNested = ("{\"payload\":" + "{\"a\":".repeat(10_000)).toByteArray()
		assertThrows(ParsersManifestException::class.java) { ParsersManifest.read(alsoNested, keys) }
	}

	@Test
	fun helpersOfDownloadedParsersAreTheirOwnAndNotTheApps() {
		val (_, bundle) = install(older)
		val loader = checkNotNull(bundle.javaClass.classLoader)
		val helpers = loader.loadClass("org.koitharu.kotatsu.parsers.util.JsoupUtils")
		assertSame("Taken from the download, the app having a class of the same name", loader, helpers.classLoader)

		// A helper that was once in a package the app does not take from the download: every use of it by a
		// downloaded parser met the app's own older copy
		val element = checkNotNull(
			Jsoup.parseBodyFragment(
				"<div style=\"width:100px;height:80px;background: url(https://example.org/a.png) -10px -20px no-repeat\">x</div>",
			).selectFirst("div"),
		)
		val background = checkNotNull(helpers.getMethod("backgroundOrNull", Element::class.java).invoke(null, element))
		assertEquals("org.koitharu.kotatsu.parsers.util.CSSBackground", background.javaClass.name)
		assertSame(loader, background.javaClass.classLoader)
		assertEquals("https://example.org/a.png", background.javaClass.getMethod("getUrl").invoke(background))
		assertEquals(100, background.javaClass.getMethod("getWidth").invoke(background))
	}

	@Test
	fun twoCrashesInTheCodeOfDownloadedParsersGiveThemUpAndNoOtherCrashDoes() = runBlocking {
		val before = Thread.getDefaultUncaughtExceptionHandler()
		// What the watcher hands every crash on to. Here it is nothing, so that nothing dies of this test
		Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
		try {
			val updater = newUpdater()
			served = older
			InstrumentationRegistry.getInstrumentation().runOnMainSync { updater.start(application) }
			assertTrue(updater.checkNow())
			val bundle = ActiveParsers.bundle
			val watcher = checkNotNull(Thread.getDefaultUncaughtExceptionHandler())
			fun crashIn(className: String, error: Throwable = IllegalStateException("boom")) {
				error.stackTrace = arrayOf(
					StackTraceElement(className, "x", null, -1),
					StackTraceElement("kotlinx.coroutines.DispatchedTask", "run", null, -1),
				)
				watcher.uncaughtException(Thread.currentThread(), error)
			}
			fun givenUp() = state.getStringSet("rejected", emptySet()).orEmpty()

			// The app's own crashes, however many
			repeat(3) { crashIn("org.koitharu.kotatsu.reader.ui.ReaderActivity") }
			// Named like a parser, and nothing the download ever loaded: the app's own copy of it
			repeat(3) { crashIn("org.koitharu.kotatsu.parsers.site.en.NoSuchParser") }
			// A link error of the app's own
			repeat(3) { crashIn("androidx.webkit.WebViewCompat", NoSuchMethodError("m")) }
			assertTrue(givenUp().isEmpty())
			assertEquals(bundle.version, state.getLong("active", 0L))

			val theirs = bundle.javaClass.name
			assertTrue(ParserBundleStore.isCodeOf(bundle, theirs))
			crashIn(theirs)
			assertTrue("One crash can be anything", givenUp().isEmpty())
			crashIn(theirs, RuntimeException("wrapped", IllegalStateException("under it")))
			assertTrue(givenUp().contains(bundle.version.toString()))
			assertEquals("Not loaded the next time the app starts", 0L, state.getLong("active", 0L))
		} finally {
			Thread.setDefaultUncaughtExceptionHandler(before)
		}
	}

	@Test
	fun parsersTheAppDidNotSurviveLoadingAreGivenUpBeforeBeingFetchedAgain() = runBlocking {
		val version = readVersion(older)
		served = older
		// What two starts of loading that were never finished leave behind
		state.edit().putLong("trying", version).putInt("trying_count", 2).commit()
		val updater = newUpdater()
		var before = requests.get()
		assertFalse(updater.checkNow())
		assertEquals("The manifest is asked for, the parsers are not", before + 1, requests.get())
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().contains(version.toString()))
		assertTrue(ActiveParsers.isBuiltIn)

		// Once is not twice: a phone can be switched off at any moment
		state.edit().remove("rejected").putLong("trying", version).putInt("trying_count", 1).commit()
		before = requests.get()
		assertTrue(updater.checkNow())
		assertEquals(version, ActiveParsers.bundle.version)
		assertNull("Loaded, and nothing left to say it was being tried", state.all["trying"])
	}

	@Test
	fun parsersThatCouldNotBeKeptAreNotFetchedAgainAtEveryFailure() = runBlocking {
		// The manifest of real parsers, and under the name it gives, a file of the right size that is not them
		val broken = File(root, "broken").apply { mkdirs() }
		val envelope = File(older, MANIFEST).readBytes()
		File(broken, MANIFEST).writeBytes(envelope)
		val manifest = newStore().readManifest(envelope)
		File(broken, manifest.file).writeBytes(ByteArray(manifest.size.toInt()))
		served = broken
		val updater = newUpdater()
		assertThrows(IOException::class.java) { runBlocking { updater.checkNow() } }
		assertTrue(ActiveParsers.isBuiltIn)
		assertTrue("Nothing wrong with the parsers themselves", state.getStringSet("rejected", emptySet()).orEmpty().isEmpty())

		// A parser failing six minutes later would have them looked for again, and megabytes fetched for nothing
		now += TimeUnit.MINUTES.toMillis(6)
		val before = requests.get()
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), ActiveParsers.generation))
		assertEquals(before, requests.get())
		// The app started again remembers
		val restarted = newUpdater()
		assertFalse(restarted.updateAfterFailure(IOException("HTTP 500"), ActiveParsers.generation))
		assertEquals(before, requests.get())

		// An hour on, with the right file there
		now += TimeUnit.MINUTES.toMillis(61)
		served = older
		assertTrue(restarted.updateAfterFailure(IOException("HTTP 500"), ActiveParsers.generation))
		assertEquals(manifest.version, ActiveParsers.bundle.version)
	}

	@Test
	fun aFailingParserWaitsForSlowParsersOnceAndTheyComeAnyway() = runBlocking {
		val updater = newUpdater()
		served = older
		val generation = ActiveParsers.generation
		val gate = CountDownLatch(1)
		heldSuffix = ".zip"
		held = gate

		var startedAt = SystemClock.elapsedRealtime()
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		val first = SystemClock.elapsedRealtime() - startedAt
		assertTrue("Waited as long as a failing parser may: $first ms", first in 14_000..25_000)

		// The next address of the same source, failing a moment later
		startedAt = SystemClock.elapsedRealtime()
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		val second = SystemClock.elapsedRealtime() - startedAt
		assertTrue("Not made to wait a second time: $second ms", second < 2_000)

		// The download was never given up, and serves whoever fails next
		gate.countDown()
		val deadline = System.currentTimeMillis() + 20_000
		while (ActiveParsers.isBuiltIn && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertEquals(readVersion(older), ActiveParsers.bundle.version)
		assertTrue(updater.updateAfterFailure(IOException("HTTP 500"), generation))
	}

	@Test
	fun aServerThatDoesNotAnswerIsTriedAgainSoonerButNotAtEveryFailure() = runBlocking<Unit> {
		val updater = newUpdater()
		isUnreachable = true
		val generation = ActiveParsers.generation
		var before = requests.get()
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), generation))
		assertEquals(before + 1, requests.get())

		// The failures that follow, and a manga opened: nobody is asked again
		repeat(5) { assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), generation)) }
		updater.onMangaOpened()
		Thread.sleep(400)
		assertEquals(before + 1, requests.get())

		// Six minutes on, opening a manga is reason enough, where it takes an hour after an answer
		now += TimeUnit.MINUTES.toMillis(6)
		before = requests.get()
		updater.onMangaOpened()
		waitFor { requests.get() > before }
		Thread.sleep(400)
		assertEquals(before + 1, requests.get())

		// The server back with nothing new: that is an answer, and the hour applies again
		isUnreachable = false
		served = null
		now += TimeUnit.MINUTES.toMillis(6)
		before = requests.get()
		updater.onMangaOpened()
		waitFor { requests.get() > before }
		Thread.sleep(400)
		now += TimeUnit.MINUTES.toMillis(6)
		val answered = requests.get()
		updater.onMangaOpened()
		Thread.sleep(500)
		assertEquals("Answered six minutes ago: not asked again so soon", answered, requests.get())
		now += TimeUnit.MINUTES.toMillis(61)
		updater.onMangaOpened()
		waitFor { requests.get() > answered }
	}

	@Test
	fun parsersCountingOnWhatThisAppLacksAreGivenUpOnAndroidsOwnError() = runBlocking<Unit> {
		assumeTrue("No ill-fitting parsers were pushed to $illFitting", File(illFitting, MANIFEST).isFile)
		val updater = newUpdater()
		val (_, bundle) = install(illFitting)
		ActiveParsers.activate(bundle)
		val live = LiveMangaParser("HIZOMANGA", FakeLoaderContext, updater)
		assertSame(bundle.findSource("HIZOMANGA"), live.source)

		// Their parser calls a method that no class of this app has. Nothing here makes the error up: it is the one
		// Android raises, with the stack Android gives it
		val outcome = runCatching { live.getFilterOptions() }
		Log.i("KotatsuParsers", "After the call into ill-fitting parsers: ${outcome.exceptionOrNull()}")
		assertTrue("Given up: ${outcome.exceptionOrNull()}", ActiveParsers.isBuiltIn)
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().contains(bundle.version.toString()))
		assertFalse(
			"Answered or failed by the app's own parser, and never with the link error",
			outcome.exceptionOrNull() is LinkageError,
		)
		assertSame(ActiveParsers.builtIn.findSource("HIZOMANGA"), live.source)
	}

	@Test
	fun aParserWhoseOwnStartUpCodeFailsIsNotTakenForParsersThatDoNotFit() {
		assumeTrue("No ill-fitting parsers were pushed to $illFitting", File(illFitting, MANIFEST).isFile)
		val updater = newUpdater()
		val (_, bundle) = install(illFitting)
		ActiveParsers.activate(bundle)
		val failing = LiveMangaParser("ZZ_FAILS_TO_START", FakeLoaderContext, updater)

		// The first use, a later one, and another parser built on the same failing code: Android has a different
		// error for each, and none of them says anything about the other thousand parsers
		val first = assertThrows(LinkageError::class.java) { failing.domain }
		val later = assertThrows(LinkageError::class.java) { failing.domain }
		val builtOnIt = assertThrows(LinkageError::class.java) {
			LiveMangaParser("ZZ_BUILT_ON_IT", FakeLoaderContext, updater).domain
		}
		Log.i("KotatsuParsers", "Start-up code failing, as Android reports it. First: $first (cause ${first.cause})")
		Log.i("KotatsuParsers", "Later: $later (cause ${later.cause})")
		Log.i("KotatsuParsers", "Built on it: $builtOnIt (cause ${builtOnIt.cause})")
		assertSame("Still in use after $first / $later / $builtOnIt", bundle, ActiveParsers.bundle)
		assertTrue(state.getStringSet("rejected", emptySet()).orEmpty().isEmpty())

		// Every other source of the same parsers goes on working, from the same download
		val other = LiveMangaParser("MANGADEX", FakeLoaderContext, updater)
		assertSame(bundle.findSource("MANGADEX"), other.source)
		assertTrue(other.configKeyDomain.presetValues.isNotEmpty())
	}

	@Test
	fun aServerAnsweringAnythingElseLeavesTheParsersAsTheyWere() = runBlocking {
		val garbage = File(root, "garbage").apply { mkdirs() }
		File(garbage, MANIFEST).writeText("<html>Sign in to continue</html>")
		served = garbage
		val updater = newUpdater()
		assertThrows(IOException::class.java) { runBlocking { updater.checkNow() } }
		assertTrue(ActiveParsers.isBuiltIn)

		// A manifest that is right, and the file it names missing
		File(older, MANIFEST).copyTo(File(garbage, MANIFEST), overwrite = true)
		assertThrows(IOException::class.java) { runBlocking { updater.checkNow() } }
		assertTrue(ActiveParsers.isBuiltIn)
		assertNull(state.all["active"])

		// The same, met while the app goes about its business, is kept quiet
		now += TimeUnit.HOURS.toMillis(2)
		assertFalse(updater.updateAfterFailure(IOException("HTTP 500"), ActiveParsers.generation))
		assertTrue(ActiveParsers.isBuiltIn)
	}

	private fun waitFor(condition: () -> Boolean) {
		val deadline = System.currentTimeMillis() + 20_000
		while (!condition() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50)
		}
		assertTrue("Waited 20 s for it", condition())
	}

	private fun readVersion(dir: File): Long = newStore().readManifest(File(dir, MANIFEST).readBytes()).version

	private fun newStore(trusted: List<PublicKey> = keys) = ParserBundleStore(
		root = root,
		trustedKeys = trusted,
		parent = checkNotNull(ParsersUpdater::class.java.classLoader),
	)

	private fun install(dir: File): Pair<ParsersManifest, ParsersBundle> {
		val store = newStore()
		val envelope = File(dir, MANIFEST).readBytes()
		val archive = store.newDownloadFile()
		File(dir, store.readManifest(envelope).file).copyTo(archive, overwrite = true)
		val manifest = store.install(envelope, archive)
		archive.delete()
		return manifest to store.load(manifest.version)
	}

	private fun newUpdater(appVersion: Int = 1) = ParsersUpdater(
		isSupported = true,
		storeProvider = { newStore() },
		state = state,
		baseUrl = "https://parsers.invalid/bundle",
		httpClient = { httpClient },
		isEnabled = { true },
		canDownload = { true },
		scope = scope,
		clock = { now },
		appVersion = appVersion,
	)

	/**
	 * Downloaded parsers that reach for what the app does not have, as soon as anything is asked of one of them: the
	 * real ones in everything else. The error is what Android raises for a method that is not there, at the place
	 * the app calls into the parser.
	 */
	private class MisfitBundle(
		private val real: ParsersBundle,
		private val failsToMakeParsers: Boolean = false,
	) : ParsersBundle by real {

		override fun findSource(name: String): ParserMangaSource? {
			return if (name == ONLY_THEIRS.name) ONLY_THEIRS else real.findSource(name)
		}

		override fun newParser(source: ParserMangaSource, context: MangaLoaderContext): MangaParser {
			if (failsToMakeParsers) {
				throw misfit()
			}
			val known = if (source === ONLY_THEIRS) checkNotNull(real.findSource("MANGADEX")) else source
			val parser = real.newParser(known, context)
			return object : MangaParser by parser {

				override val domain: String
					get() = throw misfit()

				override fun getRequestHeaders(): Headers = throw misfit()
			}
		}

		private fun misfit() = AbstractMethodError("abstract method not implemented").apply {
			stackTrace = arrayOf(StackTraceElement(LiveMangaParser::class.java.name, "call", null, -1))
		}

		companion object {

			val ONLY_THEIRS: ParserMangaSource = object : ParserMangaSource(
				name = "ONLY_IN_THE_DOWNLOAD",
				title = "Only in the download",
				locale = "en",
				contentType = ContentType.MANGA,
				isBroken = false,
			) {}
		}
	}

	private object FakeLoaderContext : MangaLoaderContext() {

		override val httpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(404).message("No network in these tests").body("".toResponseBody()).build()
		}.build()

		override val cookieJar: CookieJar = CookieJar.NO_COOKIES

		override val parsers: ParsersBundle
			get() = ActiveParsers.bundle

		@Deprecated("Provide a base url")
		override suspend fun evaluateJs(script: String): String? = null

		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null

		override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
			override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
		}

		override fun getDefaultUserAgent(): String = "Kotatsu"

		override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response = response

		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}

	private companion object {

		const val MANIFEST = "parsers-v1.json"
	}
}
