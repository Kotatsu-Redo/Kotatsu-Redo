package org.koitharu.kotatsu.core.prefs

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.Keep

@Keep
enum class EInkFlashColor(@ColorInt val colorInt: Int) {
	WHITE(Color.WHITE),
	BLACK(Color.BLACK),
}
