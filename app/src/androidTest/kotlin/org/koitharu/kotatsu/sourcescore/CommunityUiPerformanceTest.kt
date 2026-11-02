package org.koitharu.kotatsu.sourcescore

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R

/** Opt-in physical-device timing for the two layouts on the community comments path. */
@RunWith(AndroidJUnit4::class)
class CommunityUiPerformanceTest {

	@Test
	@UiThreadTest
	fun commentLayoutsInflateQuickly() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		assumeTrue(InstrumentationRegistry.getArguments().getString("runCommunityBenchmarks") == "true")
		val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Kotatsu)
		val inflater = LayoutInflater.from(context)

		repeat(20) { inflater.inflate(R.layout.item_community_comment, null) }
		val rowTimes = LongArray(200) {
			val started = SystemClock.elapsedRealtimeNanos()
			val row = inflater.inflate(R.layout.item_community_comment, null)
			assertNotNull(row.findViewById(R.id.button_reply))
			SystemClock.elapsedRealtimeNanos() - started
		}.sorted()

		repeat(5) { inflater.inflate(R.layout.sheet_community_comments, null) }
		val sheetTimes = LongArray(30) {
			val started = SystemClock.elapsedRealtimeNanos()
			val sheet = inflater.inflate(R.layout.sheet_community_comments, null)
			assertNotNull(sheet.findViewById(R.id.recyclerView))
			SystemClock.elapsedRealtimeNanos() - started
		}.sorted()

		fun List<Long>.meanMs() = average() / 1_000_000.0
		fun List<Long>.p95Ms() = this[(size * 95 / 100).coerceAtMost(lastIndex)] / 1_000_000.0
		val metrics = "community_row_inflate_mean_ms=${"%.3f".format(rowTimes.meanMs())}\n" +
			"community_row_inflate_p95_ms=${"%.3f".format(rowTimes.p95Ms())}\n" +
			"community_sheet_inflate_mean_ms=${"%.3f".format(sheetTimes.meanMs())}\n" +
			"community_sheet_inflate_p95_ms=${"%.3f".format(sheetTimes.p95Ms())}\n"
		instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$metrics") })
	}
}
