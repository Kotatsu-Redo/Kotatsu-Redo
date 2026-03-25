package org.koitharu.kotatsu.explore.ui.preset

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import androidx.core.graphics.Insets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.databinding.ActivityPresetEditBinding
import org.koitharu.kotatsu.explore.data.SourcePreset

@AndroidEntryPoint
class SourcePresetEditActivity :
	BaseActivity<ActivityPresetEditBinding>(),
	View.OnClickListener,
	DefaultTextWatcher {

	private val viewModel by viewModels<SourcePresetEditViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityPresetEditBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.editName.addTextChangedListener(this)
		afterTextChanged(viewBinding.editName.text)

		initLanguageChips()

		viewModel.onSaved.observeEvent(this) { finishAfterTransition() }
		viewModel.preset.observe(this, ::onPresetChanged)
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, ::onError)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.NONE)
			.build()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> {
				val selectedLanguages = getSelectedLanguages()
				viewModel.save(
					title = viewBinding.editName.text?.toString()?.trim().orEmpty(),
					selectedLanguages = selectedLanguages,
				)
			}
		}
	}

	override fun afterTextChanged(s: Editable?) {
		viewBinding.buttonDone.isEnabled = !s.isNullOrBlank() && !viewModel.isLoading.value
	}

	private fun initLanguageChips() {
		val chipGroup = viewBinding.chipGroupLanguages
		chipGroup.removeAllViews()
		val sortedLocales = viewModel.allLocales.sortedBy { it.toLocale().getDisplayName(this) }
		for (locale in sortedLocales) {
			val chip = Chip(this).apply {
				text = locale.toLocale().getDisplayName(this@SourcePresetEditActivity)
				isCheckable = true
				tag = locale
			}
			chipGroup.addView(chip)
		}
	}

	private fun onPresetChanged(preset: SourcePreset?) {
		setTitle(if (preset == null) R.string.create_preset else R.string.edit_preset)
		if (preset == null) return
		viewBinding.editName.setText(preset.title)
		val chipGroup = viewBinding.chipGroupLanguages
		for (i in 0 until chipGroup.childCount) {
			val chip = chipGroup.getChildAt(i) as? Chip ?: continue
			chip.isChecked = chip.tag as? String in preset.languages
		}
	}

	private fun getSelectedLanguages(): Set<String> {
		val chipGroup = viewBinding.chipGroupLanguages
		val result = LinkedHashSet<String>()
		for (i in 0 until chipGroup.childCount) {
			val chip = chipGroup.getChildAt(i) as? Chip ?: continue
			if (chip.isChecked) {
				val tag = chip.tag as? String
				if (tag != null) result.add(tag)
			}
		}
		return result
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.text = e.getDisplayMessage(resources)
		viewBinding.textViewError.isVisible = true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		viewBinding.buttonDone.isEnabled = !isLoading && !viewBinding.editName.text.isNullOrBlank()
		viewBinding.editName.isEnabled = !isLoading
		if (isLoading) {
			viewBinding.textViewError.isVisible = false
		}
	}

	companion object {

		fun newIntent(context: Context, presetId: Long = SourcePresetEditViewModel.NO_ID): Intent {
			return Intent(context, SourcePresetEditActivity::class.java)
				.putExtra(AppRouter.KEY_ID, presetId)
		}
	}
}
