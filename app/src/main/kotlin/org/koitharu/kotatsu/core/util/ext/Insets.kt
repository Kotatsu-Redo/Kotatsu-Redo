package org.koitharu.kotatsu.core.util.ext

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type.InsetsType

fun Insets.end(view: View): Int {
	return if (view.isRtl) left else right
}

fun Insets.start(view: View): Int {
	return if (view.isRtl) right else left
}

// Deprecated Insets helpers removed. Use WindowInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars()) and WindowInsetsCompat.Builder for manipulations.
