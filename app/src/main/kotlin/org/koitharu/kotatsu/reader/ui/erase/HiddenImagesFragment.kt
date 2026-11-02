package org.koitharu.kotatsu.reader.ui.erase

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentSettingsSourcesBinding
import org.koitharu.kotatsu.databinding.ItemHiddenImageBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.reader.data.erase.HiddenImageEntity
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import javax.inject.Inject

private data class HiddenImageItem(val entity: HiddenImageEntity) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is HiddenImageItem && other.entity.id == entity.id
}

private data object NothingHidden : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is NothingHidden
}

@HiltViewModel
class HiddenImagesViewModel @Inject constructor(
	private val repository: HiddenImagesRepository,
) : BaseViewModel() {

	val content = repository.observeAll()
		.map { list -> list.ifEmpty { null }?.map<HiddenImageEntity, ListModel> { HiddenImageItem(it) } ?: listOf(NothingHidden) }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), emptyList())

	fun showAgain(id: Long) {
		launchJob(Dispatchers.Default) {
			repository.delete(id)
		}
	}

	/** Having tapped the wrong one of these, nobody should have to go and find that image again to erase it. */
	fun hideAgain(entity: HiddenImageEntity) {
		launchJob(Dispatchers.Default) {
			repository.restore(entity)
		}
	}
}

/**
 * What makes "never again" safe to offer: nothing erased, by accident or by a false match, is out of reach.
 */
@AndroidEntryPoint
class HiddenImagesFragment : BaseFragment<FragmentSettingsSourcesBinding>(), RecyclerViewOwner {

	private val viewModel by viewModels<HiddenImagesViewModel>()

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentSettingsSourcesBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentSettingsSourcesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_LIST, hiddenImageAD { entity ->
				viewModel.showAgain(entity.id)
				Snackbar.make(binding.root, R.string.hidden_images_shown, Snackbar.LENGTH_LONG)
					.setAction(R.string.undo) { viewModel.hideAgain(entity) }
					.show()
			})
			.addDelegate(ListItemType.HINT_EMPTY, nothingHiddenAD())
		binding.recyclerView.adapter = adapter
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		v.setPadding(bars.left, 0, bars.right, bars.bottom)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onResume() {
		super.onResume()
		activity?.setTitle(R.string.hidden_images)
	}
}

private fun hiddenImageAD(onShowAgain: (HiddenImageEntity) -> Unit) =
	adapterDelegateViewBinding<HiddenImageItem, ListModel, ItemHiddenImageBinding>(
		{ inflater, parent -> ItemHiddenImageBinding.inflate(inflater, parent, false) },
	) {
		binding.buttonShow.setOnClickListener { onShowAgain(item.entity) }
		bind {
			val entity = item.entity
			val thumbnail = entity.thumbnail
			binding.imageViewThumbnail.setImageBitmap(
				thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
			)
			binding.textViewTitle.text = entity.mangaTitle
			binding.textViewSubtitle.text = DateUtils.getRelativeTimeSpanString(entity.createdAt)
		}
	}

private fun nothingHiddenAD() = adapterDelegate<NothingHidden, ListModel>(R.layout.item_hidden_images_empty) {
	(itemView as TextView).setText(R.string.hidden_images_empty)
}
