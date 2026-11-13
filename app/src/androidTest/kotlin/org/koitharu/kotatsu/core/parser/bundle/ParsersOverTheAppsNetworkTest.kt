package org.koitharu.kotatsu.core.parser.bundle

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Parsers fetched the way the app fetches them for real: with its own HTTP client and everything that client does to
 * a request, from a server that answers like the one they are published on, with a redirect to another host.
 *
 * Needs a server on port 8765 of this device (`adb reverse tcp:8765 tcp:8765` to one on the computer) that serves
 * the manifest and the file of one set of parsers, and answers `/redirect/<name>` with a redirect to
 * `http://localhost:8765/<name>`. And the public half of the key they are signed with, pushed as for
 * [ParsersBundleDeviceTest]. Skipped without either.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ParsersOverTheAppsNetworkTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient

	private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
	// On shared storage where the shell may still write there, inside the app (through run-as) since Android 11
	private val pushed = File(context.getExternalFilesDir(null), "parsers-bundle").takeIf { File(it, "key.pub").isFile }
		?: File(context.filesDir, "parsers-bundle")
	private val root = File(context.codeCacheDir, "parsers-network-test-${System.nanoTime()}")
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

	@Before
	fun setUp() {
		assumeTrue("Only where parsers are downloaded at all", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		assumeTrue("No key was pushed to $pushed", File(pushed, "key.pub").isFile)
		assumeTrue("Nothing is serving parsers on port 8765 of this device", isServed())
		hiltRule.inject()
		ActiveParsers.activate(ActiveParsers.builtIn)
	}

	@After
	fun tearDown() {
		ActiveParsers.activate(ActiveParsers.builtIn)
		scope.cancel()
		root.deleteRecursively()
	}

	@Test
	fun parsersComeThroughTheAppsOwnClientAndAcrossARedirectToAnotherHost() = runBlocking {
		val keys = ParsersManifest.readKeys(File(pushed, "key.pub").readText())
		val state = context.getSharedPreferences("parsers_bundle_network_test", Context.MODE_PRIVATE)
		state.edit().clear().commit()
		val updater = ParsersUpdater(
			isSupported = true,
			storeProvider = { ParserBundleStore(root, keys, checkNotNull(ParsersUpdater::class.java.classLoader)) },
			state = state,
			baseUrl = "$SERVER/redirect",
			httpClient = { httpClient },
			isEnabled = { true },
			canDownload = { true },
			scope = scope,
		)
		assertTrue("Found, fetched, checked and put in use", updater.checkNow())
		assertFalse(ActiveParsers.isBuiltIn)
		assertEquals(ActiveParsers.bundle.version, state.getLong("active", 0L))
		assertTrue(ActiveParsers.bundle.sources.size > 1000)
		// Asked again: nothing newer, and nothing fetched twice
		assertFalse(updater.checkNow())
	}

	private fun isServed(): Boolean = runCatching {
		val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
		client.newCall(Request.Builder().url("$SERVER/parsers-v1.json").build()).execute().use { it.isSuccessful }
	}.getOrDefault(false)

	private companion object {

		const val SERVER = "http://127.0.0.1:8765"
	}
}
