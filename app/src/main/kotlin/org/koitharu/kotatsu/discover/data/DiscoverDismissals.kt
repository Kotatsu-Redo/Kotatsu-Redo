package org.koitharu.kotatsu.discover.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the reader said they are not interested in: the title, which never comes back under any name or address
 * it is known by, and its tags, which count a little against what is like it. Stays on the device like everything
 * else about what someone likes, unless the reader turned on community features. See [DismissedStore].
 */
@Singleton
class DiscoverDismissals @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	val store: DismissedStore by lazy {
		DismissedStore(
			entries = PrefsKeyValueStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
			withdrawals = PrefsKeyValueStore(context.getSharedPreferences(WITHDRAWN_PREFS_NAME, Context.MODE_PRIVATE)),
		)
	}

	fun observe(): Flow<DismissedIndex> = store.observe().flowOn(Dispatchers.IO)

	suspend fun get(): DismissedIndex = withContext(Dispatchers.IO) { store.index() }

	private class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

		override fun all(): Map<String, String> = prefs.all.entries.associate { (key, value) -> key to (value as? String).orEmpty() }

		override fun put(values: Map<String, String>) = prefs.edit { values.forEach { (key, value) -> putString(key, value) } }

		override fun remove(keys: Collection<String>) = prefs.edit { keys.forEach { remove(it) } }

		override fun clear() = prefs.edit { clear() }
	}

	private companion object {
		const val PREFS_NAME = "discover_dismissed"
		const val WITHDRAWN_PREFS_NAME = "discover_dismissed_withdrawn"
	}
}
