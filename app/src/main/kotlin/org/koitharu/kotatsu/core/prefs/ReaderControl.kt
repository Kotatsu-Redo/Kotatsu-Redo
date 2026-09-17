package org.koitharu.kotatsu.core.prefs

import java.util.EnumSet

enum class ReaderControl {

	PREV_CHAPTER, NEXT_CHAPTER, SLIDER, PAGES_SHEET, SCREEN_ROTATION, SAVE_PAGE, TIMER, BOOKMARK,
	COMMENTS;

	companion object {

		/**
		 * Comments rather than the chapter list. Both are one tap away from the reader either way -
		 * the chapter list is still on the options menu and a long press on the bookmark - and of the
		 * two, the one worth a permanent slot is the one that changes while you are reading.
		 *
		 * PAGES_SHEET is still a control; it is simply no longer on by default. Anyone who wants it
		 * back turns it on in the reader settings.
		 */
		val DEFAULT: Set<ReaderControl> = EnumSet.of(
			PREV_CHAPTER, NEXT_CHAPTER, SLIDER, COMMENTS,
		)
	}
}
