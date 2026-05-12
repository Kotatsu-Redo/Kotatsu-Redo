package org.koitharu.kotatsu.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.ItemLoadingStateBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState

fun loadingStateAD() = adapterDelegateViewBinding<LoadingState, ListModel, ItemLoadingStateBinding>(
	{ inflater, parent -> ItemLoadingStateBinding.inflate(inflater, parent, false) },
) {

	bind {
		binding.textViewMessage.setTextAndVisible(item.textResId)
	}
}
