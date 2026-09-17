package org.koitharu.kotatsu.search.domain

import android.util.Log
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionScope

/**
 * Traces the screen filter from the tap that applies it to the rows it selects.
 *
 * The three stages are logged separately because a filter that returns nothing can fail at any of
 * them: the wrong text can be applied, the right text can build a predicate that cannot match, or the
 * predicate can be correct and the data simply not contain it.
 *
 * Uses [Log.i] rather than [Log.d]: `proguard-android-optimize` strips debug and verbose calls, and
 * this has to work in a release or nightly build.
 *
 * Read with: `adb logcat -s KotatsuFilter`
 */
object ScreenFilterLog {

	const val TAG = "KotatsuFilter"

	/** What the user's tap turned into, and where it came from. */
	fun applied(scope: SearchSuggestionScope, value: String, origin: String) {
		Log.i(TAG, "APPLY scope=$scope origin=$origin text=\"$value\"")
	}

	/** The predicate the text produced. */
	fun condition(table: String, sql: String) {
		Log.i(TAG, "SQL   $table -> $sql")
	}

	/** How many rows came back for it. */
	fun result(scope: SearchSuggestionScope, query: String, count: Int) {
		Log.i(TAG, "RESULT scope=$scope text=\"$query\" rows=$count")
	}
}
