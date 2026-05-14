package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.browser.BrowserCallback
import org.koitharu.kotatsu.core.util.ext.configureForParser
import org.koitharu.kotatsu.core.util.ext.prepareDetachedParserViewport
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val TAG_VRF = "MF_VRF"

@Singleton
class WebViewRequestInterceptorExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var webViewCached: WeakReference<WebView>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWebViews = mutableSetOf<WeakReference<WebView>>()

    suspend fun interceptRequests(
        url: String,
        config: InterceptionConfig
    ): List<InterceptedRequest> = withTimeout(config.timeoutMs + 5000) {
        Log.d(TAG_VRF, "interceptRequests start url=$url injectPageScript=${!config.pageScript.isNullOrBlank()} hasFilterScript=${!config.filterScript.isNullOrBlank()}")
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val resultDeferred = CompletableDeferred<List<InterceptedRequest>>()

                val interceptor = object : WebViewRequestInterceptor {
                    override fun shouldCaptureRequest(request: InterceptedRequest): Boolean {
                        val urlOk = config.urlPattern?.containsMatchIn(request.url) ?: true
                        val scriptOk = try {
                            if (config.filterScript.isNullOrBlank()) true
                            else evaluateFilterPredicate(config.filterScript, request.url)
                        } catch (e: Throwable) {
                            Log.w(TAG_VRF, "Filter error ${e.message}")
                            false
                        }
                        val match = urlOk && scriptOk
                        Log.v(TAG_VRF, "REQ url=${request.url} method=${request.method} urlOk=$urlOk scriptOk=$scriptOk match=$match")
                        return match
                    }

                    override fun onInterceptionComplete(capturedRequests: List<InterceptedRequest>) {
                        Log.d(TAG_VRF, "Interception complete captured=${capturedRequests.size}")
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(capturedRequests)
                        }
                    }

                    override fun onInterceptionError(error: Throwable) {
                        Log.w(TAG_VRF, "Interception error", error)
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.completeExceptionally(error)
                        }
                    }
                }

                val callback = object : BrowserCallback {
                    override fun onLoadingStateChanged(isLoading: Boolean) {
                        Log.v(TAG_VRF, "Loading state changed isLoading=$isLoading")
                    }
                    override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
                        Log.v(TAG_VRF, "Title changed title=$title subtitle=$subtitle")
                    }
                    override fun onHistoryChanged() {
                        Log.v(TAG_VRF, "History changed")
                    }
                }

                var webView: WebView? = null
                try {
                    webView = obtainWebView()
                    val client = RequestInterceptorWebViewClient(callback, config, interceptor)
                    webView.webViewClient = client

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request?.resources?.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) == true) {
                                request.grant(request.resources)
                            } else {
                                super.onPermissionRequest(request)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    val timeoutRunnable = Runnable {
                        Log.w(TAG_VRF, "Timeout, stopping capture")
                        client.stopCapturing()
                    }
                    mainHandler.postDelayed(timeoutRunnable, config.timeoutMs)

                    resultDeferred.invokeOnCompletion { ex ->
                        mainHandler.removeCallbacks(timeoutRunnable)
                        // Clean up WebView after operation completes - must run on main thread
                        webView?.let { wv ->
                            Log.d(TAG_VRF, "Cleaning up WebView after operation")
                            if (Thread.currentThread() == Looper.getMainLooper().thread) {
                                // Already on main thread, destroy immediately
                                wv.stopLoading()
                                wv.destroy()
                                removeWebViewFromTracking(wv)
                                if (ex != null) continuation.resumeWithException(ex)
                                else continuation.resume(resultDeferred.getCompleted())
                            } else {
                                // Must post to main thread, but wait for completion
                                mainHandler.post {
                                    wv.stopLoading()
                                    wv.destroy()
                                    removeWebViewFromTracking(wv)
                                    if (ex != null) continuation.resumeWithException(ex)
                                    else continuation.resume(resultDeferred.getCompleted())
                                }
                            }
                        } ?: run {
                            // No WebView to clean up, resume immediately
                            if (ex != null) continuation.resumeWithException(ex)
                            else continuation.resume(resultDeferred.getCompleted())
                        }
                    }
                    continuation.invokeOnCancellation {
                        client.stopCapturing()
                        mainHandler.removeCallbacks(timeoutRunnable)
                        // Clean up WebView on cancellation - must run on main thread
                        webView?.let { wv ->
                            Log.d(TAG_VRF, "Cleaning up WebView on cancellation")
                            if (Thread.currentThread() == Looper.getMainLooper().thread) {
                                // Already on main thread, destroy immediately
                                wv.stopLoading()
                                wv.destroy()
                                removeWebViewFromTracking(wv)
                            } else {
                                // Must post to main thread for cleanup
                                mainHandler.post {
                                    wv.stopLoading()
                                    wv.destroy()
                                    removeWebViewFromTracking(wv)
                                }
                            }
                        }
                        if (!resultDeferred.isCompleted) resultDeferred.cancel()
                    }
                } catch (e: Exception) {
                    // Clean up WebView on exception - must run on main thread
                    webView?.let { wv ->
                        Log.d(TAG_VRF, "Cleaning up WebView on exception")
                        if (Thread.currentThread() == Looper.getMainLooper().thread) {
                            // Already on main thread, destroy immediately
                            wv.destroy()
                            removeWebViewFromTracking(wv)
                            continuation.resumeWithException(e)
                        } else {
                            // Must post to main thread, then resume with exception
                            mainHandler.post {
                                wv.destroy()
                                removeWebViewFromTracking(wv)
                                continuation.resumeWithException(e)
                            }
                        }
                    } ?: continuation.resumeWithException(e)
                }
            }
        }
    }

    suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long = 30000L
    ): List<String> {
        val config = InterceptionConfig(
            timeoutMs = timeout,
            urlPattern = urlPattern,
            maxRequests = 1
        )
        return interceptRequests(pageUrl, config)
            .also { Log.d(TAG_VRF, "captureWebViewUrls matched=${it.size}") }
            .map { it.url }
    }

    @MainThread
    private fun obtainWebView(): WebView {
        // Clean up any previous WebView instances
        cleanupOldWebViews()

        val wv = WebView(context).apply {
            configureForParser(null)
            prepareDetachedParserViewport()
            clearHistory()
        }
        Log.d(TAG_VRF, "Created fresh WebView instance")

        // Track this WebView
        val webViewRef = WeakReference(wv)
        webViewCached = webViewRef
        synchronized(activeWebViews) {
            activeWebViews.add(webViewRef)
        }

        return wv
    }

    @MainThread
    private fun cleanupOldWebViews() {
        synchronized(activeWebViews) {
            val iterator = activeWebViews.iterator()
            while (iterator.hasNext()) {
                val ref = iterator.next()
                val webView = ref.get()
                if (webView == null) {
                    // WebView was garbage collected
                    iterator.remove()
                } else {
                    // Destroy still-active WebView
                    Log.d(TAG_VRF, "Destroying previous WebView instance")
                    webView.destroy()
                    iterator.remove()
                }
            }
        }
        webViewCached = null
    }

    @MainThread
    private fun removeWebViewFromTracking(webView: WebView) {
        synchronized(activeWebViews) {
            activeWebViews.removeAll { it.get() == webView || it.get() == null }
        }
        if (webViewCached?.get() == webView) {
            webViewCached = null
        }
    }

}

