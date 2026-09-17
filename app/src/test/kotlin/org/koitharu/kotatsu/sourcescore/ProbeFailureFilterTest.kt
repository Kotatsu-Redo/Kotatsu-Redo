package org.koitharu.kotatsu.sourcescore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.domain.ProbeFailureFilter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Which failures blame the source, and which only describe this phone's connection. */
class ProbeFailureFilterTest {

	private fun discard(error: Throwable, validated: Boolean = true, recentElsewhere: Boolean = false) =
		ProbeFailureFilter.shouldDiscard(error, validated, recentElsewhere)

	@Test
	fun `nothing counts without validated internet`() {
		assertTrue(discard(IOException("HTTP 500"), validated = false))
		assertTrue(discard(UnknownHostException(), validated = false, recentElsewhere = true))
	}

	@Test
	fun `a connection error is discarded when nothing else has worked either`() {
		// Broken DNS or a dying VPN on a network Android still calls validated.
		assertTrue(discard(UnknownHostException("example.org")))
		assertTrue(discard(ConnectException("Failed to connect")))
		assertTrue(discard(SocketTimeoutException("connect timed out")))
	}

	@Test
	fun `a dead domain still counts when other sources are answering`() {
		// Without this, a source whose domain expired would never register a failure.
		assertFalse(discard(UnknownHostException("dead.example"), recentElsewhere = true))
		assertFalse(discard(ConnectException("Connection refused"), recentElsewhere = true))
	}

	@Test
	fun `failures after connecting always count against the source`() {
		assertFalse(discard(IOException("HTTP 503")))
		// A read timeout means the source accepted the connection and then did not answer.
		assertFalse(discard(SocketTimeoutException("timeout")))
		assertFalse(discard(SocketTimeoutException("Read timed out")))
	}

	@Test
	fun `a wrapped connection error is still recognised`() {
		val wrapped = RuntimeException("search failed", IOException("io", UnknownHostException("x")))

		assertTrue(ProbeFailureFilter.isConnectionLevel(wrapped))
		assertFalse(ProbeFailureFilter.isConnectionLevel(RuntimeException(IOException("HTTP 404"))))
	}
}
