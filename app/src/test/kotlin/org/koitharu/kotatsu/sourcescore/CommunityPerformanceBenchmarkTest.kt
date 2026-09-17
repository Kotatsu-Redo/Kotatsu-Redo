package org.koitharu.kotatsu.sourcescore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.CommentDto
import org.koitharu.kotatsu.sourcescore.data.CommentPageDto
import org.koitharu.kotatsu.sourcescore.ui.threadOrdered
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.system.measureNanoTime

/** Device-independent cost of the largest comment page the API can return. */
class CommunityPerformanceBenchmarkTest {

	@Test
	fun `decode and order a maximum size comment page`() {
		assumeTrue(System.getenv("RUN_COMMUNITY_BENCHMARKS") == "1")
		val comments = buildList {
			repeat(25) { root ->
				val rootId = "root-$root"
				add(comment(rootId, null, root * 20))
				repeat(19) { reply ->
					add(comment("reply-$root-$reply", rootId, root * 20 + reply + 1))
				}
			}
		}.shuffled(Random(42))
		val json = Json { ignoreUnknownKeys = true }
		val payload = json.encodeToString(CommentPageDto(comments = comments, total = comments.size))

		repeat(20) {
			assertEquals(500, json.decodeFromString<CommentPageDto>(payload).comments.threadOrdered().size)
		}

		val decodeIterations = 250
		val decodeNanos = measureNanoTime {
			repeat(decodeIterations) {
				assertEquals(500, json.decodeFromString<CommentPageDto>(payload).comments.size)
			}
		}
		val orderIterations = 1_000
		val orderNanos = measureNanoTime {
			repeat(orderIterations) { assertEquals(500, comments.threadOrdered().size) }
		}

		val output = Path.of("build", "community-performance-results.txt")
		Files.createDirectories(output.parent)
		Files.writeString(
			output,
			listOf(
				"payload_bytes=${payload.toByteArray().size}",
				"decode_500_mean_ms=${"%.3f".format(decodeNanos / 1_000_000.0 / decodeIterations)}",
				"thread_order_500_mean_ms=${"%.3f".format(orderNanos / 1_000_000.0 / orderIterations)}",
			).joinToString(System.lineSeparator(), postfix = System.lineSeparator()),
		)
	}

	private fun comment(id: String, parentId: String?, order: Int) = CommentDto(
		id = id,
		workId = "benchmark-work",
		parentId = parentId,
		depth = if (parentId == null) 0 else 1,
		author = "reader#1234",
		body = "Benchmark comment body number $order",
		lang = "en",
		createdAt = "2026-09-16T12:${(order / 60).toString().padStart(2, '0')}:${(order % 60).toString().padStart(2, '0')}Z",
	)
}
