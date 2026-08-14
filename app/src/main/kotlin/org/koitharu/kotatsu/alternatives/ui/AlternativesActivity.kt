package org.koitharu.kotatsu.alternatives.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchStatus
import org.koitharu.kotatsu.alternatives.domain.AlternativeSortOrder
import org.koitharu.kotatsu.alternatives.domain.AlternativeSourceScope
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.OptionsMenuBadgeHelper
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityAlternativesBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.buttonFooterAD
import org.koitharu.kotatsu.list.ui.adapter.emptyStateListAD
import org.koitharu.kotatsu.list.ui.adapter.loadingFooterAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class AlternativesActivity : BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<MangaAlternativeModel>,
	ChipsView.OnChipClickListener,
	ChipsView.OnChipCloseClickListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<AlternativesViewModel>()
	private lateinit var filterBadge: OptionsMenuBadgeHelper

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(true)
		}
		viewBinding.collapsingToolbarLayout.subtitle =
			buildReferenceSubtitle(MangaAlternativeReference(viewModel.manga))
		addMenuProvider(AlternativesMenuProvider(this, viewModel))
		filterBadge = OptionsMenuBadgeHelper(
			viewBinding.toolbar,
			R.id.action_alternatives_filter,
			getString(R.string.filter_active),
		)
		viewBinding.chipsSummary.onChipClickListener = this
		viewBinding.chipsSummary.onChipCloseClickListener = this
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
			.addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
			.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
			.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.options.observe(this) {
			updateSummaryChips()
			viewBinding.toolbar.post {
				filterBadge.setBadgeVisible(!it.isDefaultFor(viewModel.manga.title))
			}
		}
		viewModel.searchStatus.observe(this) {
			updateSearchProgress(it)
			updateSummaryChips()
		}
		viewModel.referenceManga.observe(this) {
			viewBinding.collapsingToolbarLayout.subtitle = buildReferenceSubtitle(it)
		}
		viewModel.onMigrated.observeEvent(this) {
			Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			router.openDetails(it)
			finishAfterTransition()
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: MangaAlternativeModel, view: View) {
		when (view.id) {
			R.id.chip_source -> router.openSearch(item.manga.source, viewModel.options.value.query)
			R.id.button_migrate -> if (item.isMigrationEnabled && item.chaptersCount > 0) {
				confirmMigration(item.manga)
			}
			else -> router.openDetails(item.manga)
		}
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()

	override fun onEmptyActionClick() = Unit

	override fun onFooterButtonClick() = viewModel.continueSearch()

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data as? SummaryChip) {
			SummaryChip.QUERY -> viewBinding.toolbar.menu.findItem(R.id.action_search)?.expandActionView()
			SummaryChip.RESET -> {
				viewModel.resetOptions()
				invalidateMenu()
			}

			SummaryChip.STATUS, null -> Unit
			else -> viewBinding.toolbar.menu.performIdentifierAction(R.id.action_alternatives_filter, 0)
		}
	}

	override fun onChipCloseClick(chip: Chip, data: Any?) {
		when (data as? SummaryChip) {
			SummaryChip.QUERY -> viewModel.setQuery(viewModel.manga.title)
			SummaryChip.SOURCE_SCOPE -> viewModel.setSourceScope(AlternativeSourceScope.ENABLED)
			SummaryChip.LANGUAGE -> viewModel.setSameLanguageOnly(false)
			SummaryChip.CONTENT_TYPE -> viewModel.setSameContentTypeOnly(false)
			SummaryChip.HAS_CHAPTERS -> viewModel.setHideNoChapters(false)
			SummaryChip.SORT -> viewModel.setSortOrder(AlternativeSortOrder.BEST_MATCH)
			SummaryChip.RESET -> viewModel.resetOptions()
			SummaryChip.STATUS, null -> Unit
		}
		invalidateMenu()
	}

	private fun confirmMigration(target: Manga) {
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.manga_migration)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					viewModel.manga.title,
					viewModel.manga.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}

	private fun buildReferenceSubtitle(reference: MangaAlternativeReference): String = buildList {
		val manga = reference.manga
		add(manga.source.getTitle(this@AlternativesActivity))
		(manga.source as? MangaParserSource)?.locale?.uppercase()?.let(::add)
		add(
			resources.getQuantityStringSafe(
				R.plurals.chapters,
				reference.chaptersCount,
				reference.chaptersCount,
			),
		)
	}.joinToString(" • ")

	private fun updateSummaryChips() {
		val options = viewModel.options.value
		val status = viewModel.searchStatus.value
		val resultCount = resources.getQuantityString(
			R.plurals.search_results_count,
			status.resultsCount,
			status.resultsCount,
		)
		val statusText = when {
			!status.isRunning -> resultCount
			status.totalSources == 0 -> getString(R.string.inline_preference_pattern, getString(R.string.preparing_search), resultCount)
			else -> getString(
				R.string.inline_preference_pattern,
				getString(R.string.searching_sources_progress, status.completedSources, status.totalSources),
				resultCount,
			)
		}
		val chips = buildList {
			add(
				ChipsView.ChipModel(
					title = options.query,
					icon = appcompatR.drawable.abc_ic_search_api_material,
					isCloseable = options.query != viewModel.manga.title,
					data = SummaryChip.QUERY,
				),
			)
			add(
				ChipsView.ChipModel(
					title = statusText,
					isLoading = status.isRunning,
					data = SummaryChip.STATUS,
				),
			)
			add(
				ChipsView.ChipModel(
					titleResId = options.sourceScope.titleRes,
					icon = R.drawable.ic_manga_source,
					isCloseable = options.sourceScope != AlternativeSourceScope.ENABLED,
					data = SummaryChip.SOURCE_SCOPE,
				),
			)
			if (options.sameLanguageOnly) add(
				ChipsView.ChipModel(
					titleResId = R.string.same_language,
					icon = R.drawable.ic_language,
					isCloseable = true,
					data = SummaryChip.LANGUAGE,
				),
			)
			if (options.sameContentTypeOnly) add(
				ChipsView.ChipModel(
					titleResId = R.string.same_content_type,
					isCloseable = true,
					data = SummaryChip.CONTENT_TYPE,
				),
			)
			if (options.hideNoChapters) add(
				ChipsView.ChipModel(
					titleResId = R.string.has_chapters,
					icon = R.drawable.ic_current_chapter,
					isCloseable = true,
					data = SummaryChip.HAS_CHAPTERS,
				),
			)
			add(
				ChipsView.ChipModel(
					titleResId = options.sortOrder.titleRes,
					icon = R.drawable.ic_sort_desc,
					isCloseable = options.sortOrder != AlternativeSortOrder.BEST_MATCH,
					data = SummaryChip.SORT,
				),
			)
			if (!options.isDefaultFor(viewModel.manga.title)) add(
				ChipsView.ChipModel(
					titleResId = R.string.reset,
					icon = R.drawable.ic_clear_all,
					data = SummaryChip.RESET,
				),
			)
		}
		viewBinding.chipsSummary.setChips(chips)
	}

	private fun updateSearchProgress(status: AlternativeSearchStatus) {
		with(viewBinding.progressSources) {
			if (status.totalSources > 0) {
				isIndeterminate = false
				max = status.totalSources
				setProgressCompat(status.completedSources, true)
			} else {
				isIndeterminate = true
			}
			isVisible = status.isRunning
		}
	}

	private val AlternativeSourceScope.titleRes: Int
		get() = when (this) {
			AlternativeSourceScope.ENABLED -> R.string.enabled_sources
			AlternativeSourceScope.PINNED -> R.string.pinned_sources
			AlternativeSourceScope.ALL -> R.string.all_sources
		}

	private val AlternativeSortOrder.titleRes: Int
		get() = when (this) {
			AlternativeSortOrder.BEST_MATCH -> R.string.best_match
			AlternativeSortOrder.MOST_CHAPTERS -> R.string.most_chapters
			AlternativeSortOrder.CLOSEST_CHAPTER_COUNT -> R.string.closest_chapter_count
			AlternativeSortOrder.SOURCE_PRIORITY -> R.string.source_priority
		}

	private enum class SummaryChip {
		QUERY,
		SOURCE_SCOPE,
		LANGUAGE,
		CONTENT_TYPE,
		HAS_CHAPTERS,
		SORT,
		STATUS,
		RESET,
	}
}
