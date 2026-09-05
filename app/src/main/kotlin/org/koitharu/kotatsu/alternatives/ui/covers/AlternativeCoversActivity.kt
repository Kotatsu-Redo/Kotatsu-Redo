package org.koitharu.kotatsu.alternatives.ui.covers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ActivityAlternativeCoversBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Covers for the same title from other sources, to be compared against the current one and picked.
 *
 * A full screen rather than a sheet: this searches every enabled source, so it needs room for a grid
 * and for an honest progress readout while it works.
 */
@AndroidEntryPoint
class AlternativeCoversActivity :
	BaseActivity<ActivityAlternativeCoversBinding>(),
	OnListItemClickListener<AlternativeCoverModel> {

	private val viewModel by viewModels<AlternativeCoversViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativeCoversBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		title = getString(R.string.alternative_covers)

		val adapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_GRID, alternativeCoverAD(this))
		viewBinding.recyclerView.adapter = adapter
		viewBinding.buttonApply.setOnClickListener { applySelection() }

		viewModel.covers.observe(this) { covers ->
			adapter.items = covers
			updateEmptyState()
		}
		viewModel.selectedUrl.observe(this) { url ->
			val selected = viewModel.covers.value.firstOrNull { it.coverUrl == url }
			viewBinding.imageViewPreview.setImageAsync(url, selected?.manga ?: viewModel.manga)
			viewBinding.textViewStatus.text = when {
				selected == null -> null
				selected.isCurrent -> getString(R.string.current_cover)
				else -> selected.source.getTitle(this)
			}
			// Re-picking the cover already in use would be a no-op write.
			viewBinding.buttonApply.isEnabled = selected != null && !selected.isCurrent
		}
		viewModel.progress.observe(this) { (completed, total) ->
			viewBinding.progress.max = total.coerceAtLeast(1)
			viewBinding.progress.setProgressCompat(completed, true)
			supportActionBar?.subtitle = if (total > 0) {
				getString(R.string.alternative_covers_progress, completed, total)
			} else {
				null
			}
		}
		viewModel.isRunning.observe(this) { isRunning ->
			viewBinding.progress.isVisible = isRunning
			if (!isRunning) {
				supportActionBar?.subtitle = null
			}
			updateEmptyState()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding.root.updatePadding(left = barsInsets.left, top = barsInsets.top, right = barsInsets.right)
		viewBinding.root.updatePadding(bottom = barsInsets.bottom)
		return insets.consumeAll(typeMask)
	}

	private fun updateEmptyState() {
		// Count only alternatives: the current cover leads the list, but is absent entirely when the
		// manga has no cover of its own, so a size check would misreport one found cover as none.
		val showEmpty = viewModel.covers.value.none { !it.isCurrent } && !viewModel.isRunning.value
		viewBinding.textViewEmpty.isVisible = showEmpty
		viewBinding.recyclerView.isVisible = !showEmpty
	}

	/** Tapping the carousel only previews; applying is a deliberate second step. */
	override fun onItemClick(item: AlternativeCoverModel, view: View) {
		viewModel.select(item.coverUrl)
	}

	private fun applySelection() {
		val url = viewModel.selectedUrl.value ?: return
		// Whatever is still in flight is no longer worth fetching.
		viewModel.cancelSearch()
		setResult(RESULT_OK, Intent().putExtra(EXTRA_COVER_URL, url))
		finish()
	}

	class Contract : ActivityResultContract<Manga, String?>() {

		override fun createIntent(context: Context, input: Manga) =
			Intent(context, AlternativeCoversActivity::class.java)
				.putExtra(AppRouter.KEY_MANGA, ParcelableManga(input, withDescription = false))

		override fun parseResult(resultCode: Int, intent: Intent?): String? = if (resultCode == RESULT_OK) {
			intent?.getStringExtra(EXTRA_COVER_URL)
		} else {
			null
		}
	}

	private companion object {

		const val EXTRA_COVER_URL = "cover_url"
	}
}