// If you added evaluateFilterPredicate earlier, keep it here.
fun evaluateFilterPredicate(script: String, requestUrl: String): Boolean {
    Log.v(TAG_VRF, "Full script: '$script'")

    val returnIdx = script.lastIndexOf("return")
    if (returnIdx == -1) {
        Log.v(TAG_VRF, "No return in script, capturing all. url=$requestUrl")
        return true
    }

    // Extract everything after "return " until end of script, then remove semicolon if present
    val afterReturn = script.substring(returnIdx + 6).trim()
    val expr = if (afterReturn.endsWith(";")) {
        afterReturn.dropLast(1).trim()
    } else {
        afterReturn
    }

    if (expr.isEmpty()) {
        Log.v(TAG_VRF, "Empty return expression, capturing all. url=$requestUrl")
        return true
    }

    Log.v(TAG_VRF, "Evaluating predicate for url=$requestUrl expr='$expr'")

    // Simple check for url.includes('vrf=') - handle it directly without complex parsing
    if (expr == "url.includes('vrf=')" || expr == """url.includes("vrf=")""") {
        val contains = requestUrl.contains("vrf=")
        val status = if (contains) "Predicate MATCH" else "Predicate MISS"
        Log.d(TAG_VRF, "$status url=$requestUrl expr=$expr contains=$contains")
        return contains
    }

    // Fallback to complex parsing for other expressions
    val orClauses = expr.split("||").map { it.trim() }
    for (clause in orClauses) {
        val andTerms = clause.trim().trim('(', ')')
            .split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        var allMatch = true

        Log.v(TAG_VRF, "Checking clause: '$clause' with ${andTerms.size} AND terms")

        for (term in andTerms) {
            Log.v(TAG_VRF, "Processing term: '$term'")

            // Handle url.includes('...') or url.includes("...")
            val singleQuoteMatch = Regex("""url\.includes\(\s*'([^']*)'\s*\)""").find(term)
            val doubleQuoteMatch = Regex("""url\.includes\(\s*"([^"]*)"\s*\)""").find(term)

            val match = singleQuoteMatch ?: doubleQuoteMatch
            if (match != null) {
                val needle = match.groupValues[1]
                val contains = requestUrl.contains(needle)
                Log.v(TAG_VRF, "Term: '$term' -> needle: '$needle' -> contains: $contains")
                if (!contains) {
                    allMatch = false
                    break
                }
            } else {
                Log.v(TAG_VRF, "Term does not match url.includes pattern: '$term'")
                allMatch = false
                break
            }
        }
        if (allMatch) {
            Log.d(TAG_VRF, "Predicate MATCH url=$requestUrl clause='$clause'")
            return true
        }
    }
    Log.d(TAG_VRF, "Predicate MISS url=$requestUrl expr='$expr'")
    return false
}
