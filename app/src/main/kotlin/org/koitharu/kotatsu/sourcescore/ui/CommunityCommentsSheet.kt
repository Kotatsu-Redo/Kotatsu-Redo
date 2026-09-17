package org.koitharu.kotatsu.sourcescore.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetCommunityCommentsBinding
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.data.CommentDto

/**
 * The comment thread for one work, or one chapter of it.
 *
 * Its own sheet in its own package, launched from a single line on the details screen: that file is
 * upstream Kotatsu's and this fork has to keep rebasing onto it, so the community feature touches it
 * at exactly one point.
 */
@AndroidEntryPoint
class CommunityCommentsSheet : BaseAdaptiveSheet<SheetCommunityCommentsBinding>(), CommentActions {

	private val viewModel by viewModels<CommunityCommentsViewModel>()
	private var adapter: CommunityCommentAdapter? = null

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		SheetCommunityCommentsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetCommunityCommentsBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val workId = requireArguments().getString(ARG_WORK_ID) ?: return
		// Not `> 0`: a chapter id is a signed 64-bit hash and is negative about half the time, so that
		// test threw away every other chapter and quietly showed the whole work's comments instead.
		// Zero is the absent value here because it is what getLong's default returns.
		val chapterId = requireArguments().getLong(ARG_CHAPTER_ID, 0L).takeIf { it != 0L }

		adapter = CommunityCommentAdapter(this).also { binding.recyclerView.adapter = it }

		// Open, at full height, on the first frame. disableFitToContents() alone only sets the
		// height the sheet is allowed to reach; the behaviour still started collapsed, so the box was
		// off the bottom of the screen and you had to drag the sheet up before you could type.
		setExpanded(isExpanded = true, isLocked = false)

		binding.chipGroupSort.setOnCheckedStateChangeListener { _, checked ->
			viewModel.setSort(
				if (checked.firstOrNull() == R.id.chip_sort_new) {
					CommunityCommentsViewModel.SORT_NEW
				} else {
					CommunityCommentsViewModel.SORT_TOP
				},
			)
		}
		binding.buttonSend.setOnClickListener { send() }
		TooltipCompat.setTooltipText(binding.checkBoxSpoiler, getString(R.string.community_comment_spoiler))
		binding.checkBoxSpoiler.addOnCheckedChangeListener { button, checked ->
			// An open eye for an ordinary comment, a struck-through one for a comment that will be
			// hidden until asked for. The icon says what the comment will do, not what the button is.
			button.setIconResource(if (checked) R.drawable.ic_eye_off else R.drawable.ic_eye)
		}
		binding.checkBoxSpoiler.setIconResource(R.drawable.ic_eye)
		binding.buttonCancelCompose.setOnClickListener { viewModel.cancelCompose() }
		// The send button is the only thing that says the draft is too short until the server does,
		// which saves a round trip and a rejection for the commonest mistake there is.
		binding.editTextComment.doAfterTextChanged { updateSendEnabled() }

