package org.koitharu.kotatsu.discover.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the reader said they are not interested in: the title, which never comes back, and its tags, which count
 * a little against what is like it. Stays on the device like everything else about what someone likes.
 */
@Singleton
class DiscoverDismissals @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
	private val state = MutableStateFlow<Map<String, List<String>>?>(null)

	/** Title keys to the tags of what they were. */
	fun observe(): Flow<Map<String, List<String>>> = flow {
		emit(load())
		emitAll(state.filterNotNull())
	}.distinctUntilChanged().flowOn(Dispatchers.IO)

	suspend fun get(): Map<String, List<String>> = withContext(Dispatchers.IO) { load() }

	/** @return what to pass to [remove] to take it back */
	fun add(manga: Manga): String {
		val key = DiscoverRanking.titleKey(manga.title)
		val tags = manga.tags.map { it.title }
		prefs.edit { putString(key, tags.joinToString(TAG_DIVIDER)) }
		state.update { it.orEmpty() + (key to tags) }
		return key
	}

	fun remove(key: String) {
		prefs.edit { remove(key) }
		state.update { it.orEmpty() - key }
	}

	fun clear() {
		prefs.edit { clear() }
		state.value = emptyMap()
	}

	private fun load(): Map<String, List<String>> = state.value ?: prefs.all.entries
		.associate { (key, value) -> key to (value as? String).orEmpty().split(TAG_DIVIDER).filter { it.isNotEmpty() } }
		.also { state.value = it }

	private companion object {
		const val PREFS_NAME = "discover_dismissed"
		const val TAG_DIVIDER = "|"
	}
}
