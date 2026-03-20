package org.koitharu.kotatsu.core.parser

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.toList
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.use
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.core.network.webview.WebViewRequestInterceptorExecutor
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig as ParsersInterceptionConfig
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.map
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.use

@Singleton
class MangaLoaderContextImpl @Inject constructor(
    @MangaHttpClient override val httpClient: OkHttpClient,
    override val cookieJar: MutableCookieJar,
    @ApplicationContext private val androidContext: Context,
    private val webViewExecutor: WebViewExecutor,
    private val webViewRequestInterceptorExecutor: WebViewRequestInterceptorExecutor,
) : MangaLoaderContext() {

    private val webViewUserAgent by lazy { obtainWebViewUserAgent() }

    @Deprecated("Provide a base url")
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun evaluateJs(script: String): String? = evaluateJs("", script, timeout = 10000L)

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        webViewExecutor.evaluateJs(baseUrl, script, timeoutMs = timeout)

    override fun getDefaultUserAgent(): String = webViewUserAgent

    override fun getConfig(source: MangaSource): MangaSourceConfig {
        return SourceSettings(androidContext, source)
    }

    override fun encodeBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    override fun decodeBase64(data: String): ByteArray {
        return Base64.decode(data, Base64.DEFAULT)
    }

    override fun getPreferredLocales(): List<Locale> {
        return LocaleListCompat.getAdjustedDefault().toList()
    }

    override fun requestBrowserAction(
        parser: MangaParser,
        url: String,
    ): Nothing = throw InteractiveActionRequiredException(parser.source, url)

    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        return response.map { body ->
            BitmapDecoderCompat.decode(body.byteStream(), body.contentType()?.toMimeType(), isMutable = true)
                .use { bitmap ->
                    (redraw(BitmapWrapper.create(bitmap)) as BitmapWrapper).use { result ->
                        Buffer().also {
                            result.compressTo(it.outputStream())
                        }.asResponseBody("image/jpeg".toMediaType())
                    }
                }
        }
    }

    override fun createBitmap(width: Int, height: Int): Bitmap = BitmapWrapper.create(width, height)

    // kotlin
    override suspend fun interceptWebViewRequests(
        url: String,
        interceptorScript: String,
        timeout: Long
    ): List<InterceptedRequest> {
        val config = org.koitharu.kotatsu.core.network.webview.InterceptionConfig(
            timeoutMs = timeout,
            maxRequests = 100,
            filterScript = interceptorScript
        )

        val captured = webViewRequestInterceptorExecutor.interceptRequests(url, config)
        return captured.map { appRequest ->
            InterceptedRequest(
                url = appRequest.url,
                method = appRequest.method,
                headers = appRequest.headers,
                timestamp = appRequest.timestamp,
                body = appRequest.body
            )
        }
    }

    // New method to support InterceptionConfig with pageScript
    override suspend fun interceptWebViewRequests(
        url: String,
        config: ParsersInterceptionConfig
    ): List<InterceptedRequest> {
        val appConfig = org.koitharu.kotatsu.core.network.webview.InterceptionConfig(
            timeoutMs = config.timeoutMs,
            maxRequests = config.maxRequests,
            urlPattern = config.urlPattern,
            filterScript = config.filterScript,
            pageScript = config.pageScript  // This is the key part that was missing!
        )

        val captured = webViewRequestInterceptorExecutor.interceptRequests(url, appConfig)
        return captured.map { appRequest ->
            InterceptedRequest(
                url = appRequest.url,
                method = appRequest.method,
                headers = appRequest.headers,
                timestamp = appRequest.timestamp,
                body = appRequest.body
            )
        }
    }

    private fun evaluateFilterPredicate(script: String, requestUrl: String): Boolean {
        // Extract the last `return ...;` expression from the script
        val returnIdx = script.lastIndexOf("return")
        if (returnIdx == -1) {
            // No explicit return -> default to capturing
            return true
        }
        val afterReturn = script.substring(returnIdx + "return".length)
        val expr = afterReturn.substringBefore(";").trim().ifEmpty { return true }

        // Support expressions like:
        //   url.includes('a') && url.includes('b') || url.includes('c')
        // Split by OR
        val orClauses = expr.split("||").map { it.trim() }
        for (clause in orClauses) {
            // Remove wrapping parentheses
            val normalized = clause.trim().trim('(', ')').trim()
            // Split by AND
            val andTerms = normalized.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            var allMatch = true
            for (term in andTerms) {
                // Only support url.includes('<text>') terms for now
                val m = Regex("""url\.includes\(\s*(['"])(.*?)\1\s*\)""").find(term)
                if (m != null) {
                    val needle = m.groupValues[2]
                    if (!requestUrl.contains(needle)) {
                        allMatch = false
                        break
                    }
                } else {
                    // Unknown term -> fail this clause
                    allMatch = false
                    break
                }
            }
            if (allMatch) return true
        }
        return false
    }

    override suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long
    ): List<String> {
        return webViewRequestInterceptorExecutor.captureWebViewUrls(pageUrl, urlPattern, timeout)
    }

    private fun obtainWebViewUserAgent(): String {
        val mainDispatcher = Dispatchers.Main.immediate
        return if (!mainDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            webViewExecutor.getDefaultUserAgentSync()
        } else {
            runBlocking(mainDispatcher) {
                webViewExecutor.getDefaultUserAgentSync()
            }
        } ?: UserAgents.FIREFOX_MOBILE
    }
}
