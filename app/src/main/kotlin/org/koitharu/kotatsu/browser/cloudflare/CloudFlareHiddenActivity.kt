package org.koitharu.kotatsu.browser.cloudflare

import dagger.hilt.android.AndroidEntryPoint

/**
 * Same as [CloudFlareActivity] but registered in the manifest with a translucent theme so it's actually
 * hidden — `windowIsTranslucent` is read by the WindowManager *before* `onCreate`, so applying it via
 * `setTheme()` at runtime doesn't work. The auto-resolve flow launches this class instead of [CloudFlareActivity].
 */
@AndroidEntryPoint
class CloudFlareHiddenActivity : CloudFlareActivity() {

	// Keep the manifest's translucent theme; don't let BaseActivity overlay the app color scheme on top of it.
	override val applyColorSchemeTheme: Boolean = false
}
