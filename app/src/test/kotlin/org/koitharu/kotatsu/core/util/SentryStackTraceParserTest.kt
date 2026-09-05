package org.koitharu.kotatsu.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryStackTraceParserTest {

	/** A real report: the Hilt injector missing from the dex after a bad incremental build. */
	private val crash = """
		java.lang.NoClassDefFoundError: Failed resolution of: Lorg/koitharu/kotatsu/core/BaseApp_GeneratedInjector;
			at org.koitharu.kotatsu.core.Hilt_BaseApp.<init>(Hilt_BaseApp.java:21)
			at org.koitharu.kotatsu.core.BaseApp.<init>(BaseApp.kt:41)
			at org.koitharu.kotatsu.KotatsuApp.<init>(KotatsuApp.kt:12)
			at java.lang.Class.newInstance(Native Method)
			at android.app.AppComponentFactory.instantiateApplication(AppComponentFactory.java:80)
			at android.app.ActivityThread.main(ActivityThread.java:9569)
		Caused by: java.lang.ClassNotFoundException: Didn't find class "org.koitharu.kotatsu.core.BaseApp_GeneratedInjector" on path: DexPathList[[zip file "/data/app/base.apk"]]
			at dalvik.system.BaseDexClassLoader.findClass(BaseDexClassLoader.java:259)
			at java.lang.ClassLoader.loadClass(ClassLoader.java:644)
			... 20 more
	""".trimIndent()

	@Test
	fun `orders exceptions root cause first`() {
		val exceptions = SentryStackTraceParser.parse(crash)

		assertEquals(2, exceptions.size)
		// Sentry renders the last entry as the headline, so the thrown exception must come last.
		assertEquals("ClassNotFoundException", exceptions.first().type)
		assertEquals("NoClassDefFoundError", exceptions.last().type)
		assertEquals("java.lang", exceptions.last().module)
		assertTrue(exceptions.last().value!!.startsWith("Failed resolution of:"))
	}

	@Test
	fun `orders frames with the crashing one last`() {
		val frames = SentryStackTraceParser.parse(crash).last().stacktrace!!.frames!!

		assertEquals(6, frames.size)
		assertEquals("main", frames.first().function)
		val crashing = frames.last()
		assertEquals("org.koitharu.kotatsu.core.Hilt_BaseApp", crashing.module)
		assertEquals("<init>", crashing.function)
		assertEquals("Hilt_BaseApp.java", crashing.filename)
		assertEquals(21, crashing.lineno)
	}

	@Test
	fun `marks app frames in-app and native frames native`() {
		val frames = SentryStackTraceParser.parse(crash).last().stacktrace!!.frames!!
		val byModule = frames.associateBy { it.module }

		assertEquals(true, byModule["org.koitharu.kotatsu.KotatsuApp"]?.isInApp)
		assertEquals(false, byModule["android.app.ActivityThread"]?.isInApp)

		val native = byModule.getValue("java.lang.Class")
		assertEquals(true, native.isNative)
		assertNull(native.filename)
		assertNull(native.lineno)
	}

	@Test
	fun `drops elided frame counts`() {
		val causeFrames = SentryStackTraceParser.parse(crash).first().stacktrace!!.frames!!

		// "... 20 more" is not a frame and must not become one.
		assertEquals(2, causeFrames.size)
		assertEquals("findClass", causeFrames.last().function)
	}

	@Test
	fun `handles an exception with no message and unknown source`() {
		val exceptions = SentryStackTraceParser.parse(
			"""
			java.lang.IllegalStateException
				at org.koitharu.kotatsu.Foo${'$'}Bar.doIt(Unknown Source)
			""".trimIndent(),
		)

		assertEquals(1, exceptions.size)
		assertNull(exceptions.single().value)
		val frame = exceptions.single().stacktrace!!.frames!!.single()
		assertEquals("org.koitharu.kotatsu.Foo${'$'}Bar", frame.module)
		assertNull(frame.filename)
		assertNull(frame.lineno)
	}

	@Test
	fun `returns nothing for unparseable input`() {
		assertTrue(SentryStackTraceParser.parse("").isEmpty())
	}
}