		viewModel.comments.observe(viewLifecycleOwner) { comments ->
			adapter?.submitList(comments)
			binding.textViewEmpty.isVisible = comments.isEmpty() && viewModel.isLoading.value != true
		}
		viewModel.total.observe(viewLifecycleOwner) { updateSubtitle() }
		viewModel.otherLanguages.observe(viewLifecycleOwner) { updateSubtitle() }
		viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
			binding.progressIndicator.isVisible = loading && adapter?.itemCount == 0
		}
		viewModel.isSending.observe(viewLifecycleOwner) { updateSendEnabled() }
		viewModel.rules.observe(viewLifecycleOwner) { updateSendEnabled() }
		viewModel.compose.observe(viewLifecycleOwner, ::onComposeTargetChanged)
		viewModel.rejection.observe(viewLifecycleOwner) { it?.let(::showRejection) }
		viewModel.loadFailed.observe(viewLifecycleOwner) { failed ->
			if (failed) {
				binding.textViewEmpty.setText(R.string.community_comments_unavailable)
				binding.textViewEmpty.isVisible = adapter?.itemCount == 0
			}
		}

		viewModel.start(workId, chapterId)
	}

	/**
	 * The compose box has to clear the navigation bar and the keyboard.
	 *
	 * Without this the send button sits under the gesture bar, which on a sheet whose whole purpose
	 * is typing is not a cosmetic problem.
	 */
	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		viewBinding?.root?.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onDestroyView() {
		adapter = null
		super.onDestroyView()
	}

	// -- actions -------------------------------------------------------------------------------

	override fun onVote(comment: CommentDto, value: Int) = viewModel.vote(comment, value)

	override fun onReply(comment: CommentDto) {
		viewModel.replyTo(comment)
		viewBinding?.editTextComment?.requestFocus()
	}

	override fun onEdit(comment: CommentDto) {
		viewModel.editOwn(comment)
		viewBinding?.editTextComment?.apply {
			setText(comment.body)
			setSelection(comment.body.length)
			requestFocus()
		}
	}

	override fun onDelete(comment: CommentDto) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_comment_delete)
			.setMessage(R.string.community_comment_delete_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.community_comment_delete) { _, _ -> viewModel.delete(comment) }
			.show()
	}

	private fun send() {
		val binding = viewBinding ?: return
		val body = binding.editTextComment.text?.toString()?.trim().orEmpty()
		if (body.isEmpty()) return
		lifecycleScope.launch {
			if (viewModel.send(body, binding.checkBoxSpoiler.isChecked)) {
				binding.editTextComment.setText("")
				// Cleared on success only: a rejected comment keeps everything the user chose, because
				// being told to rewrite and losing the rest is the worst possible combination.
				binding.checkBoxSpoiler.isChecked = false
			}
		}
	}

	// -- rendering -----------------------------------------------------------------------------

	private fun onComposeTargetChanged(target: ComposeTarget) {
		val binding = viewBinding ?: return
		val replying = target.comment != null
		binding.textViewReplying.isVisible = replying
		binding.buttonCancelCompose.isVisible = replying
		if (replying) {
			binding.textViewReplying.text = getString(
				if (target.isEditing) R.string.community_comment_editing else R.string.community_comment_replying_to,
				target.comment?.author.orEmpty(),
			)
		} else {
			// Nothing is being replied to or edited any more, so the box goes back to empty. Cancelling
			// an edit used to leave the comment's text sitting in the field, which then read as a draft
			// of a *new* comment - press send and you posted a duplicate of the one you just edited.
			binding.editTextComment.text?.clear()
			binding.checkBoxSpoiler.isChecked = false
		}
		updateSendEnabled()
	}

	private fun updateSendEnabled() {
		val binding = viewBinding ?: return
		val text = binding.editTextComment.text?.toString()?.trim().orEmpty()
		// Counted the way the server counts it, in characters a person would recognise, so a comment
		// of twenty emoji is not called too short here and then accepted there.
		val characters = if (text.isEmpty()) 0 else text.codePointCount(0, text.length)
		// The server's figure, not ours. It arrives with the comment page, so a limit changed on the
		// server takes effect the next time this sheet opens - no release, and never a send button
		// greyed out over a rule that has since been relaxed.
		val minimum = viewModel.rules.value?.minLength ?: MIN_LENGTH
		binding.buttonSend.isEnabled = characters >= minimum && viewModel.isSending.value != true
		binding.textInputLayoutComment.helperText = if (characters in 1 until minimum) {
			getString(R.string.community_comment_too_short, minimum)
		} else {
			null
		}
	}

	private fun updateSubtitle() {
		val binding = viewBinding ?: return
		val total = viewModel.total.value ?: 0
		// The count only. A "also 3 in other languages" tail used to hang off this, which meant the
		// heading changed length depending on who else had commented - and nobody opened the sheet to
		// find that out. Comments in other languages stay reachable by changing the app's language.
		binding.textViewSubtitle.text = getString(R.string.community_comments_heading_count, total)
	}

	/**
	 * Explains a rejection and offers the one-tap false-positive report.
	 *
	 * The term is the user's own word and arrives untranslated; everything around it is built here
	 * from string resources, because the server holds no translations by design.
	 */
	private fun showRejection(rejection: CommentRejection) {
		viewModel.consumeRejection()
		val error = rejection.error
		val message = when (error.error) {
			ApiErrorDto.FILTER_BLOCKED -> getString(R.string.community_comment_blocked, error.term.orEmpty())
			ApiErrorDto.TOO_SHORT -> getString(R.string.community_comment_too_short, error.min ?: MIN_LENGTH)
			ApiErrorDto.CHAIN_DEPTH_EXCEEDED -> getString(R.string.community_comment_chain_depth)
			ApiErrorDto.RATE_LIMITED -> {
				// The server sends how long to wait. Saying "a little while" when we were told
				// "57 minutes" is the difference between waiting and assuming the feature is broken.
				val minutes = error.retryAfterSeconds?.let { ((it + 59) / 60).toInt() }
				if (minutes != null && minutes > 0) {
					resources.getQuantityString(R.plurals.community_comment_rate_limited_in, minutes, minutes)
				} else {
					getString(R.string.community_comment_rate_limited)
				}
			}
			ApiErrorDto.BANNED -> getString(R.string.community_comment_banned)
			ApiErrorDto.UNAUTHORIZED -> getString(R.string.community_comments_unavailable)
			else -> getString(R.string.community_comment_failed)
		}

		val builder = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_comment_not_posted)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)

		val blockId = error.blockId
		if (error.error == ApiErrorDto.FILTER_BLOCKED && blockId != null) {
			// One tap, by the person who just saw exactly which word stopped them. It is the cheapest
			// and best-targeted false-positive signal the filter will ever get.
			builder.setNegativeButton(R.string.community_comment_filter_wrong) { _, _ ->
				viewModel.dispute(blockId)
				viewBinding?.root?.let {
					Snackbar.make(it, R.string.community_comment_filter_reported, Snackbar.LENGTH_SHORT).show()
				}
			}
		}
		error.rulesUrl?.let { url ->
			builder.setNeutralButton(R.string.community_rules) { _, _ ->
				router.openExternalBrowser(url, getString(R.string.community_rules))
			}
		}
		builder.show()
	}

	companion object {

		private const val TAG = "CommunityCommentsSheet"
		private const val ARG_WORK_ID = "work_id"
		private const val ARG_CHAPTER_ID = "chapter_id"

		/** Mirrors the server's own minimum, which removes "first", "up" and "lol" without judgement. */
		/** Mirrors the server's CommentRules.MIN_BODY_LENGTH. The server is the one that enforces it. */
		/** Only until the first page arrives; the server is the authority. */
		private const val MIN_LENGTH = 10

		fun show(fm: FragmentManager, workId: String, chapterId: Long? = null) {
			CommunityCommentsSheet().apply {
				arguments = Bundle(2).apply {
					putString(ARG_WORK_ID, workId)
					chapterId?.let { putLong(ARG_CHAPTER_ID, it) }
				}
			}.show(fm, TAG)
		}
	}
}
