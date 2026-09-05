package org.koitharu.kotatsu.alternatives.ui

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.annotation.UiContext
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.databinding.DialogMangaUnavailableBinding
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Prompt shown when a manga the user just opened is gone from its source (404 / removed), offering to
 * look for the same title elsewhere instead of leaving them with a dead-end error.
 */
class MangaUnavailableDialog private constructor(
	private val delegate: AlertDialog,
	private val binding: DialogMangaUnavailableBinding,
	private val manga: Manga,
) : DialogInterface by delegate {

	val isShowing: Boolean
		get() = delegate.isShowing

	fun show() {
		delegate.show()
		// After show(), so the view is attached and Coil can bind to the dialog's lifecycle.
		binding.imageViewCover.setImageAsync(manga.coverUrl, manga)
	}

	class Builder(@UiContext context: Context, private val manga: Manga) {

		private val binding = DialogMangaUnavailableBinding.inflate(LayoutInflater.from(context))

		private val delegate = MaterialAlertDialogBuilder(context)
			.setView(binding.root)

		init {
			binding.textViewTitle.setText(R.string.manga_unavailable_title)
			val sourceTitle = if (manga.isLocal) null else manga.source.getTitle(context)
			binding.textViewMessage.text = if (sourceTitle != null) {
				context.getString(R.string.manga_unavailable_message, manga.title, sourceTitle)
			} else {
				context.getString(R.string.manga_unavailable_message_no_source, manga.title)
			}
		}

		fun setOnAlternativesClickListener(listener: Runnable) = apply {
			binding.button1.setOnClickListener {
				val dialog = binding.root.tag as DialogInterface
				dialog.dismiss()
				listener.run()
			}
		}

		fun create(): MangaUnavailableDialog {
			val dialog = delegate.create()
			binding.root.tag = dialog
			binding.buttonClose.setOnClickListener { dialog.dismiss() }
			return MangaUnavailableDialog(dialog, binding, manga)
		}
	}
}
