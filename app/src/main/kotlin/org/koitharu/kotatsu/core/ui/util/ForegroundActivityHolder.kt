package org.koitharu.kotatsu.core.ui.util

import android.app.Activity
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently resumed Activity so callers (e.g. the headless WebView used for CloudFlare auto-solving)
 * can briefly attach views to it. Some Cloudflare Turnstile challenges only complete when the WebView is actually
 * attached to a real window — a detached WebView reliably returns "rBxl / GPZI" anti-bot rejections.
 */
@Singleton
class ForegroundActivityHolder @Inject constructor() : DefaultActivityLifecycleCallbacks {

	@Volatile
	private var activityRef: WeakReference<Activity>? = null

	val current: Activity?
		get() = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

	override fun onActivityResumed(activity: Activity) {
		activityRef = WeakReference(activity)
	}

	override fun onActivityPaused(activity: Activity) {
		if (activityRef?.get() === activity) {
			activityRef = null
		}
	}
}
