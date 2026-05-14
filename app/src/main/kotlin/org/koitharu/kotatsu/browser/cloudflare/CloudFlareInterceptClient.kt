package org.koitharu.kotatsu.browser.cloudflare

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "CFInterceptClient"

/**
 * CloudFlare client with header interception to bypass blocking
 * Filters out sec-ch-ua, sec-ch-ua-full-version-list, and x-requested-with headers
 */
class CloudFlareInterceptClient(
	private val cookieJar: MutableCookieJar,
	callback: CloudFlareCallback,
	targetUrl: String,
) : CloudFlareClient(cookieJar, callback, targetUrl) {

	private val targetUri = runCatching { URI(targetUrl) }.getOrNull()

	// Headers we want to block
	private val blockedHeaders = setOf(
		"sec-ch-ua",
		"sec-ch-ua-full-version-list",
		"x-requested-with"
	)

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		Log.d(TAG, "Page started with interception enabled: $url")
	}

	override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
		if (request == null) return null

		try {
			if (!shouldReplayRequest(request)) {
				return super.shouldInterceptRequest(view, request)
			}

			Log.d(TAG, "Intercepting request: ${request.url}")

			val client = OkHttpClient.Builder()
				.cookieJar(cookieJar)
				.connectTimeout(15, TimeUnit.SECONDS)
				.readTimeout(15, TimeUnit.SECONDS)
				.build()

			val requestBuilder = Request.Builder()
				.url(request.url.toString())
				.method(request.method, null)

			// Filter headers using blocklist - keep everything except blocked headers
			val blockedCount = mutableListOf<String>()
			for ((key, value) in request.requestHeaders) {
				val lowerKey = key.lowercase(Locale.ROOT)
				if (!blockedHeaders.contains(lowerKey)) {
					requestBuilder.addHeader(key, value)
				} else {
					blockedCount.add(key)
				}
			}
			if (blockedCount.isNotEmpty()) {
				Log.d(TAG, "Blocked headers: ${blockedCount.joinToString(", ")}")
			}

			val response = client.newCall(requestBuilder.build()).execute()

			// If the replayed main-frame request returns a normal 2xx, CloudFlare let us through —
			// we got the real page, not an interstitial. Signal success right here (on the main thread)
			// so the activity can self-close without waiting for the cookie poll / navigation listener.
			if (request.isForMainFrame && response.code in 200..299) {
				android.os.Handler(android.os.Looper.getMainLooper()).post {
					callback.onMainFrameResponseSuccess()
				}
			}

			val contentType = response.header("Content-Type")
			val mimeType: String
			val charset: String?

			if (contentType != null) {
				val parts = contentType.split(";")
				mimeType = parts[0].trim()
				charset = parts.find { it.trim().startsWith("charset=", ignoreCase = true) }
					?.substringAfter("=")?.trim()
			} else {
				mimeType = "text/html"
				charset = "UTF-8"
			}

			return WebResourceResponse(mimeType, charset, response.body?.byteStream()).apply {
				val headers = mutableMapOf<String, String>()
				response.headers.forEach { headers[it.first] = it.second }
				responseHeaders = headers
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error intercepting request: ${request.url}", e)
			return null
		}
	}

	private fun shouldReplayRequest(request: WebResourceRequest): Boolean {
		if (request.method != "GET") {
			Log.d(TAG, "Skipping non-GET request: ${request.method} ${request.url}")
			return false
		}
		if (!request.isForMainFrame) {
			return false
		}
		if (!hasBlockedHeaders(request)) {
			return false
		}
		val requestUri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return false
		val sameOrigin = requestUri.scheme.equals(targetUri?.scheme, ignoreCase = true)
			&& requestUri.host.equals(targetUri?.host, ignoreCase = true)
			&& normalizedPort(requestUri) == normalizedPort(targetUri)
		if (!sameOrigin) {
			Log.d(TAG, "Skipping off-origin main frame request: ${request.url}")
		}
		return sameOrigin
	}

	private fun hasBlockedHeaders(request: WebResourceRequest): Boolean {
		return request.requestHeaders.keys.any { it.lowercase(Locale.ROOT) in blockedHeaders }
	}

	private fun normalizedPort(uri: URI?): Int {
		if (uri == null) return -1
		return when {
			uri.port != -1 -> uri.port
			uri.scheme.equals("https", ignoreCase = true) -> 443
			uri.scheme.equals("http", ignoreCase = true) -> 80
			else -> -1
		}
	}
}
