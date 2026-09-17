package org.koitharu.kotatsu.sourcescore.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.data.CommentDto
import org.koitharu.kotatsu.sourcescore.data.CommentRulesDto
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityApiException
import org.koitharu.kotatsu.sourcescore.domain.CommunityWorkResolver
import java.time.Instant
import javax.inject.Inject

/** What the sheet is being asked to do, so the send button can say so and refuse to double-fire. */
enum class ComposeMode { NEW, REPLY, EDIT }

data class ComposeTarget(
	val mode: ComposeMode = ComposeMode.NEW,
	/** The comment being replied to or edited. */
	val comment: CommentDto? = null,
) {
	val isEditing: Boolean get() = mode == ComposeMode.EDIT
}

/**
 * A rejection worth showing the user, as opposed to a network failure worth swallowing.
 *
 * Carries the server's code and its data rather than a sentence: the server holds no translations,
 * so the sentence is built in the sheet from the app's own string resources (PLAN.md §3).
 */
data class CommentRejection(val error: ApiErrorDto)

@HiltViewModel
class CommunityCommentsViewModel @Inject constructor(
	private val api: CommunityApi,
	private val resolver: CommunityWorkResolver,
) : ViewModel() {

	val comments = MutableLiveData<List<CommentDto>>(emptyList())
	val total = MutableLiveData(0)
	val otherLanguages = MutableLiveData<Map<String, Int>>(emptyMap())

	/** The server's own limits, so the app never enforces a rule the server has since relaxed. */
	val rules = MutableLiveData(CommentRulesDto())
	val isLoading = MutableLiveData(false)
	val isSending = MutableLiveData(false)
	val compose = MutableLiveData(ComposeTarget())

	/** Set once and cleared when shown: a rejection must not re-appear on every rotation. */
	val rejection = MutableLiveData<CommentRejection?>()
	val loadFailed = MutableLiveData(false)

	private var workId: String? = null
	private var chapterId: Long? = null
	private var sort: String = SORT_TOP
	private var inFlight: Job? = null

	fun start(workId: String, chapterId: Long?) {
		if (this.workId == workId && this.chapterId == chapterId && comments.value?.isNotEmpty() == true) return
		this.workId = workId
		this.chapterId = chapterId
		reload()
	}

	fun setSort(value: String) {
		if (sort == value) return
		sort = value
		reload()
	}

	fun currentSort(): String = sort

	fun reload() {
		val id = workId ?: return
		inFlight?.cancel()
		inFlight = viewModelScope.launch {
			isLoading.value = true
			loadFailed.value = false
			runCatchingCancellable {
				withCanonicalWork(id) { canonical -> api.fetchComments(canonical, chapterId, sort) }
			}
				.onSuccess { page ->
					comments.value = page.comments.threadOrdered()
					total.value = page.total
					otherLanguages.value = page.byLanguage
					rules.value = page.rules
				}
				.onFailure { loadFailed.value = true }
			isLoading.value = false
		}
	}

	fun replyTo(comment: CommentDto) {
		compose.value = ComposeTarget(ComposeMode.REPLY, comment)
	}

	fun editOwn(comment: CommentDto) {
		compose.value = ComposeTarget(ComposeMode.EDIT, comment)
	}

	fun cancelCompose() {
		compose.value = ComposeTarget()
	}

	/**
	 * @return true when the draft was accepted, so the caller can clear the input. A rejection leaves
	 *  the text exactly where the user left it - being told to rewrite and losing what you wrote is
	 *  the worst possible combination.
	 */
	suspend fun send(body: String, isSpoiler: Boolean): Boolean {
		val id = workId ?: return false
		val target = compose.value ?: ComposeTarget()
		if (isSending.value == true) return false
		isSending.value = true
		try {
			val result = runCatchingCancellable {
				if (target.isEditing) {
					api.editComment(requireNotNull(target.comment).id, body)
				} else {
					withCanonicalWork(id) { canonical ->
						api.postComment(
							workId = canonical,
							body = body,
							parentId = target.comment?.id,
							chapterId = chapterId,
							isSpoiler = isSpoiler,
						)
					}
				}
			}
			result.onFailure { error ->
				// Logged because a rejection the user never sees is indistinguishable from the send
				// button doing nothing, and that is what a lost exception looks like from outside.
				android.util.Log.w(
					"CommunityComments",
					"post failed: " + error.javaClass.simpleName + ": " + error.message,
				)
				if (error is CommunityApiException) rejection.value = CommentRejection(error.error)
				return false
			}
			compose.value = ComposeTarget()
			reload()
			return true
		} finally {
			isSending.value = false
		}
	}

	/**
	 * Votes, updating the one row rather than reloading.
	 *
	 * A full reload would re-sort the list under the user's thumb, which makes voting on a second
	 * comment a game of chase.
	 */
	fun vote(comment: CommentDto, value: Int) {
		viewModelScope.launch {
			val next = if (comment.myVote == value) 0 else value
			runCatchingCancellable { api.voteComment(comment.id, next) }
				.onSuccess { updated -> replace(updated) }
		}
	}

	fun delete(comment: CommentDto) {
		viewModelScope.launch {
			runCatchingCancellable { api.deleteComment(comment.id) }.onSuccess { reload() }
		}
	}

	fun dispute(blockId: String) {
		viewModelScope.launch { runCatchingCancellable { api.disputeFilterBlock(blockId) } }
	}

	fun consumeRejection() {
		rejection.value = null
	}

	private fun replace(updated: CommentDto) {
		comments.value = comments.value.orEmpty().map { if (it.id == updated.id) updated else it }
	}

	private suspend fun <T> withCanonicalWork(id: String, request: suspend (String) -> T): T =
		try {
			request(id)
		} catch (error: CommunityApiException) {
			val movedTo = error.error.movedTo
			if (error.error.error != ApiErrorDto.WORK_MOVED || movedTo.isNullOrBlank()) throw error
			resolver.repoint(id, movedTo)
			if (workId == id) workId = movedTo
			request(movedTo)
		}

	companion object {
		const val SORT_TOP = "top"
		const val SORT_NEW = "new"
	}
}

/**
 * Keeps the server's root ranking, then lays each reply tree out chronologically.
 *
 * The server deliberately does not sort the recursive result because doing so forces PostgreSQL to
 * materialize an arbitrarily large thread before applying its safety limit. Ordering here is cheap
 * (at most 500 comments) and also makes truncated or older-server responses deterministic.
 */
internal fun List<CommentDto>.threadOrdered(): List<CommentDto> {
	if (size < 2) return this
	val children = groupBy(CommentDto::parentId)
	val result = ArrayList<CommentDto>(size)
	val seen = HashSet<String>(size)

	fun append(comment: CommentDto) {
		if (!seen.add(comment.id)) return
		result += comment
		children[comment.id].orEmpty()
			.sortedWith(COMMENT_CHRONOLOGICAL)
			.forEach(::append)
	}

	// Root order is already the requested "top" or "new" order and must not be changed.
	children[null].orEmpty().forEach(::append)
	// Defensive fallback for a truncated response whose parent was omitted, or malformed cyclic data.
	filter { it.id !in seen }.sortedWith(COMMENT_CHRONOLOGICAL).forEach(::append)
	return result
}

private val COMMENT_CHRONOLOGICAL = compareBy<CommentDto>(
	{ runCatching { Instant.parse(it.createdAt) }.getOrNull() },
	{ it.id },
)
