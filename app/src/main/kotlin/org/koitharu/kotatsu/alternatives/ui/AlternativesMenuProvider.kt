package org.koitharu.kotatsu.alternatives.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSortOrder
import org.koitharu.kotatsu.alternatives.domain.AlternativeSourceScope

class AlternativesMenuProvider(
	private val activity: AlternativesActivity,
	private val viewModel: AlternativesViewModel,
) : MenuProvider, MenuItem.OnActionExpandListener, SearchView.OnQueryTextListener {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_alternatives, menu)
		val menuItem = menu.findItem(R.id.action_search)
		menuItem.setOnActionExpandListener(this)
		(menuItem.actionView as SearchView).run {
			setOnQueryTextListener(this@AlternativesMenuProvider)
			queryHint = activity.getString(R.string.search_manga)
		}
	}

	override fun onPrepareMenu(menu: Menu) {
		val options = viewModel.options.value
		menu.findItem(
			when (options.sourceScope) {
				AlternativeSourceScope.ENABLED -> R.id.action_alternatives_sources_enabled
				AlternativeSourceScope.PINNED -> R.id.action_alternatives_sources_pinned
				AlternativeSourceScope.ALL -> R.id.action_alternatives_sources_all
			},
		)?.isChecked = true
		menu.findItem(R.id.action_alternatives_reset)?.isVisible = !options.isDefaultFor(viewModel.manga.title)
		menu.findItem(R.id.action_alternatives_same_language)?.isChecked = options.sameLanguageOnly
		menu.findItem(R.id.action_alternatives_same_content_type)?.isChecked = options.sameContentTypeOnly
		menu.findItem(R.id.action_alternatives_hide_no_chapters)?.isChecked = options.hideNoChapters
		menu.findItem(
			when (options.sortOrder) {
				AlternativeSortOrder.BEST_MATCH -> R.id.action_alternatives_sort_best_match
				AlternativeSortOrder.MOST_CHAPTERS -> R.id.action_alternatives_sort_most_chapters
				AlternativeSortOrder.CLOSEST_CHAPTER_COUNT -> R.id.action_alternatives_sort_closest_chapters
				AlternativeSortOrder.SOURCE_PRIORITY -> R.id.action_alternatives_sort_source_priority
			},
		)?.isChecked = true
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		val options = viewModel.options.value
		when (menuItem.itemId) {
			R.id.action_alternatives_sources_enabled -> viewModel.setSourceScope(AlternativeSourceScope.ENABLED)
			R.id.action_alternatives_sources_pinned -> viewModel.setSourceScope(AlternativeSourceScope.PINNED)
			R.id.action_alternatives_sources_all -> viewModel.setSourceScope(AlternativeSourceScope.ALL)
			R.id.action_alternatives_same_language -> viewModel.setSameLanguageOnly(!options.sameLanguageOnly)
			R.id.action_alternatives_same_content_type -> viewModel.setSameContentTypeOnly(!options.sameContentTypeOnly)
			R.id.action_alternatives_hide_no_chapters -> viewModel.setHideNoChapters(!options.hideNoChapters)
			R.id.action_alternatives_sort_best_match -> viewModel.setSortOrder(AlternativeSortOrder.BEST_MATCH)
			R.id.action_alternatives_sort_most_chapters -> viewModel.setSortOrder(AlternativeSortOrder.MOST_CHAPTERS)
			R.id.action_alternatives_sort_closest_chapters -> viewModel.setSortOrder(AlternativeSortOrder.CLOSEST_CHAPTER_COUNT)
			R.id.action_alternatives_sort_source_priority -> viewModel.setSortOrder(AlternativeSortOrder.SOURCE_PRIORITY)
			R.id.action_alternatives_reset -> viewModel.resetOptions()
			else -> return false
		}
		activity.invalidateMenu()
		return true
	}

	override fun onQueryTextSubmit(query: String?): Boolean {
		query?.takeIf(String::isNotBlank)?.let(viewModel::setQuery)
		return true
	}

	override fun onQueryTextChange(newText: String?): Boolean = false

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		(item.actionView as? SearchView)?.post {
			(item.actionView as? SearchView)?.setQuery(viewModel.options.value.query, false)
		}
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean = true
}
