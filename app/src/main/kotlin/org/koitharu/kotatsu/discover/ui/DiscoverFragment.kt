package org.koitharu.kotatsu.discover.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.slider.TickVisibilityMode
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.core.ui.util.SpanSizeResolver
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.progress.IntPercentLabelFormatter
import org.koitharu.kotatsu.databinding.FragmentDiscoverBinding
import org.koitharu.kotatsu.discover.domain.DiscoverWindow
import org.koitharu.kotatsu.explore.ui.SourcesActivity
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.emptyHintAD
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.adapter.tipAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.list.ui.size.StaticItemSizeResolver
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import kotlin.math.roundToInt

private val TYPES = listOf(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
private const val MAX_POOLED_COVERS = 24

/**
 * The Explore tab: what to read next, whichever source it happens to be on. Two screens to swipe between, and
 * one row of tags above them that says which one this is and narrows down both.
 */
@AndroidEntryPoint
class DiscoverFragment :
	BaseFragment<FragmentDiscoverBinding>(),
	RecyclerViewOwner,
	ListStateHolderListener,
	OnListItemClickListener<MangaListModel>,
	ChipsView.OnChipClickListener,
	TipView.OnButtonClickListener {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<DiscoverViewModel>()
	private var pages: List<RecyclerView> = emptyList()

	/** One for each page, to give them all a new width when the size of the grid is changed. */
	private val columnResolvers = ArrayList<SpanSizeResolver>(DiscoverTab.entries.size)

	override val recyclerView: RecyclerView?
		get() = viewBinding?.pager?.currentItem?.let(pages::getOrNull)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentDiscoverBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentDiscoverBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		pages = DiscoverTab.entries.map { createPage(it) }
		with(binding.pager) {
			offscreenPageLimit = 1
			adapter = PagesAdapter(pages)
			setCurrentItem(viewModel.tab.value.ordinal, false)
			registerOnPageChangeCallback(
				object : ViewPager2.OnPageChangeCallback() {
					override fun onPageSelected(position: Int) {
						viewModel.setTab(DiscoverTab.entries[position])
						viewBinding?.scrollChips?.smoothScrollTo(0, 0)
					}
				},
			)
		}
		binding.chips.onChipClickListener = this
		viewModel.chips.observe(viewLifecycleOwner, ::showChips)
		settings.observeAsFlow(AppSettings.KEY_GRID_SIZE_DISCOVER) { gridSizeDiscover }.observe(viewLifecycleOwner) {
			val width = getColumnWidth()
			columnResolvers.forEach { it.minItemWidth = width }
		}
		addMenuProvider(DiscoverMenuProvider())
	}

	override fun onDestroyView() {
		requireViewBinding().pager.adapter = null
		pages = emptyList()
		columnResolvers.clear()
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		val padding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		pages.forEach { it.setPadding(bars.left + padding, padding, bars.right + padding, bars.bottom + padding) }
		viewBinding?.scrollChips?.updatePadding(left = bars.left + padding, right = bars.right + padding)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onResume() {
		super.onResume()
		viewModel.onScreenResumed()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		router.openDetails(item.toMangaWithOverride())
	}

	override fun onItemLongClick(item: MangaListModel, view: View): Boolean {
		offerDismiss(item.manga, view)
		return true
	}

	private fun offerDismiss(manga: Manga, anchor: View) {
		val popup = PopupMenu(anchor.context, anchor)
		popup.menu.add(R.string.not_interested).setOnMenuItemClickListener {
			dismissWithUndo(manga)
			true
		}
		popup.show()
	}

	// Shared by the long-press popup and the button on each cover
	private fun dismissWithUndo(manga: Manga) {
		val key = viewModel.dismiss(manga)
		val host = viewBinding?.root ?: return
		val snackbar = Snackbar.make(host, R.string.removed_from_suggestions, Snackbar.LENGTH_LONG)
		(host.context.findActivity() as? BottomNavOwner)?.let { snackbar.anchorView = it.bottomNav }
		snackbar.setAction(R.string.undo) { viewModel.undoDismiss(key) }
		snackbar.show()
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is DiscoverTab -> viewBinding?.pager?.setCurrentItem(data.ordinal, true)
			is DiscoverWindow -> viewModel.setWindow(data)
			is ContentType -> viewModel.toggleType(data)
			is String -> viewModel.toggleGenre(data)
		}
	}

	override fun onPrimaryButtonClick(tipView: TipView) = viewModel.closeTip()

	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onRetryClick(error: Throwable) = viewModel.refresh()

	override fun onEmptyActionClick() = router.openManageSources()

	private fun showChips(state: DiscoverChips) {
		val chips = buildList {
			DiscoverTab.entries.mapTo(this) { ChipsView.ChipModel(titleResId = it.titleRes, isChecked = it == state.tab, data = it) }
			if (state.tab == DiscoverTab.TOP) {
				DiscoverWindow.entries.mapTo(this) { ChipsView.ChipModel(titleResId = it.titleRes, isChecked = it == state.window, data = it) }
			}
			TYPES.mapTo(this) { ChipsView.ChipModel(titleResId = it.titleResId, isChecked = it == state.filter.type, data = it) }
			state.genres.mapTo(this) { ChipsView.ChipModel(title = it, isChecked = it == state.filter.genre, data = it) }
		}
		viewBinding?.chips?.setChips(chips)
	}

	private fun createPage(tab: DiscoverTab): RecyclerView {
		val rowSize = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width))
		val coverPool = RecyclerView.RecycledViewPool().apply {
			setMaxRecycledViews(ListItemType.MANGA_GRID.ordinal, MAX_POOLED_COVERS)
		}
		val gridSize = DynamicItemSizeResolver(
			resources = resources,
			lifecycleOwner = viewLifecycleOwner,
			settings = settings,
			adjustWidth = false,
			sizeKey = AppSettings.KEY_GRID_SIZE_DISCOVER,
			sizeOf = { it.gridSizeDiscover },
		)
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.DISCOVER_RANKED, discoverRankedAD({ router.openDetails(it) }, ::offerDismiss, ::dismissWithUndo))
			.addDelegate(ListItemType.DISCOVER_ROW, discoverRowAD(rowSize, coverPool, { router.openSuggestions() }, this) { item, _ -> dismissWithUndo(item.manga) })
			.addDelegate(ListItemType.FOOTER_LOADING, discoverPlaceholderAD())
			.addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(gridSize, this) { item, _ -> dismissWithUndo(item.manga) })
			.addDelegate(ListItemType.HEADER, listHeaderAD(null))
			.addDelegate(ListItemType.HINT_EMPTY, emptyHintAD(this))
			.addDelegate(ListItemType.TIP, tipAD(this))
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		val page = RecyclerView(requireContext())
		page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		page.clipToPadding = false
		page.isVerticalScrollBarEnabled = true
		page.adapter = listAdapter
		val grid = GridLayoutManager(page.context, 3)
		grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
			override fun getSpanSize(position: Int): Int = when (listAdapter.getItemViewType(position)) {
				ListItemType.MANGA_GRID.ordinal,
				ListItemType.DISCOVER_RANKED.ordinal,
				ListItemType.FOOTER_LOADING.ordinal,
					-> 1

				else -> grid.spanCount
			}
		}
		page.layoutManager = grid
		columnResolvers += SpanSizeResolver(page, getColumnWidth()).also { it.attach() }
		page.addItemDecoration(TypedListSpacingDecoration(page.context, false))
		val content = if (tab == DiscoverTab.FOR_YOU) viewModel.forYouContent else viewModel.topContent
		content.observe(viewLifecycleOwner, listAdapter)
		return page
	}

	/** As narrow as a column of covers may get: as many are shown as fit at that. */
	private fun getColumnWidth(): Int {
		return (resources.getDimension(R.dimen.preferred_grid_width) * settings.gridSizeDiscover / 100f).roundToInt()
	}

	/**
	 * Its own size, and not the library's: there was no way to change it at all, and five small covers across was
	 * what a phone set to show more on screen got. Changed as the slider moves, so the choice shows behind it.
	 */
	private fun showGridSizeDialog() {
		val context = requireContext()
		val slider = Slider(context).apply {
			valueFrom = 50f
			valueTo = 150f
			stepSize = 5f
			tickVisibilityMode = TickVisibilityMode.TICK_VISIBILITY_HIDDEN
			labelBehavior = LabelFormatter.LABEL_FLOATING
			setLabelFormatter(IntPercentLabelFormatter(context))
			setValueRounded(settings.gridSizeDiscover.toFloat())
			addOnChangeListener { _, value, fromUser ->
				if (fromUser) {
					settings.gridSizeDiscover = value.toInt()
				}
			}
		}
		val padding = resources.getDimensionPixelOffset(R.dimen.margin_normal)
		val container = FrameLayout(context).apply {
			setPadding(padding, padding, padding, 0)
			addView(slider)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.grid_size)
			.setView(container)
			.setPositiveButton(R.string.done, null)
			.show()
	}

	/** Hands the pager views that already exist: each keeps its own list, and they stay alive side by side. */
	private class PagesAdapter(private val pages: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

		override fun getItemCount() = pages.size

		override fun getItemViewType(position: Int) = position

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
			object : RecyclerView.ViewHolder(pages[viewType]) {}

		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
	}

	private inner class DiscoverMenuProvider : MenuProvider {

		override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
			menuInflater.inflate(R.menu.opt_discover, menu)
		}

		override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
			R.id.action_by_source -> {
				startActivity(Intent(requireContext(), SourcesActivity::class.java))
				true
			}

			R.id.action_grid_size -> {
				showGridSizeDialog()
				true
			}

			R.id.action_refresh -> {
				viewModel.refresh()
				true
			}

			R.id.action_show_dismissed -> {
				viewModel.clearDismissed()
				true
			}

			else -> false
		}
	}
}
