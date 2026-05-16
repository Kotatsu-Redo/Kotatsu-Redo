package org.koitharu.kotatsu.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
open class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {

	private var pendingResult = RESULT_CANCELED
	private val isHidden: Boolean by lazy { intent?.getBooleanExtra(EXTRA_HIDDEN, false) == true }
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private var resultNotified = false
	private var hiddenTimeoutJob: Job? = null
	private var clearancePollJob: Job? = null
	private var initialClearance: String? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		if (isHidden) {
			// Hide every UI element but keep the WebView attached to a real window so Cloudflare/Turnstile
			// sees a real Surface (otherwise it would reject the headless attempt the same way a 1×1 detached
			// WebView gets rejected).
			supportActionBar?.hide()
			viewBinding.root.alpha = 0f
			// Let touches and input focus reach the activity below so the user can keep using the app while
			// the silent solve is running.
			window.addFlags(
				android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
					android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			)
			// Safety net: if Cloudflare can't pass the challenge silently, bail so the caller can retry
			// in visible mode instead of hanging here forever.
			hiddenTimeoutJob = lifecycleScope.launch {
				delay(HIDDEN_TIMEOUT_MS)
				viewBinding.webView.stopLoading()
				finishAfterTransition()
			}
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}

		// Check if source needs header interception
		val needsInterception = shouldUseInterception(source, repository)
		Log.d(TAG, "Source: ${source.name}, needsInterception: $needsInterception")

		cfClient = if (needsInterception) {
			Log.d(TAG, "Using CloudFlareInterceptClient with header filtering")
			CloudFlareInterceptClient(cookieJar, this, url)
		} else {
			Log.d(TAG, "Using regular CloudFlareClient (no interception)")
			CloudFlareClient(cookieJar, this, url)
		}

		viewBinding.webView.webViewClient = cfClient
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				onTitleChanged(getString(R.string.loading_), url)
				viewBinding.webView.loadUrl(url)
			}
		}
		// CloudFlareClient only fires onCheckPassed when onPageStarted sees the clearance cookie change.
		// Cloudflare's PAT / managed-challenge flow can hand out clearance without an explicit redirect,
		// so poll the cookie directly. NOTE: we deliberately do NOT poll the page state via
		// evaluateJavascript — repeatedly injecting scripts is an anti-bot signal that Turnstile picks up
		// on, causing the challenge to loop on the user. JS state is instead checked once per navigation
		// inside onPageLoaded().
		initialClearance = CloudFlareHelper.getClearanceCookie(cookieJar, url)
		clearancePollJob = lifecycleScope.launch {
			while (true) {
				delay(CLEARANCE_POLL_INTERVAL_MS)
				val current = CloudFlareHelper.getClearanceCookie(cookieJar, url)
				if (current != null && current != initialClearance) {
					onCheckPassed()
					return@launch
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		setResult(pendingResult)
		// In auto-resolve mode the originating Fragment / Activity may already be dead, so its
		// ActivityResultLauncher won't deliver the result. Notify the singleton coordinator instead so
		// the result reaches every screen that's still awaiting it.
		if (isAutoResolve && !resultNotified) {
			resultNotified = true
			val sourceName = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (sourceName != null) {
				captchaAutoResolveCoordinator.notifyResolveResult(
					MangaSource(sourceName),
					pendingResult == RESULT_OK,
				)
			}
		}
		super.finish()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	override fun onPageLoaded() {
		viewBinding.progressBar.isInvisible = true
	}

	override fun onLoopDetected() {
		restartCheck()
	}

	override fun onCheckPassed() {
		pendingResult = RESULT_OK
		lifecycleScope.launch {
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(MangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				viewBinding.webView.loadUrl(targetUrl.toString())
			}
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
	}

	private fun shouldUseInterception(source: MangaSource, repository: ParserMangaRepository?): Boolean {
		Log.d(TAG, "shouldUseInterception called for source: ${source.name}")
		Log.d(TAG, "Repository type: ${repository?.javaClass?.simpleName}")

		if (repository !is ParserMangaRepository) {
			Log.d(TAG, "Repository is not ParserMangaRepository, returning false")
			return false
		}

		// Check if parser has InterceptCloudflare ConfigKey
		val configKeys = repository.getConfigKeys()
		Log.d(TAG, "Config keys count: ${configKeys.size}")
		Log.d(TAG, "Config keys: ${configKeys.map { it.javaClass.simpleName }}")

		val interceptKey = configKeys.filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()
		Log.d(TAG, "InterceptCloudflare key found: ${interceptKey != null}")
		if (interceptKey != null) {
			Log.d(TAG, "InterceptCloudflare defaultValue: ${interceptKey.defaultValue}")
		}

		val result = interceptKey?.defaultValue == true
		Log.d(TAG, "Returning: $result")
		return result
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	/**
	 * Same as [Contract] but launches the activity in hidden mode (translucent window, no UI),
	 * used to auto-resolve CloudFlare without showing the captcha screen.
	 */
	class HiddenContract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input, hidden = true)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		const val EXTRA_HIDDEN = "hidden"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		private const val HIDDEN_TIMEOUT_MS = 15_000L
		private const val CLEARANCE_POLL_INTERVAL_MS = 700L
	}
}
