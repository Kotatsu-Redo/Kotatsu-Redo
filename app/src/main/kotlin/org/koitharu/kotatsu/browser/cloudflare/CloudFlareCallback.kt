package org.koitharu.kotatsu.browser.cloudflare

import org.koitharu.kotatsu.browser.BrowserCallback

interface CloudFlareCallback : BrowserCallback {

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) = Unit

	fun onPageLoaded()

	fun onCheckPassed()

	fun onLoopDetected()

	/**
	 * Fired by [CloudFlareInterceptClient] when the replayed main-frame request comes back with a
	 * successful (2xx) HTTP status — i.e. we got the real page rather than a CloudFlare interstitial.
	 * Default no-op so non-CloudFlare callers don't have to implement it.
	 */
	fun onMainFrameResponseSuccess() = Unit
}
