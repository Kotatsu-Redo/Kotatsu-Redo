package org.koitharu.kotatsu.sourcescore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.data.CommentDto
import org.koitharu.kotatsu.sourcescore.data.CommentPageDto
import org.koitharu.kotatsu.sourcescore.data.NotificationsResponse
import org.koitharu.kotatsu.sourcescore.data.ScoresResponse
import org.koitharu.kotatsu.sourcescore.ui.threadOrdered

/**
 * The contract with the community server, pinned against its actual output.
 *
 * These payloads are copied verbatim from a live `kotatsuredo-server`. The two repositories are
 * built and deployed separately, so nothing but a test like this notices when a field is renamed on
 * one side - and the failure mode without it is silent: `ignoreUnknownKeys` turns a renamed field
 * into a default value and the feature quietly shows nothing.
 */
class CommunityWireFormatTest {

	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `a page from a server too old to send rules still decodes`() {
		val page = json.decodeFromString<CommentPageDto>("""{"comments":[],"total":0}""")
		assertEquals(10, page.rules.minLength)
	}

	@Test
	fun `a comment page decodes`() {
		val payload = """
			{"comments":[{"id":"277","work_id":"309","depth":0,"author":"anon#jlsm",
			"body":"The art in this chapter is genuinely spectacular.","is_spoiler":false,"lang":"en",
			"created_at":"2026-09-10T20:26:02.727388Z","up":1,"down":0,"score":0.2065433,"my_vote":1,
			"is_mine":false,"deleted":false},{"id":"278","work_id":"309","parent_id":"277","depth":1,
			"author":"anon#ze3r","body":"Agreed.","is_spoiler":false,"lang":"fr",
			"created_at":"2026-09-10T20:26:02.748189Z","up":0,"down":0,"score":0.0,"my_vote":0,
			"is_mine":true,"deleted":false}],"total":2,"by_language":{"en":1,"fr":1},
			"rules":{"min_length":10,"max_length":4000}}
		""".trimIndent().replace("\n", "")

		val page = json.decodeFromString<CommentPageDto>(payload)
		assertEquals(2, page.total)
		assertEquals(mapOf("en" to 1, "fr" to 1), page.byLanguage)
		// The server owns the limits. If this stops decoding the app silently falls back to its own
		// number and starts refusing comments the server would have accepted.
		assertEquals(10, page.rules.minLength)
		assertEquals(4000, page.rules.maxLength)

		val root = page.comments.first()
		assertEquals("277", root.id)
		assertEquals(1, root.myVote)
		assertFalse(root.isMine)
		assertNull(root.parentId)

		val reply = page.comments.last()
		assertEquals("277", reply.parentId)
		assertEquals(1, reply.depth)
		assertTrue(reply.isMine)
	}

	@Test
	fun `unordered recursive replies are grouped under ranked roots chronologically`() {
		fun comment(id: String, parent: String? = null, at: String) = CommentDto(
			id = id,
			workId = "1",
			parentId = parent,
			depth = if (parent == null) 0 else 1,
			createdAt = at,
		)

		val rankedSecond = comment("root-2", at = "2026-09-02T00:00:00Z")
		val rankedFirst = comment("root-1", at = "2026-09-01T00:00:00Z")
		val ordered = listOf(
			rankedSecond,
			rankedFirst,
			comment("late-1", "root-1", "2026-09-04T00:00:00Z"),
			comment("child-2", "root-2", "2026-09-05T00:00:00Z"),
			comment("early-1", "root-1", "2026-09-03T00:00:00Z"),
		).threadOrdered()

		assertEquals(
			listOf("root-2", "child-2", "root-1", "early-1", "late-1"),
			ordered.map(CommentDto::id),
		)
	}

	@Test
	fun `a tombstone decodes as empty rather than missing`() {
		// A deleted comment keeps its place in the thread so the replies under it still make sense,
		// and carries nothing else - the adapter relies on `deleted` rather than on an empty body.
		val payload = """
			{"id":"12","work_id":"9","depth":0,"author":"","body":"","is_spoiler":false,
			"created_at":"2026-09-10T20:26:02Z","up":0,"down":0,"score":0.0,"my_vote":0,
			"is_mine":false,"deleted":true}
		""".trimIndent().replace("\n", "")

		val comment = json.decodeFromString<CommentDto>(payload)
		assertTrue(comment.deleted)
		assertEquals("", comment.body)
	}

	/**
	 * The rejection the app has to explain.
	 *
	 * `term` is the user's own word, echoed back untranslated, and `block_id` is what the "this was
	 * wrong" button reports against. Losing either turns a helpful message into "something failed".
	 */
	@Test
	fun `a filter rejection carries the term, the rules link and the block id`() {
		val payload = """
			{"error":"filter_blocked","term":"shit","tier":"profanity",
			"rules_url":"https://example.invalid/rules","block_id":"42"}
		""".trimIndent().replace("\n", "")

		val error = json.decodeFromString<ApiErrorDto>(payload)
		assertEquals(ApiErrorDto.FILTER_BLOCKED, error.error)
		assertEquals("shit", error.term)
		assertEquals("42", error.blockId)
		assertEquals("https://example.invalid/rules", error.rulesUrl)
	}

	@Test
	fun `the other rejections the sheet switches on decode`() {
		assertEquals(20, json.decodeFromString<ApiErrorDto>("""{"error":"too_short","min":20}""").min)
		assertEquals(
			ApiErrorDto.CHAIN_DEPTH_EXCEEDED,
			json.decodeFromString<ApiErrorDto>("""{"error":"chain_depth_exceeded"}""").error,
		)
		assertEquals(
			900L,
			json.decodeFromString<ApiErrorDto>(
				"""{"error":"rate_limited","retry_after_s":900,"limit":"comments"}""",
			).retryAfterSeconds,
		)
		assertEquals(
			ApiErrorDto.BANNED,
			json.decodeFromString<ApiErrorDto>("""{"error":"banned"}""").error,
		)
	}

	@Test
	fun `notifications carry the cursor that stops a reply being announced twice`() {
		val payload = """
			{"replies":[{"comment_id":"278","work_id":"309","parent_id":"277","author":"anon#ze3r",
			"preview":"Agreed, the linework is superb.","created_at":"2026-09-10T20:26:02.748189Z"}],
			"cursor":"2026-09-10T20:26:02.748189Z"}
		""".trimIndent().replace("\n", "")

		val response = json.decodeFromString<NotificationsResponse>(payload)
		assertEquals(1, response.replies.size)
		assertEquals("277", response.replies.single().parentId)
		// UTC with a `Z`, never a numeric offset: the cursor goes straight back as a query parameter,
		// and `+02:00` would arrive decoded as a space.
		assertTrue(response.cursor.endsWith("Z"))
	}

	@Test
	fun `source score response keeps the server generation timestamp`() {
		val response = json.decodeFromString<ScoresResponse>(
			"""{"region":"EU","generated_at":"2026-09-15T12:00:00Z","median_composite":0.6,"median_stability":0.7,"sources":[]}""",
		)

		assertEquals("2026-09-15T12:00:00Z", response.generatedAt)
	}
}
