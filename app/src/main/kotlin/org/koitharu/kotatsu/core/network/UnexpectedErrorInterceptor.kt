package org.koitharu.kotatsu.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException

/**
 * Turns whatever else goes wrong while a request is made into a failed request.
 *
 * A request can only fail with an [IOException]. Anything else thrown on the way - a header a parser built from a
 * site address with letters it cannot hold, a host name that cannot be looked up, an error page that would not
 * parse on an old Android - is thrown again on the network's own thread once the caller has been told, where
 * nothing catches it and the app closes. Has to be the first interceptor, to be around all the others.
 */
class UnexpectedErrorInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response = try {
		chain.proceed(chain.request())
	} catch (e: IOException) {
		throw e
	} catch (e: RuntimeException) {
		throw IOException(e.message ?: e.javaClass.simpleName, e)
	}
}
