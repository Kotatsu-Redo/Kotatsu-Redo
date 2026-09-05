package org.koitharu.kotatsu.details.ui

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.ui.MangaUnavailableDialog
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.exceptions.resolve.ErrorObserver
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.findCloudFlareException
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isContentNotFound
import org.koitharu.kotatsu.core.util.ext.isNetworkError
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.Manga

class DetailsErrorObserver(
	override val activity: DetailsActivity,
	private val viewModel: DetailsViewModel,
	private val resolver: ExceptionResolver?,
) : ErrorObserver(
	activity.viewBinding.scrollView, null, resolver,
	{ isResolved ->
		if (isResolved) {
			viewModel.reload()
		}
	},
) {

	private var unavailableDialog: MangaUnavailableDialog? = null

	init {
		// The prompt is an activity-hosted dialog, so it has to go down with the activity.
		activity.lifecycle.addObserver(
			object : DefaultLifecycleObserver {
				override fun onDestroy(owner: LifecycleOwner) {
					unavailableDialog?.dismiss()
					unavailableDialog = null
				}
			},
		)
	}

	override suspend fun emit(value: Throwable) {
		// Manga details is an explicit user action ("opened a manga"), so auto-resolve is appropriate.
		// If the per-source toggle disables it, or auto-resolve doesn't succeed, fall back to the
		// standard snackbar with the "Solve" action below.
		val cf = value.findCloudFlareException()
		if (cf is CloudFlareProtectedException && resolver != null) {
			val autoDisabled = SourceSettings(host.context, cf.source).isCaptchaAutoResolveDisabled
			if (!autoDisabled) {
				val resolved = resolver.resolve(cf, tryAutoResolve = true)
				if (resolved) {
					viewModel.reload()
					return
				}
			}
		}
		// A title that is gone from its source is a dead end, not something a one-line snackbar can
		// help with, so ask up front whether to go looking for it on another source.
		val missingManga = viewModel.getMangaOrNull()?.takeIf { !it.isLocal && value.isContentNotFound() }
		if (missingManga != null && showUnavailableDialog(missingManga)) {
			return
		}
		val snackbar = Snackbar.make(host, value.getDisplayMessage(host.context.resources), Snackbar.LENGTH_SHORT)
		snackbar.setAnchorView(activity.viewBinding.containerBottomSheet)
		if (value is NotFoundException || value is UnsupportedSourceException) {
			snackbar.duration = Snackbar.LENGTH_INDEFINITE
		}
		when {
			canResolve(value) -> {
				snackbar.setAction(ExceptionResolver.getResolveStringId(value)) {
					resolve(value)
				}
			}

			value is ParseException -> {
				val router = router()
				if (router != null && value.isSerializable()) {
					snackbar.setAction(R.string.details) {
						router.showErrorDialog(value)
					}
				}
			}

			value.isNetworkError() -> {
				snackbar.setAction(R.string.try_again) {
					viewModel.reload()
				}
			}
		}
		snackbar.show()
	}

	/**
	 * @return `true` if the prompt is on screen and the caller should not show anything else. A dialog
	 * that is already up is left alone: reloads re-emit the same error and stacking dialogs on top of
	 * each other would trap the user behind a pile of them.
	 */
	private fun showUnavailableDialog(manga: Manga): Boolean {
		if (unavailableDialog?.isShowing == true) {
			return true
		}
		if (activity.isDestroyed || activity.isFinishing) {
			return false
		}
		val router = activity.router
		unavailableDialog = MangaUnavailableDialog.Builder(activity, manga)
			.setOnAlternativesClickListener { router.openAlternatives(manga) }
			.create()
			.also { it.show() }
		return true
	}
}
