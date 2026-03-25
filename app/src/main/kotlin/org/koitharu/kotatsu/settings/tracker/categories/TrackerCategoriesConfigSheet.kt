package org.koitharu.kotatsu.settings.tracker.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import org.koitharu.kotatsu.core.util.ext.isRtl
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetBaseBinding

@AndroidEntryPoint
class TrackerCategoriesConfigSheet :
	BaseAdaptiveSheet<SheetBaseBinding>(),
	OnListItemClickListener<FavouriteCategory> {

	private val viewModel by viewModels<TrackerCategoriesConfigViewModel>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetBaseBinding {
		return SheetBaseBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetBaseBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.headerBar.setTitle(R.string.favourites_categories)
		val adapter = TrackerCategoriesConfigAdapter(this)
		binding.recyclerView.adapter = adapter

		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.recyclerView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		val src = insets.getInsets(typeMask)
		val start = false
		val top = false
		val end = false
		val bottom = true
		val left = if (if (v.isRtl) end else start) 0 else src.left
		val topVal = if (top) 0 else src.top
		val right = if (if (v.isRtl) start else end) 0 else src.right
		val bottomVal = if (bottom) 0 else src.bottom
		val newInsets = Insets.of(left, topVal, right, bottomVal)
		return WindowInsetsCompat.Builder(insets).setInsets(typeMask, newInsets).build()
	}

	override fun onItemClick(item: FavouriteCategory, view: View) {
		viewModel.toggleItem(item)
	}
}
