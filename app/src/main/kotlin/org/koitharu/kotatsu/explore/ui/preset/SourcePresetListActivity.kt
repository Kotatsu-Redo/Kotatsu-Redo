package org.koitharu.kotatsu.explore.ui.preset

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.ui.BaseActivity
import androidx.core.graphics.Insets
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent

import org.koitharu.kotatsu.databinding.ActivityPresetListBinding
import org.koitharu.kotatsu.explore.data.SourcePreset
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration

@AndroidEntryPoint
class SourcePresetListActivity :
	BaseActivity<ActivityPresetListBinding>(),
	View.OnClickListener,
	SourcePresetListener {

	private val viewModel by viewModels<SourcePresetListViewModel>()
	private lateinit var adapter: SourcePresetAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityPresetListBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)

		adapter = SourcePresetAdapter(this, viewModel::countSourcesForPreset)
		adapter.activePresetId = viewModel.activePresetId
		viewBinding.recyclerView.adapter = adapter
		viewBinding.recyclerView.setHasFixedSize(true)
		viewBinding.recyclerView.addItemDecoration(TypedListSpacingDecoration(this, false))
		viewBinding.fabAdd.setOnClickListener(this)

		viewModel.presets.observe(this) { presets ->
			adapter.submitList(presets)
		}
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.onPresetDeleted.observeEvent(this) {
			adapter.activePresetId = viewModel.activePresetId
			Snackbar.make(viewBinding.recyclerView, R.string.preset_deleted, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
		viewBinding.fabAdd.updateLayoutParams<MarginLayoutParams> {
			marginEnd = topMargin + barsInsets.end(v)
			bottomMargin = topMargin + barsInsets.bottom
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.NONE)
			.build()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.fab_add -> startActivity(SourcePresetEditActivity.newIntent(this))
		}
	}

	override fun onPresetClick(preset: SourcePreset) {
		val newId = if (preset.id == viewModel.activePresetId) 0L else preset.id
		viewModel.setActivePreset(newId)
		adapter.activePresetId = newId
		val message = if (newId != 0L) R.string.preset_activated else R.string.preset_deactivated
		Snackbar.make(viewBinding.recyclerView, message, Snackbar.LENGTH_SHORT).show()
	}

	override fun onEditPreset(preset: SourcePreset) {
		startActivity(SourcePresetEditActivity.newIntent(this, preset.id))
	}

	override fun onDeletePreset(preset: SourcePreset) {
		MaterialAlertDialogBuilder(this)
			.setTitle(preset.title)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				viewModel.deletePreset(preset.id)
			}
			.show()
	}
}
