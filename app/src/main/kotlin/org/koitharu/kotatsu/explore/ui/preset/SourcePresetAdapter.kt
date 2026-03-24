package org.koitharu.kotatsu.explore.ui.preset

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemPresetBinding
import org.koitharu.kotatsu.explore.data.SourcePreset

class SourcePresetAdapter(
	private val listener: SourcePresetListener,
	private val sourceCounter: (SourcePreset) -> Int,
) : ListAdapter<SourcePreset, SourcePresetAdapter.PresetViewHolder>(PresetDiffCallback) {

	var activePresetId: Long = 0L
		set(value) {
			if (field != value) {
				field = value
				notifyDataSetChanged()
			}
		}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
		val binding = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return PresetViewHolder(binding)
	}

	override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
		holder.bind(getItem(position))
	}

	inner class PresetViewHolder(
		private val binding: ItemPresetBinding,
	) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

		private var boundItem: SourcePreset? = null

		init {
			binding.root.setOnClickListener(this)
			binding.buttonEdit.setOnClickListener(this)
			binding.buttonDelete.setOnClickListener(this)
		}

		fun bind(item: SourcePreset) {
			boundItem = item
			binding.textViewTitle.text = item.title
			val context = binding.root.context
			val langNames = item.languages.map { it.toLocale().getDisplayName(context) }
			val count = sourceCounter(item)
			val countText = context.resources.getQuantityString(R.plurals.sources_count, count, count)
			val subtitle = if (langNames.isNotEmpty()) {
				"${langNames.joinToString()} — $countText"
			} else {
				countText
			}
			binding.textViewSubtitle.text = subtitle
			binding.radio.isChecked = item.id == activePresetId
		}

		override fun onClick(v: View) {
			val item = boundItem ?: return
			when (v) {
				binding.buttonEdit -> listener.onEditPreset(item)
				binding.buttonDelete -> listener.onDeletePreset(item)
				else -> listener.onPresetClick(item)
			}
		}
	}

	private object PresetDiffCallback : DiffUtil.ItemCallback<SourcePreset>() {
		override fun areItemsTheSame(oldItem: SourcePreset, newItem: SourcePreset) = oldItem.id == newItem.id
		override fun areContentsTheSame(oldItem: SourcePreset, newItem: SourcePreset) = oldItem == newItem
	}
}

interface SourcePresetListener {
	fun onPresetClick(preset: SourcePreset)
	fun onEditPreset(preset: SourcePreset)
	fun onDeletePreset(preset: SourcePreset)
}
