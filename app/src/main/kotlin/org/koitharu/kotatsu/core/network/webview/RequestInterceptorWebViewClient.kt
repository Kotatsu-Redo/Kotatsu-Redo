package org.koitharu.kotatsu.core.network.webview

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.WorkerThread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.browser.BrowserCallback
import org.koitharu.kotatsu.browser.BrowserClient
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebViewClient that intercepts and captures HTTP requests for VRF token extraction
 * and other dynamic data extraction from AJAX requests.
 */
class RequestInterceptorWebViewClient(
    callback: BrowserCallback,
    adBlock: AdBlock?,
    private val config: InterceptionConfig,
    private val interceptor: WebViewRequestInterceptor,
) : BrowserClient(callback, adBlock) {

    private val capturedRequests = Collections.synchronizedList(mutableListOf<InterceptedRequest>())
    private val mutex = Mutex()
    private val isCapturing = AtomicBoolean(true)
    private val startTime = System.currentTimeMillis()
    private val scriptInjected = AtomicBoolean(false)

    @WorkerThread
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        // Always call parent first for ad blocking and other functionality
        val parentResponse = super.shouldInterceptRequest(view, request)

        // Capture request if still within timeout and capturing
        if (isCapturing.get() && request != null && !isTimeoutReached()) {
            captureRequestIfMatches(request)
        }

        return parentResponse
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Capture navigation events (like window.location.href = "...")
        if (isCapturing.get() && request != null && !isTimeoutReached()) {
            if (captureRequestIfMatches(request)) {
                return true // Stop the WebView from loading the intercepted URL
            }
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val script = config.pageScript
        if (!script.isNullOrBlank() && scriptInjected.compareAndSet(false, true)) {
            Log.d(TAG_VRF, "Injecting pageScript for URL: $url")
            view.evaluateJavascript(script, null)
        } else if (!script.isNullOrBlank()) {
            Log.v(TAG_VRF, "PageScript already injected, skipping for URL: $url")
        }
    }

    /**
     * @return true if the request was captured
     */
    private fun captureRequestIfMatches(request: WebResourceRequest): Boolean {
        try {
            val interceptedRequest = InterceptedRequest(
                url = request.url.toString(),
                method = request.method,
                headers = request.requestHeaders,
                timestamp = System.currentTimeMillis()
            )

            // Check if request matches filtering criteria
            val shouldCapture = when {
                capturedRequests.size >= config.maxRequests -> false
                config.urlPattern != null && !interceptedRequest.urlMatches(config.urlPattern) -> false
                else -> interceptor.shouldCaptureRequest(interceptedRequest)
            }

            if (shouldCapture) {
                val shouldComplete = synchronized(capturedRequests) {
                    capturedRequests.add(interceptedRequest)
                    capturedRequests.size >= config.maxRequests
                }

                // If we've reached maxRequests, stop capturing immediately
                if (shouldComplete) {
                    Log.d(TAG_VRF, "Reached maxRequests (${config.maxRequests}), stopping capture immediately")
                    stopCapturing()
                }
                return true
            }
        } catch (e: Exception) {
            // Don't let interception errors break the WebView
            interceptor.onInterceptionError(e)
        }
        return false
    }

    private fun isTimeoutReached(): Boolean {
        return System.currentTimeMillis() - startTime > config.timeoutMs
    }

    private fun completeInterception() {
        try {
            val finalRequests = synchronized(capturedRequests) {
                capturedRequests.toList()
            }
            interceptor.onInterceptionComplete(finalRequests)
        } catch (e: Exception) {
            interceptor.onInterceptionError(e)
        }
    }

    /**
     * Manually stop capturing requests before timeout
     */
    fun stopCapturing() {
        if (isCapturing.compareAndSet(true, false)) {
            completeInterception()
        }
    }

    /**
     * Get currently captured requests (thread-safe)
     */
    fun getCapturedRequests(): List<InterceptedRequest> {
        return synchronized(capturedRequests) {
            capturedRequests.toList()
        }
    }
}
