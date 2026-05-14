package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareCallback
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareClient
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareInterceptClient
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.prepareDetachedParserViewport
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.ranges.contains

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
	private val foregroundActivityHolder: ForegroundActivityHolder,
	private val mangaRepositoryFactoryProvider: Provider<MangaRepository.Factory>,
) {

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()

	// host -> epoch ms until which we skip new resolve attempts for that host (set after a recent failure).
	// Avoids the "burst of failed images each triggers its own 12 s WebView attempt" cascade.
	private val recentFailureUntil = ConcurrentHashMap<String, Long>()

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			// Probably WebView is not available
			null
		}
	}

    suspend fun evaluateJs(
        baseUrl: String?,
        script: String,
        timeoutMs: Long = 15000L,
        preserveCookies: Boolean = false
    ): String? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val webView = obtainWebView()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            try {
                if (baseUrl.isNullOrEmpty()) {
                    return@withContext suspendCoroutine { cont ->
                        webView.evaluateJavascript(script) { cont.resume(it.takeUnless { r -> r == "null" }) }
                    }
                }

                val baseUri = android.net.Uri.parse(baseUrl)
                val originalHost = baseUri.host

                suspendCoroutine { continuation ->
                    var hasResumed = false

                    val resumeOnce: (String?) -> Unit = { result ->
                        if (!hasResumed) {
                            hasResumed = true
                            handler.removeCallbacksAndMessages(null)
                            // Immediately stop further loading/polling
                            webView.stopLoading()
                            continuation.resume(result)
                        }
                    }

                    val contentPoller = object : Runnable {
                        val startTime = System.currentTimeMillis()
                        override fun run() {
                            if (hasResumed) return
                            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                                return
                            }
                            webView.evaluateJavascript(script) { result ->
                                if (hasResumed) return@evaluateJavascript
                                val content = result?.takeUnless { it == "null" }
                                if (!content.isNullOrBlank()) {
                                    println("DEBUG: Content found via polling. Returning immediately.")
                                    resumeOnce(content)
                                } else {
                                    handler.postDelayed(this, 1000)
                                }
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url ?: return false
                            val requestHost = url.host
                            if (originalHost != null && requestHost != null && requestHost.contains(originalHost)) {
                                return false
                            }
                            println("DEBUG: Blocked redirect to external domain: $url")
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (hasResumed || url == "about:blank") return
                            println("DEBUG: onPageFinished. Checking content...")
                            view?.evaluateJavascript(script) { result ->
                                if (hasResumed) return@evaluateJavascript
                                val content = result?.takeUnless { it == "null" }
                                if (!content.isNullOrBlank()) {
                                    println("DEBUG: Content found on pageFinished. Returning immediately.")
                                    resumeOnce(content)
                                }
                            }
                        }
                    }

                    val headers = mapOf("Accept-Language" to "en-EN,en;q=0.9")
                    if (preserveCookies) {
                        webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
                    } else {
                        webView.loadUrl(baseUrl, headers)
                    }

                    handler.postDelayed(contentPoller, 1000)

                    handler.postDelayed({
                        if (!hasResumed) {
                            println("ERROR: Overall operation timed out.")
                            resumeOnce(null)
                        }
                    }, timeoutMs)
                }
            } finally {
                // If already resumed, stopLoading() was called; this is a safety call.
                webView.stopLoading()
            }
        }
    }

    suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean {
		val cooldownHost = runCatching { URI(exception.url).host?.lowercase() }.getOrNull()
		if (cooldownHost != null) {
			val now = System.currentTimeMillis()
			val skipUntil = recentFailureUntil[cooldownHost]
			if (skipUntil != null) {
				if (skipUntil > now) {
					Log.d(TAG, "Skipping captcha auto-resolve for $cooldownHost (cooled down for ${skipUntil - now}ms)")
					return false
				}
				recentFailureUntil.remove(cooldownHost)
			}
		}
		val resolved = mutex.withLock {
			// Re-check cooldown after acquiring the mutex: a previous waiter may have just failed for the same host.
			if (cooldownHost != null) {
				val skipUntil = recentFailureUntil[cooldownHost]
				if (skipUntil != null && skipUntil > System.currentTimeMillis()) {
					return@withLock false
				}
			}
			runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
			withContext(Dispatchers.Main.immediate) {
				// Build a fresh WebView against the foreground Activity (NOT the cached app-context one), so it
				// matches CloudFlareActivity's WebView — Cloudflare/Turnstile fingerprints differ between
				// app-context and Activity-context WebViews. Falls back to the cached one if no Activity is
				// available (e.g. background path).
				val activity = foregroundActivityHolder.current
				val webView: WebView
				val host: ViewGroup?
				val isThrowaway: Boolean
				if (activity != null) {
					webView = WebView(activity).apply { configureForParser(null) }
					host = attachToHost(webView, activity)
					isThrowaway = true
				} else {
					webView = obtainWebView()
					host = null
					isThrowaway = false
				}
				try {
					exception.source.getUserAgent()?.let {
						webView.settings.userAgentString = it
					}
					val resolved = withTimeoutOrNull(timeout) {
						suspendCancellableCoroutine { cont ->
							webView.webViewClient = createCloudFlareClient(webView, exception, cont)
							webView.loadUrl(exception.url)
						}
					}
					if (resolved == null) {
						Log.w(TAG, "Captcha auto-resolve timed out for ${exception.url}, dumping page HTML:")
						dumpPageHtml(webView)
					}
					resolved == true
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					exception.addSuppressed(e)
					e.printStackTraceDebug()
					false
				} finally {
					if (isThrowaway) {
						runCatching { webView.stopLoading() }
						webView.webViewClient = WebViewClient()
						host?.let { detachFromHost(webView, it) }
						runCatching { webView.destroy() }
					} else {
						webView.reset()
					}
				}
			}
		}
		if (cooldownHost != null) {
			if (resolved) {
				recentFailureUntil.remove(cooldownHost)
			} else {
				recentFailureUntil[cooldownHost] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
			}
		}
		return resolved
	}

	/**
	 * Cloudflare Turnstile only completes when the WebView is actually attached to a real window — a detached
	 * WebView reliably returns the `rBxl / GPZI` anti-bot rejection. Attach the WebView to the foreground
	 * Activity's content view (full size, on top) for the duration of the auto-resolve so it has a real
	 * Surface, real layout, and is visible to the user while the challenge runs.
	 *
	 * @return the container we attached to (so we can remove the WebView again), or `null` if no Activity was
	 *         available. In that case we fall back to the detached-WebView behaviour.
	 */
	@MainThread
	private fun attachToHost(webView: WebView, activity: android.app.Activity): ViewGroup? {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
		runCatching {
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.alpha = 1f
			webView.visibility = View.VISIBLE
			webView.bringToFront()
			content.addView(
				webView,
				ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
			)
		}.onFailure {
			it.printStackTraceDebug()
			return null
		}
		return content
	}

	@MainThread
	private fun detachFromHost(webView: WebView, host: ViewGroup) {
		runCatching { host.removeView(webView) }.onFailure { it.printStackTraceDebug() }
	}

	/**
	 * Builds the very same WebViewClient that [org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity] uses,
	 * just driven by a continuation instead of an Activity UI.
	 */
	@MainThread
	private fun createCloudFlareClient(
		webView: WebView,
		exception: CloudFlareException,
		continuation: CancellableContinuation<Boolean>,
	): CloudFlareClient {
		val handler = Handler(Looper.getMainLooper())
		val resumeOnce: (Boolean) -> Unit = { result ->
			handler.removeCallbacksAndMessages(null)
			if (continuation.isActive) continuation.resume(result)
		}
		val initialClearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
		val challengeDeadline = System.currentTimeMillis() + MAX_CHALLENGE_MS
		// CloudFlareClient only signals success when the clearance cookie *changes*. Probe the page state so we can
		// (a) finish the instant the real page is shown, and (b) give up on a stuck challenge before the hard timeout.
		val check = object : Runnable {
			override fun run() {
				if (!continuation.isActive) return
				// Some sources (e.g. utoon) only ever land cf_clearance via Cloudflare's PAT / managed-challenge
				// side-channel while Turnstile itself reports an error. Catch that the moment the cookie appears.
				val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
				if (clearance != null && clearance != initialClearance) {
					resumeOnce(true)
					return
				}
				webView.evaluateJavascript(CF_STATE_JS) { raw ->
					if (!continuation.isActive) return@evaluateJavascript
					when (raw?.removeSurrounding("\"")) {
						"ok" -> resumeOnce(true)
						"error" -> resumeOnce(false)
						else -> if (System.currentTimeMillis() >= challengeDeadline) {
							resumeOnce(false)
						} else {
							handler.removeCallbacks(this)
							handler.postDelayed(this, CHALLENGE_POLL_INTERVAL_MS)
						}
					}
				}
			}
		}
		val callback = object : CloudFlareCallback {
			override fun onLoadingStateChanged(isLoading: Boolean) = Unit
			override fun onHistoryChanged() = Unit

			override fun onPageLoaded() {
				if (!continuation.isActive) return
				// Re-probe immediately on every navigation so a redirect to the real page is detected at once.
				handler.removeCallbacks(check)
				handler.postDelayed(check, 100L)
			}

			override fun onCheckPassed() = resumeOnce(true)

			// CloudFlareClient gives up after a few reloads; headlessly we'd rather keep going until the
			// page-state poll sees the real page (or the overall timeout fires).
			override fun onLoopDetected() = Unit
		}
		return if (needsCloudFlareInterception(exception.source)) {
			CloudFlareInterceptClient(cookieJar, callback, exception.url)
		} else {
			CloudFlareClient(cookieJar, callback, exception.url)
		}
	}

	private fun needsCloudFlareInterception(source: MangaSource): Boolean = runCatching {
		val repository = mangaRepositoryFactoryProvider.get().create(source) as? ParserMangaRepository ?: return false
		repository.getConfigKeys().filterIsInstance<ConfigKey.InterceptCloudflare>().firstOrNull()?.defaultValue == true
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(false)

	@MainThread
	private suspend fun dumpPageHtml(webView: WebView) {
		runCatchingCancellable {
			val html = withTimeoutOrNull(2_000L) {
				suspendCancellableCoroutine<String?> { cont ->
					webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
						if (cont.isActive) cont.resume(result)
					}
				}
			}
			Log.w(TAG, html.orEmpty())
		}.onFailure { it.printStackTraceDebug() }
	}

    @MainThread
    private fun obtainWebView(): WebView = webViewCached?.get() ?: WebView(context).also {
        it.configureForParser(null)
        it.prepareDetachedParserViewport()
        webViewCached = WeakReference(it)
    }

	private fun MangaSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserMangaRepository
		return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
	}

    @MainThread
    fun getDefaultUserAgentSync() = runCatching {
        obtainWebView().settings.userAgentString.sanitizeHeaderValue().trim().nullIfEmpty()
    }.onFailure { e ->
        e.printStackTraceDebug()
    }.getOrNull()

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

	private companion object {

		const val TAG = "WebViewExecutor"
		const val CHALLENGE_POLL_INTERVAL_MS = 700L
		const val MAX_CHALLENGE_MS = 11_000L // give up on a stuck challenge just before the hard timeout

		// After a failed auto-resolve for a host, skip new attempts for this long so a burst of failing
		// requests (favicon + catalog + chapters + …) doesn't queue up multiple full timeouts.
		const val FAILURE_COOLDOWN_MS = 30_000L
	}
}
