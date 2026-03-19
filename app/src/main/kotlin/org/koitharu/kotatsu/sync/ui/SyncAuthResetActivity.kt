package org.koitharu.kotatsu.sync.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentResultListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.DefaultTextWatcher
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivitySyncAuthResetBinding

private const val PASSWORD_MIN_LENGTH = 4

@AndroidEntryPoint
class SyncAuthResetActivity : BaseActivity<ActivitySyncAuthResetBinding>(), View.OnClickListener, FragmentResultListener,
	DefaultTextWatcher {

	private val viewModel by viewModels<SyncAuthResetViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySyncAuthResetBinding.inflate(layoutInflater))
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.editPassword.addTextChangedListener(this)
		viewBinding.editPasswordConfirm.addTextChangedListener(this)

		viewModel.onPasswordResetSucceeded.observeEvent(this, ::onPasswordResetSucceeded)
		viewModel.onError.observeEvent(this, ::onError)
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)

		supportFragmentManager.setFragmentResultListener(SyncHostDialogFragment.REQUEST_KEY, this, this)

		if(intent.action != Intent.ACTION_VIEW || intent.data?.host != HOST_RESET_PASSWORD) {
			onNoTokenProvided()
			return
		}

		val baseUrl = intent.data?.getQueryParameter("base_url")?.trim()
		val token = intent.data?.getQueryParameter("token")?.trim()
		if(token == null || token.isEmpty() || baseUrl == null || baseUrl.isEmpty()) {
			onNoTokenProvided()
			return
		}

		viewModel.syncURL.value = baseUrl
		viewModel.resetToken.value = token
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		val basePadding = viewBinding.layoutContent.paddingBottom
		viewBinding.layoutContent.updatePadding(
			left = barsInsets.left + basePadding,
			right = barsInsets.right + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> {
				setResult(RESULT_CANCELED)
				finish()
			}

			R.id.button_done -> {
				viewModel.resetPassword(viewBinding.editPassword.text.toString())
			}
		}
	}

	override fun onFragmentResult(requestKey: String, result: Bundle) {
		val syncURL = result.getString(SyncHostDialogFragment.KEY_SYNC_URL) ?: return
		viewModel.syncURL.value = syncURL
	}

	override fun afterTextChanged(s: Editable?) {
		val isLoading = viewModel.isLoading.value
		val password = viewBinding.editPassword.text?.toString()
		val passwordConfirm = viewBinding.editPasswordConfirm.text?.toString()
		viewBinding.buttonDone.isEnabled = !isLoading && password != null && password.length >= PASSWORD_MIN_LENGTH && password == passwordConfirm
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		with(viewBinding) {
			progressBar.isInvisible = !isLoading
			editPassword.isEnabled = !isLoading
			editPasswordConfirm.isEnabled = !isLoading
		}
		afterTextChanged(null)
	}

	private fun onError(error: Throwable) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error)
			.setMessage(error.getDisplayMessage(resources))
			.setNegativeButton(R.string.close, null)
			.show()
	}

	private fun onPasswordResetSucceeded(unit: Unit) {
		Toast.makeText(this, getString(R.string.password_reset), Toast.LENGTH_SHORT)
			.show()
		setResult(RESULT_OK)
		super.finishAfterTransition()
	}

	private fun onNoTokenProvided() {
		Toast.makeText(this, getString(R.string.no_reset_token), Toast.LENGTH_SHORT)
			.show()
		super.finishAfterTransition()
	}

	companion object {
		private const val HOST_RESET_PASSWORD = "reset-password"
	}
}
