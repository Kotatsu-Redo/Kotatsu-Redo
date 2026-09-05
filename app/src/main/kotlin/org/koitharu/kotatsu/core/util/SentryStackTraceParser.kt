package org.koitharu.kotatsu.core.util

import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace

/**
 * Turns `printStackTrace()` output back into Sentry's structured form.
 *
 * ACRA hands a report's stack trace over as plain text. Sentry groups issues on structured frames, so
 * without this every crash would land as a separate untitled issue with an unreadable blob attached.
 *
 * Sentry orders both exceptions and frames oldest-first, the opposite of how Java prints them, so
 * both lists come back reversed: the thrown exception is last, and within each exception the
 * crashing frame is last.
 */
object SentryStackTraceParser {

	private const val IN_APP_PACKAGE = "org.koitharu."
	private val FRAME_REGEX = Regex("""at\s+([\w$.]+)\.([\w$<>\-]+)\((.*?)\)""")

	fun parse(stackTrace: String): List<SentryException> {
		val exceptions = ArrayList<SentryException>()
		var header: String? = null
		var frames = ArrayList<SentryStackFrame>()

		fun flush() {
			val currentHeader = header ?: return
			exceptions.add(buildException(currentHeader, frames))
			frames = ArrayList()
		}

		for (line in stackTrace.lineSequence()) {
			val trimmed = line.trim()
			when {
				// "... 20 more" elides frames shared with the enclosing exception; there is nothing
				// to reconstruct from it.
				trimmed.isEmpty() || trimmed.startsWith("... ") -> Unit

				trimmed.startsWith("at ") -> parseFrame(trimmed)?.let(frames::add)

				else -> {
					flush()
					header = trimmed.removePrefix("Caused by: ").removePrefix("Suppressed: ")
				}
			}
		}
		flush()
		return exceptions.asReversed()
	}

	private fun buildException(header: String, frames: List<SentryStackFrame>): SentryException {
		val separator = header.indexOf(": ")
		val className = if (separator == -1) header else header.substring(0, separator)
		return SentryException().apply {
			type = className.substringAfterLast('.')
			module = className.substringBeforeLast('.', "").takeUnless { it.isEmpty() }
			value = if (separator == -1) null else header.substring(separator + 2)
			stacktrace = SentryStackTrace(frames.asReversed())
		}
	}

	private fun parseFrame(line: String): SentryStackFrame? {
		val match = FRAME_REGEX.find(line) ?: return null
		val (className, methodName, location) = match.destructured
		return SentryStackFrame().apply {
			module = className
			function = methodName
			isInApp = className.startsWith(IN_APP_PACKAGE)
			if (location == "Native Method") {
				isNative = true
			} else {
				filename = location.substringBefore(':').takeUnless { it == "Unknown Source" }
				lineno = location.substringAfter(':', "").toIntOrNull()
			}
		}
	}
}
