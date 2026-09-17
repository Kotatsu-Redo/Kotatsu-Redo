package org.koitharu.kotatsu.sourcescore.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemCommunityCommentBinding
import org.koitharu.kotatsu.sourcescore.data.CommentDto
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * What a tap on a comment can do.
 *
 * Deliberately short: there is no report action, because dislikes *are* the moderation signal and a
 * report button is itself a brigading weapon (PLAN.md §6).
 */
interface CommentActions {
	fun onVote(comment: CommentDto, value: Int)
	fun onReply(comment: CommentDto)
	fun onEdit(comment: CommentDto)
	fun onDelete(comment: CommentDto)
}

class CommunityCommentAdapter(
	private val actions: CommentActions,
) : ListAdapter<CommentDto, CommunityCommentAdapter.Holder>(DIFF) {

	/**
	 * Spoilers the reader has chosen to see.
	 *
	 * Held here rather than on the item, because it is a property of this reader's session and not of
	 * the comment: scrolling away and back should not re-hide something they deliberately revealed,
	 * and reloading the thread should.
	 */
	private val revealed = mutableSetOf<String>()

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
		ItemCommunityCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false),
	)

	override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

	inner class Holder(
		private val binding: ItemCommunityCommentBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		fun bind(comment: CommentDto) {
			val context = binding.root.context
			// Which container this comment sits in. Fill is spent on the two facts worth seeing at a
			// glance - whose it is, and whether it answers the one above - rather than on decoration.
			val isReply = comment.depth > 0
			// setBackgroundResource resets padding to the drawable's, which is none. Put the layout's
			// own padding back or the container closes on the text and stops reading as a container.
			val left = binding.root.paddingLeft
			val top = binding.root.paddingTop
			val right = binding.root.paddingRight
			val bottom = binding.root.paddingBottom
			binding.root.setBackgroundResource(
				when {
					comment.isMine && isReply -> R.drawable.bg_comment_reply_mine
					comment.isMine -> R.drawable.bg_comment_mine
					isReply -> R.drawable.bg_comment_reply
					else -> R.drawable.bg_comment
				},
			)
			binding.root.setPadding(left, top, right, bottom)
			// Text has to follow the container it is written on, or a tinted card gets body copy
			// coloured for a surface it is no longer sitting on.
			val onContainer = MaterialColors.getColor(
				binding.root,
				if (comment.isMine) {
					com.google.android.material.R.attr.colorOnSecondaryContainer
				} else {
					com.google.android.material.R.attr.colorOnSurface
				},
			)
			binding.textViewAuthor.setTextColor(onContainer)

			// Replies are indented once and then not again: nesting a thread six levels deep on a
			// phone leaves a column of text one word wide.
			val indent = if (isReply) {
				context.resources.getDimensionPixelSize(R.dimen.community_reply_indent)
			} else {
				0
			}
			// A margin, not padding: padding would shrink the container, a margin moves it.
			binding.root.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
				marginStart = indent
			}

			binding.textViewAuthor.text = comment.author
			binding.textViewTime.text = relativeTime(comment.createdAt)

			if (comment.deleted) {
				// The tombstone holds its place so the replies under it still make sense, and says
				// nothing else at all.
				binding.textViewBody.setText(R.string.community_comment_deleted)
				binding.textViewBody.isEnabled = false
				binding.textViewAuthor.setText(R.string.community_comment_deleted_author)
				binding.groupActions.isVisible = false
				return
			}

			bindBody(comment, onContainer)
			binding.groupActions.isVisible = true

			// Hidden, not disabled, on your own comment. The server refuses the vote either way, and a
			// control that cannot be pressed is worse than no control - it invites the press and then
			// does nothing. The count goes with it: it is the button's label, not a separate readout.
			binding.buttonUp.isVisible = !comment.isMine
			binding.buttonDown.isVisible = !comment.isMine
			binding.buttonUp.text = comment.up.toString()
			binding.buttonDown.text = comment.down.toString()
			// Colour carries the state now that the buttons have no outline to fill in.
			tintVote(binding.buttonUp, comment.myVote > 0)
			tintVote(binding.buttonDown, comment.myVote < 0)

			binding.buttonReply.isVisible = true
			binding.buttonEdit.isVisible = comment.isMine
			binding.buttonDelete.isVisible = comment.isMine

			binding.buttonUp.setOnClickListener { actions.onVote(comment, 1) }
			binding.buttonDown.setOnClickListener { actions.onVote(comment, -1) }
			binding.buttonReply.setOnClickListener { actions.onReply(comment) }
			binding.buttonEdit.setOnClickListener { actions.onEdit(comment) }
			binding.buttonDelete.setOnClickListener { actions.onDelete(comment) }
		}

		private fun tintVote(button: TextView, chosen: Boolean) {
			val color = MaterialColors.getColor(
				button,
				if (chosen) {
					androidx.appcompat.R.attr.colorPrimary
				} else {
					com.google.android.material.R.attr.colorOnSurfaceVariant
				},
			)
			TextViewCompat.setCompoundDrawableTintList(button, android.content.res.ColorStateList.valueOf(color))
			button.setTextColor(color)
		}

		/**
		 * A spoiler is withheld until asked for.
		 *
		 * The content policy asks people to tag spoilers, so a tagged one has to actually be hidden -
		 * a flag that only shows a label would make the tagging pointless.
		 */
		private fun bindBody(comment: CommentDto, onContainer: Int) {
			val hidden = comment.isSpoiler && comment.id !in revealed
			binding.textViewBody.isEnabled = true
			if (hidden) {
				binding.textViewBody.setText(R.string.community_comment_spoiler_hidden)
				binding.textViewBody.setTextColor(
					MaterialColors.getColor(binding.textViewBody, androidx.appcompat.R.attr.colorPrimary),
				)
				binding.textViewBody.setOnClickListener {
					revealed.add(comment.id)
					notifyItemChanged(bindingAdapterPosition)
				}
			} else {
				binding.textViewBody.text = comment.body
				binding.textViewBody.setTextColor(onContainer)
				binding.textViewBody.setOnClickListener(null)
				binding.textViewBody.isClickable = false
			}
		}

		private fun relativeTime(iso: String): CharSequence = try {
			DateUtils.getRelativeTimeSpanString(
				Instant.parse(iso).toEpochMilli(),
				System.currentTimeMillis(),
				DateUtils.MINUTE_IN_MILLIS,
			)
		} catch (e: DateTimeParseException) {
			""
		}
	}

	private companion object {

		val DIFF = object : DiffUtil.ItemCallback<CommentDto>() {
			override fun areItemsTheSame(oldItem: CommentDto, newItem: CommentDto) = oldItem.id == newItem.id

			override fun areContentsTheSame(oldItem: CommentDto, newItem: CommentDto) = oldItem == newItem
		}
	}
}
