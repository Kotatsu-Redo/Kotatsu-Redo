package org.koitharu.kotatsu.sourcescore.domain

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps what is waiting to be sent from outliving the reader's consent: when community features are off, no pass
 * is left pending, whichever screen switched them off, and whether this process was running at the time or not.
 */
@Singleton
class DismissalConsent @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: CommunitySettings,
	private val dismissals: DiscoverDismissals,
) {

	/** Held by this class: preferences only keep a weak reference to a listener. */
	private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		// A null key is the whole file being cleared, as when the account is forgotten
		if (key == null || key == CommunitySettings.KEY_ENABLED) {
			cancelIfOff()
		}
	}

	fun start() {
		context.getSharedPreferences(CommunitySettings.FILE_NAME, Context.MODE_PRIVATE)
			.registerOnSharedPreferenceChangeListener(listener)
		cancelIfOff()
	}

	private fun cancelIfOff() {
		if (settings.isEnabled) return
		// Reads the stored passes the first time, which is disk
		processLifecycleScope.launch(Dispatchers.IO) { dismissals.store.cancelPending() }
	}
}
