package org.koitharu.kotatsu.list.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

// A separate overload rather than a defaulted parameter: callers pass the click listener as a trailing lambda,
// which would otherwise bind to the new last parameter.
fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: OnListItemClickListener<MangaListModel>,
) = mangaGridItemAD(sizeResolver, clickListener, null)

fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: OnListItemClickListener<MangaListModel>,
	onDismissClick: ((MangaListModel, View) -> Unit)?,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	// The theme decides which of the two title views is visible: under the cover by default, over the
	// artwork on Expressive. Size the one actually being drawn.
	val titleView = if (binding.textViewTitleOverlay.isVisible) {
		binding.textViewTitleOverlay
	} else {
		binding.textViewTitle
	}
	sizeResolver.attachToView(itemView, titleView, binding.progressView)
	// Opt-in: only Explore passes a callback. The button consumes its own touches, so the card's click
	// and long-press never see them.
	binding.buttonDismiss.isVisible = onDismissClick != null
	if (onDismissClick != null) {
		binding.buttonDismiss.setTooltipCompat(R.string.not_interested)
		binding.buttonDismiss.setOnClickListener { onDismissClick(item, it) }
	}

	// Backdrop for the overlaid title: a short fade of a heavily darkened theme accent, tall enough
	// for two lines, with its bottom corners following the cover's own rounding. Only the theme that
	// draws titles over the artwork shows it, which is the same condition that reveals that title.
	val isTitleOverCover = binding.textViewTitleOverlay.isVisible
	binding.viewScrim.isVisible = isTitleOverCover
	// Spacing between cards, per theme. The layout's own margin and padding are both replaced here so
	// there is a single source for the inset rather than two that have to agree.
	val gridSpacing = context.getThemeDimensionPixelOffset(R.attr.mangaGridSpacing)
	itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
		setMargins(0, 0, 0, 0)
	}
	// Nothing sits below the cover once the title is on the artwork, so that edge needs no inset.
	itemView.setPadding(gridSpacing, gridSpacing, gridSpacing, if (isTitleOverCover) 0 else gridSpacing)
	if (isTitleOverCover) {
		val darkAccent = ColorUtils.blendARGB(
			context.getThemeColor(appcompatR.attr.colorPrimary),
			Color.BLACK,
			SCRIM_DARKEN,
		)
		binding.viewScrim.background = GradientDrawable(
			GradientDrawable.Orientation.BOTTOM_TOP,
			intArrayOf(
				ColorUtils.setAlphaComponent(darkAccent, 0xF2),
				ColorUtils.setAlphaComponent(darkAccent, 0xC0),
				ColorUtils.setAlphaComponent(darkAccent, 0x00),
			),
		).apply {
			val radius = SCRIM_CORNER_RADIUS_DP * context.resources.displayMetrics.density
			cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
		}
	}

	bind { payloads ->
		itemView.setTooltipCompat(item.getSummary(context))
		titleView.text = item.title
		binding.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		// The dismiss button takes the corner the progress ring would use
		if (onDismissClick != null) binding.progressView.isVisible = false
		with(binding.iconsView) {
			clearIcons()
			if (item.isPinned) addIcon(R.drawable.ic_pin_small)
			if (item.isSaved) addIcon(R.drawable.ic_storage)
			isVisible = iconsCount > 0
		}
		// Favourite moved out of the top-left icon strip and into the status cluster opposite it.
		binding.imageViewFavourite.isVisible = item.isFavorite
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
	}
}

private const val SCRIM_DARKEN = 0.78f
private const val SCRIM_CORNER_RADIUS_DP = 12f
