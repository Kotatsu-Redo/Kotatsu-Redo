package org.koitharu.kotatsu.core.util.ext

import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import android.view.View

val WindowInsetsCompat.systemBarsInsets: Insets
    get() = getInsets(WindowInsetsCompat.Type.systemBars())

/**
 * Consume all system bars insets. Returns a consumed WindowInsetsCompat.
 * Simple helper to centralize calls that expect consumed insets.
 */
fun WindowInsetsCompat.consumeAllSystemBarsInsets(): WindowInsetsCompat = WindowInsetsCompat.CONSUMED

fun WindowInsetsCompat.consumeAll(typeMask: Int): WindowInsetsCompat = WindowInsetsCompat.CONSUMED

fun WindowInsetsCompat.consume(typeMask: Int = WindowInsetsCompat.Type.systemBars()): WindowInsetsCompat = WindowInsetsCompat.CONSUMED

fun WindowInsetsCompat.consume(
    view: View,
    typeMask: Int = WindowInsetsCompat.Type.systemBars(),
    top: Boolean = false,
    bottom: Boolean = false,
    start: Boolean = false,
    end: Boolean = false,
): WindowInsetsCompat = WindowInsetsCompat.CONSUMED

fun WindowInsetsCompat.consumeSystemBarsInsets(
    top: Boolean = false,
    bottom: Boolean = false,
    start: Boolean = false,
    end: Boolean = false,
): WindowInsetsCompat = WindowInsetsCompat.CONSUMED
