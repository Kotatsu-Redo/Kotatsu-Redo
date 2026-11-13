package org.koitharu.kotatsu.core.parser.bundle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.edit
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.acra.ACRA
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.exceptions.ProxyConfigException
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.ParsersApi
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.util.await
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the parsers newer than the app itself: looks for a published set of them, takes it in, and puts it in use
 * while the app runs, without anything being asked of or shown to the user.
 *
 * It looks when the app comes to the front, when a manga is opened, and when a parser fails. The last is the one that
 * matters: a source whose site has changed is repaired for whoever runs into it, as soon as a fix is published.
 *
 * Whatever goes wrong here leaves the app with the parsers it had. Parsers that fail to load, or that the app keeps
 * crashing in, are given up for good and the ones the app was built with take over again.
 *
 * @param isSupported `false` where no parsers are taken: before Android 8, or in a build without a key to check
 * them with
 * @param storeProvider called once, and not on the main thread: reading a key is not for there
 * @param baseUrl where the manifest and the file it names are published, without a slash at the end
 */
@Singleton
class ParsersUpdater internal constructor(
	val isSupported: Boolean,
	storeProvider: () -> ParserBundleStore?,
	private val state: SharedPreferences,
	private val baseUrl: String,
	private val httpClient: () -> OkHttpClient,
	private val isEnabled: () -> Boolean,
	private val canDownload: () -> Boolean,
	private val scope: CoroutineScope,
	private val clock: () -> Long = System::currentTimeMillis,
	private val appVersion: Int = BuildConfig.VERSION_CODE,
) {

	@Inject
	constructor(
		@ApplicationContext context: Context,
		settings: AppSettings,
		@BaseHttpClient httpClient: Lazy<OkHttpClient>,
		networkState: Lazy<NetworkState>,
	) : this(
		isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
			&& BuildConfig.PARSERS_BUNDLE_URL.isNotEmpty()
			&& BuildConfig.PARSERS_BUNDLE_KEY.isNotBlank(),
		storeProvider = { newStore(context) },
		state = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
		baseUrl = BuildConfig.PARSERS_BUNDLE_URL.trimEnd('/'),
		httpClient = { httpClient.get() },
		isEnabled = { settings.isParsersAutoUpdateEnabled },
		// Not over a connection the user has asked to be spared: nothing here is worth more than that
		canDownload = { !networkState.get().isOfflineOrRestricted() },
		scope = processLifecycleScope + Dispatchers.Default,
	)

	private val store: ParserBundleStore? by lazy {
		if (isSupported) storeProvider() else null
	}

	private val mutex = Mutex()

	@Volatile
	private var lastCheckAt = -1L

	@Volatile
	private var startup: ActiveParsers.Startup? = null

	/** When a failing parser last waited for newer parsers as long as it may, and got none. */
	@Volatile
	private var lastUnansweredWaitAt = 0L

	/**
	 * Whether the last time newer parsers were looked for, the server did not answer at all. That is not the same as
	 * it having nothing new, and is not waited out as long, see [gateFor]. Kept across restarts.
	 */
	private var isUnanswered: Boolean
		get() = state.getBoolean(KEY_UNANSWERED, false)
		set(value) {
			if (value != isUnanswered) {
				state.edit { putBoolean(KEY_UNANSWERED, value) }
			}
		}

	/** When parsers that had been downloaded whole last could not be unpacked or read back. Kept across restarts. */
	private var lastInstallFailureAt: Long
		get() = state.getLong(KEY_INSTALL_FAILED, 0L)
		set(value) = state.edit { putLong(KEY_INSTALL_FAILED, value) }

	/** Whether newer parsers are looked for at all: on a phone and in a build that can take them, and not turned off. */
	val isOn: Boolean
		get() = isSupported && isEnabled()

	/** When newer parsers were last looked for, in milliseconds since the epoch, or 0 if never. */
	val lastCheckTime: Long
		get() {
			if (lastCheckAt < 0) {
				lastCheckAt = state.getLong(KEY_LAST_CHECK, 0L)
			}
			return lastCheckAt
		}

	/**
	 * To be called once, as early in the life of the process as can be: loads the parsers kept from the last run and
	 * starts watching for the app to come to the front.
	 *
	 * Returns at once. Until the kept parsers are in, whoever asks which sources there are is made to wait for them,
	 * see [ActiveParsers.beginStartup].
	 */
	@MainThread
	fun start(application: Application) {
		if (!isSupported || !isEnabled()) {
			return
		}
		val startup = ActiveParsers.beginStartup()
		this.startup = startup
		Thread({ loadKept(startup) }, "parsers-loader").start()
		watchForCrashes()
		// The app is at the front when the first of its screens is. Counted here because ProcessLifecycleOwner never
		// says so in this app, see CommunityReplyNotifier
		application.registerActivityLifecycleCallbacks(
			object : DefaultActivityLifecycleCallbacks {

				private var started = 0

				override fun onActivityStarted(activity: Activity) {
					if (started++ == 0) {
						requestCheck()
					}
				}

				override fun onActivityStopped(activity: Activity) {
					started = (started - 1).coerceAtLeast(0)
				}
			},
		)
	}

	/** A manga was opened, to look at or to read. */
	fun onMangaOpened() = requestCheck()

	/**
	 * A parser has failed with [error]. Finds out whether newer parsers than the ones that failed can be had, and has
	 * them in use by the time it returns if so, for the caller to try once more.
	 *
	 * Not waited for long: the caller has someone looking at a progress bar. A download that takes longer goes on by
	 * itself and serves the next attempt.
	 *
	 * @param failedGeneration the [ActiveParsers.generation] of the parser that failed
	 * @return `true` if other parsers are in use now than the ones that failed
	 */
	suspend fun updateAfterFailure(error: Throwable, failedGeneration: Int): Boolean {
		if (ActiveParsers.generation != failedGeneration) {
			return true
		}
		if (!isSupported || !isEnabled() || !error.mayBeFixedByNewerParsers()) {
			return false
		}
		if (isWithin(lastUnansweredWaitAt, MIN_AGE_AFTER_FAILURE_MS)) {
			// Waited for in full a moment ago, by this call or another, and not there yet. A source with several
			// addresses fails once for each of them: it is not to wait as many times
			return false
		}
		val check = scope.async { check(MIN_AGE_AFTER_FAILURE_MS) }
		if (withTimeoutOrNull(FAILURE_WAIT_MS) { check.await() } == null) {
			lastUnansweredWaitAt = clock()
		}
		return ActiveParsers.generation != failedGeneration
	}

	/**
	 * Looks for newer parsers now, however recently that was done.
	 *
	 * @return `true` if newer parsers were put in use
	 * @throws IOException if they could not be looked for or taken in
	 */
	suspend fun checkNow(): Boolean = check(minAge = 0L, isSilent = false)

	/**
	 * Gives up the downloaded parsers [bundle], for having failed with [cause] in a way no parser should. If they
	 * are the ones in use, the ones the app was built with are back in use when this returns. The same version is
	 * not taken again by this build of the app.
	 */
	fun giveUp(bundle: ParsersBundle, cause: Throwable) {
		if (bundle === ActiveParsers.builtIn) {
			return
		}
		reject(bundle.version, cause)
		ActiveParsers.deactivate(bundle)
		describeToCrashReports()
	}

	private fun requestCheck() {
		if (!isSupported || !isEnabled() || isWithin(lastCheckTime, gateFor(MIN_AGE_MS))) {
			return
		}
		scope.launch {
			check(MIN_AGE_MS)
		}
	}

	/**
	 * @param minAge nothing is done if newer parsers were looked for less than this long ago
	 * @param isSilent to keep what goes wrong to itself and say `false`
	 */
	private suspend fun check(minAge: Long, isSilent: Boolean = true): Boolean {
		if (!isSupported) {
			return false
		}
		return try {
			withContext(Dispatchers.IO) {
				// The parsers kept from the last run first: the two would be putting parsers in use over each other
				startup?.await(STARTUP_WAIT_MS)
				mutex.withLock {
					val store = store ?: return@withLock false
					if (minAge > 0 && (isWithin(lastCheckTime, gateFor(minAge)) || !canDownload())) {
						return@withLock false
					}
					if (minAge > 0 && isWithin(lastInstallFailureAt, MIN_AGE_MS)) {
						// Megabytes were downloaded and could not be kept: not again at every failing parser
						return@withLock false
					}
					// Before anything is asked for: a server that is failing is not to be asked again at every error
					lastCheckAt = clock()
					state.edit { putLong(KEY_LAST_CHECK, lastCheckAt) }
					update(store)
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			e.printStackTraceDebug()
			Log.w(TAG, "Newer parsers could not be looked for or taken in: $e")
			if (isSilent) false else throw e
		}
	}

	private suspend fun update(store: ParserBundleStore): Boolean {
		val envelope = try {
			fetchManifest()
		} catch (e: IOException) {
			isUnanswered = true
			throw e
		}
		isUnanswered = false
		if (envelope == null) {
			return false
		}
		// Read here only to decide. It is read again, from these same bytes, by whatever goes on to trust it
		val manifest = store.readManifest(envelope)
		if (!isWanted(manifest)) {
			return false
		}
		if (state.getLong(KEY_TRYING, 0L) == manifest.version && state.getInt(KEY_TRYING_COUNT, 0) >= MAX_FAILURES) {
			reject(
				manifest.version,
				IllegalStateException("The app did not survive loading the parsers of ${manifest.revision}"),
			)
			return false
		}
		Log.i(TAG, "Newer parsers are published: ${manifest.revision}, ${manifest.size} bytes")
		val download = store.newDownloadFile()
		try {
			fetchArchive(manifest, download)
			try {
				store.install(envelope, download)
			} catch (e: IOException) {
				lastInstallFailureAt = clock()
				throw e
			}
		} finally {
			download.delete()
		}
		val bundle = try {
			tryingOut(manifest.version) { store.load(manifest.version) }
		} catch (e: ParsersFilesException) {
			// Unpacked a moment ago and already not as they were: the place they are kept in is what is wrong
			lastInstallFailureAt = clock()
			store.delete(manifest.version)
			throw e
		} catch (e: Throwable) {
			// Signed, whole, and still not loading: no use in downloading these again
			reject(manifest.version, e)
			throw IOException("The parsers of ${manifest.revision} cannot be loaded", e)
		}
		state.edit(commit = true) {
			putLong(KEY_ACTIVE, manifest.version)
			remove(KEY_LOADING)
			remove(KEY_CRASHES)
			remove(KEY_INSTALL_FAILED)
		}
		ActiveParsers.activate(bundle)
		Log.i(TAG, "Parsers ${bundle.revision} are in use, with ${bundle.sources.size} sources")
		describeToCrashReports()
		store.deleteAllExcept(manifest.version)
		return true
	}

	private fun isWanted(manifest: ParsersManifest): Boolean {
		return manifest.api == ParsersApi.VERSION
			&& manifest.minSdk <= Build.VERSION.SDK_INT
			// Newer than the ones in use, be they downloaded or the app's own: there is no going back to older ones
			&& manifest.version > ActiveParsers.bundle.version
			&& !isRejected(manifest.version)
	}

	/** @return `null` if there is none: nothing was published yet for this version of the contract */
	private suspend fun fetchManifest(): ByteArray? {
		val request = Request.Builder()
			.url("$baseUrl/parsers-v${ParsersApi.VERSION}.json")
			.cacheControl(CacheControl.FORCE_NETWORK)
			.build()
		val client = httpClient().newBuilder().callTimeout(MANIFEST_TIMEOUT_S, TimeUnit.SECONDS).build()
		return client.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				return@use null
			}
			val source = response.body.source()
			// One byte more than a manifest may be is enough to know this is not one
			source.request(ParsersManifest.MAX_ENVELOPE_SIZE + 1L)
			if (source.buffer.size > ParsersManifest.MAX_ENVELOPE_SIZE) {
				throw IOException("Too large to be a manifest")
			}
			source.buffer.readByteArray()
		}
	}

	private suspend fun fetchArchive(manifest: ParsersManifest, target: File) {
		val request = Request.Builder()
			.url("$baseUrl/${manifest.file}")
			.cacheControl(CacheControl.Builder().noStore().build())
			.build()
		val client = httpClient().newBuilder().callTimeout(ARCHIVE_TIMEOUT_S, TimeUnit.SECONDS).build()
		client.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw IOException("${manifest.file}: HTTP ${response.code}")
			}
			val source = response.body.source()
			target.sink().buffer().use { sink ->
				var total = 0L
				while (true) {
					val read = source.read(sink.buffer, 64 * 1024L)
					if (read < 0) break
					total += read
					if (total > manifest.size) {
						throw IOException("${manifest.file} is larger than the manifest was signed for")
					}
					sink.emitCompleteSegments()
				}
			}
		}
	}

	private fun loadKept(startup: ActiveParsers.Startup) {
		var isLeftWithout = false
		startup.loader = Thread.currentThread()
		try {
			forgetWhatAnotherBuildGaveUp()
			val version = state.getLong(KEY_ACTIVE, 0L)
			if (version != 0L) {
				isLeftWithout = !loadKept(version)
			}
		} catch (e: Throwable) {
			e.printStackTraceDebug()
		} finally {
			startup.finish()
			describeToCrashReports()
		}
		runCatching {
			store?.deleteAllExcept(state.getLong(KEY_ACTIVE, 0L).takeIf { it != 0L })
		}
		if (isLeftWithout) {
			// Whatever sources only downloaded parsers have are unknown to the app until it has some again: not to be
			// left to the hour that may not have passed since they were last looked for
			lastCheckAt = 0L
			state.edit { remove(KEY_LAST_CHECK) }
			scope.launch {
				check(MIN_AGE_MS)
			}
		}
	}

	/**
	 * A load that was begun and neither finished nor failed took the process down with it, natively. Two of those and
	 * the parsers are given up: nothing else would ever get the app past its first moments again.
	 *
	 * @return `false` if the parsers kept from the last run are not in use, for whatever reason
	 */
	private fun loadKept(version: Long): Boolean {
		val store = store ?: return false
		if (version <= ActiveParsers.builtIn.version || isRejected(version) || !store.has(version)) {
			// The app was updated past them, or its cache was cleared
			state.edit { remove(KEY_ACTIVE) }
			return false
		}
		val unfinished = state.getInt(KEY_LOADING, 0)
		if (unfinished >= MAX_FAILURES) {
			reject(version, IllegalStateException("The app did not survive loading its parsers $unfinished times"))
			return false
		}
		state.edit(commit = true) { putInt(KEY_LOADING, unfinished + 1) }
		return try {
			val startedAt = SystemClock.elapsedRealtime()
			val bundle = store.load(version)
			ActiveParsers.activate(bundle)
			state.edit { remove(KEY_LOADING) }
			val took = SystemClock.elapsedRealtime() - startedAt
			Log.i(TAG, "Parsers ${bundle.revision}, downloaded earlier, are in use: loaded in $took ms")
			true
		} catch (e: ParsersFilesException) {
			// Damaged or half gone where they were kept. Nothing is wrong with the parsers: they are downloaded again
			Log.w(TAG, "The parsers of version $version are to be downloaded again: $e")
			state.edit(commit = true) {
				remove(KEY_ACTIVE)
				remove(KEY_LOADING)
			}
			false
		} catch (e: Throwable) {
			reject(version, e)
			false
		}
	}

	/**
	 * Notes that the parsers of [version], just downloaded, are being loaded for the first time, and takes the note
	 * back once that is over, well or not. A note still there later is a load the process did not come back from,
	 * see [update].
	 */
	private inline fun <T> tryingOut(version: Long, block: () -> T): T {
		val count = if (state.getLong(KEY_TRYING, 0L) == version) state.getInt(KEY_TRYING_COUNT, 0) else 0
		state.edit(commit = true) {
			putLong(KEY_TRYING, version)
			putInt(KEY_TRYING_COUNT, count + 1)
		}
		try {
			return block()
		} finally {
			state.edit(commit = true) {
				remove(KEY_TRYING)
				remove(KEY_TRYING_COUNT)
			}
		}
	}

	/**
	 * Parsers are given up for not fitting the app or for crashing in it, and either can be the app's doing: a new
	 * build of it gets to try them again.
	 */
	private fun forgetWhatAnotherBuildGaveUp() {
		if (state.getInt(KEY_APP, 0) == appVersion) {
			return
		}
		state.edit(commit = true) {
			putInt(KEY_APP, appVersion)
			remove(KEY_REJECTED)
			remove(KEY_CRASHES)
			remove(KEY_LOADING)
			remove(KEY_TRYING)
			remove(KEY_TRYING_COUNT)
			remove(KEY_INSTALL_FAILED)
		}
	}

	/**
	 * Counts the crashes that go through the code of downloaded parsers, and gives those parsers up at the second.
	 *
	 * A parser that throws is nothing new and is shown as an error. This is for what a whole set of them could do
	 * that the app cannot catch, to everyone at once and without anyone having updated anything: it must not take a
	 * new release of the app to recover from.
	 */
	private fun watchForCrashes() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			runCatching {
				val bundle = ActiveParsers.bundle
				if (bundle !== ActiveParsers.builtIn && error.isFromParsers(bundle)) {
					val crashes = state.getInt(KEY_CRASHES, 0) + 1
					if (crashes >= MAX_FAILURES) {
						reject(bundle.version, error)
					} else {
						state.edit(commit = true) { putInt(KEY_CRASHES, crashes) }
					}
				}
			}
			previous?.uncaughtException(thread, error)
		}
	}

	private fun reject(version: Long, cause: Throwable) {
		cause.printStackTraceDebug()
		if (version == 0L) {
			return
		}
		Log.w(TAG, "The parsers of version $version are given up: $cause")
		val rejected = state.getStringSet(KEY_REJECTED, emptySet()).orEmpty()
			.mapNotNull { it.toLongOrNull() }
			.plus(version)
			.sortedDescending()
			.take(MAX_REJECTED)
		state.edit(commit = true) {
			putInt(KEY_APP, appVersion)
			putStringSet(KEY_REJECTED, rejected.mapTo(HashSet()) { it.toString() })
			if (state.getLong(KEY_ACTIVE, 0L) == version) {
				remove(KEY_ACTIVE)
			}
			remove(KEY_LOADING)
			remove(KEY_CRASHES)
		}
	}

	private fun isRejected(version: Long): Boolean {
		return state.getStringSet(KEY_REJECTED, emptySet()).orEmpty().contains(version.toString())
	}

	/** A crash in a parser is read very differently for knowing which parsers it was in. */
	private fun describeToCrashReports() {
		runCatching {
			val bundle = ActiveParsers.bundle
			val origin = if (bundle === ActiveParsers.builtIn) "built-in" else "downloaded"
			ACRA.errorReporter.putCustomData("parsers", "${bundle.revision} ($origin)")
		}
	}

	private fun Throwable.mayBeFixedByNewerParsers(): Boolean = when (this) {
		// These ask something of the user, or of the site to calm down, and no parser will answer in their place
		is CloudFlareException,
		is AuthRequiredException,
		is InteractiveActionRequiredException,
		is ProxyConfigException,
		is TooManyRequestExceptions -> false

		else -> true
	}

	/** Whether code that [bundle] brought with it was running when this was thrown, or what caused it. */
	private fun Throwable.isFromParsers(bundle: ParsersBundle): Boolean {
		var error: Throwable? = this
		var depth = 0
		while (error != null && depth++ < 8) {
			val isTheirs = error.stackTrace.any { frame ->
				DOWNLOADED_PACKAGES.any { frame.className.startsWith(it) } &&
					ParserBundleStore.isCodeOf(bundle, frame.className)
			}
			if (isTheirs || (error is LinkageError && LiveMangaParser.isMisfit(bundle, error))) {
				return true
			}
			error = error.cause
		}
		return false
	}

	/**
	 * How long newer parsers are not looked for again, where it would be [minAge] after a server that answered. One
	 * that did not answer is tried again sooner, the phone having perhaps been without a connection for a moment,
	 * but never at every failing parser: each would be kept waiting for an answer that does not come.
	 */
	private fun gateFor(minAge: Long): Long = if (isUnanswered) minOf(minAge, MIN_AGE_AFTER_FAILURE_MS) else minAge

	/** @return `false` too if the clock was set back to before [since]: nothing is put off for that */
	private fun isWithin(since: Long, span: Long): Boolean = clock() - since in 0 until span

	companion object {

		/** What is said under this tag is said in release builds too: it is all there is to go by on a phone. */
		private const val TAG = "KotatsuParsers"
		private const val PREFS_NAME = "parsers_bundle"
		private const val KEY_ACTIVE = "active"
		private const val KEY_LOADING = "loading"
		private const val KEY_CRASHES = "crashes"
		private const val KEY_REJECTED = "rejected"
		private const val KEY_LAST_CHECK = "last_check"
		private const val KEY_TRYING = "trying"
		private const val KEY_TRYING_COUNT = "trying_count"
		private const val KEY_APP = "app"
		private const val KEY_INSTALL_FAILED = "install_failed"
		private const val KEY_UNANSWERED = "unanswered"

		/** Parsers are published by hand, a few times a week at most: looking more often finds nothing. */
		private val MIN_AGE_MS = TimeUnit.HOURS.toMillis(1)

		/** A failing parser is the one sign that there may be something to find. */
		private val MIN_AGE_AFTER_FAILURE_MS = TimeUnit.MINUTES.toMillis(5)

		private val FAILURE_WAIT_MS = TimeUnit.SECONDS.toMillis(15)
		private val STARTUP_WAIT_MS = TimeUnit.SECONDS.toMillis(30)
		private const val MANIFEST_TIMEOUT_S = 10L
		private const val ARCHIVE_TIMEOUT_S = 120L
		private const val MAX_FAILURES = 2
		private const val MAX_REJECTED = 20

		/**
		 * Where the code of the parsers is that the app never runs a copy of its own of, once it has downloaded some.
		 * Their helpers are left out: the app calls those of its own copy all the time, under the same names.
		 */
		private val DOWNLOADED_PACKAGES = arrayOf(
			"org.koitharu.kotatsu.parsers.site.",
			"org.koitharu.kotatsu.parsers.core.",
		)

		private fun newStore(context: Context): ParserBundleStore? {
			val keys = runCatching { ParsersManifest.readKeys(BuildConfig.PARSERS_BUNDLE_KEY) }
				.onFailure { it.printStackTraceDebug() }
				.getOrDefault(emptyList())
			if (keys.isEmpty()) {
				return null
			}
			// Where Android means generated code to be kept: never backed up, and emptied when the app is updated,
			// which is when the parsers inside the app are most likely the newer ones again
			return ParserBundleStore(
				root = File(context.codeCacheDir, "parsers"),
				trustedKeys = keys,
				parent = checkNotNull(ParsersUpdater::class.java.classLoader),
			)
		}
	}
}
