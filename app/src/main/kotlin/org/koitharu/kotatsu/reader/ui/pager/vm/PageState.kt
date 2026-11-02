package org.koitharu.kotatsu.reader.ui.pager.vm

import com.davemorrissey.labs.subscaleview.ImageSource
import org.koitharu.kotatsu.reader.domain.erase.PageMask

sealed class PageState {

	data object Empty : PageState()

	data class Loading(
		val preview: ImageSource?,
		val progress: Int,
	) : PageState()

	data class Loaded(
		val source: ImageSource,
		val isConverted: Boolean,
		val mask: PageMask? = null,
	) : PageState()

	class Converting() : PageState()

	data class Shown(
		val source: ImageSource,
		val isConverted: Boolean,
		val mask: PageMask? = null,
	) : PageState()

	data class Error(
		val error: Throwable,
	) : PageState()

	/** A remembered image and nothing else. The list is about to drop this page; show nothing of it. */
	data object Hidden : PageState()

	fun isFinalState(): Boolean = this is Error || this is Shown || this is Hidden
}
