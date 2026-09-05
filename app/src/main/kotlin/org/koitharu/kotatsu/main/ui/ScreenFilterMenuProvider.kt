package org.koitharu.kotatsu.main.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

/**
 * Puts a clear button in the search bar while a library screen is filtered.
 *
 * Without it a filter can only be undone by re-opening search and emptying the field, which is easy
 * to miss - the list just looks short.
 */
class ScreenFilterMenuProvider(
	private val isFilterActive: () -> Boolean,
	private val onClear: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_screen_filter, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_clear_screen_filter)?.isVisible = isFilterActive()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_clear_screen_filter -> {
			onClear()
			true
		}

		else -> false
	}
}
