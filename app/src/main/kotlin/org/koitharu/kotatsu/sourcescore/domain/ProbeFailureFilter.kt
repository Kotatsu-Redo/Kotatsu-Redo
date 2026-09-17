package org.koitharu.kotatsu.sourcescore.domain

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Decides whether a failed request says something about the *source* or only about this phone's
 * connection.
 *
 * Without this, a phone that is offline, behind a flaky VPN, or on broken DNS fails every source it
 * tries, and each of those failures lowers the stability of perfectly healthy sources - most of all
 * the ones popular with people on bad networks. Only the phone can tell the difference, so it is
 * decided here, before anything is recorded or uploaded.
 *
 * Pure, so the rules can be tested without a device.
 */
object ProbeFailureFilter {

	/** How recently another source must have answered for this phone's network to count as working. */
	const val RECENT_SUCCESS_WINDOW_MS = 2 * 60_000L

	/**
	 * @param isNetworkValidated Android's own check that the active network reaches the internet.
	 * @param hasRecentSuccessElsewhere a *different* source answered within [RECENT_SUCCESS_WINDOW_MS].
	 * @return true when the failure should not be counted at all - neither locally nor in telemetry.
	 */
	fun shouldDiscard(
		error: Throwable,
		isNetworkValidated: Boolean,
		hasRecentSuccessElsewhere: Boolean,
	): Boolean = when {
		// No validated internet: nothing that failed can be blamed on the source.
		!isNetworkValidated -> true
		// A connection-level error on a validated network is ambiguous. It is what broken DNS or a
		// dying VPN produces, but it is also exactly what a dead or moved domain produces - and manga
		// sources move constantly. Discarding every one would make dead sources look healthy forever,
		// so it is only discarded when nothing else has worked recently either.
		isConnectionLevel(error) -> !hasRecentSuccessElsewhere
		// The server answered, or failed after connecting: that is the source's doing.
		else -> false
	}

	/**
	 * The request never reached the source: name resolution, routing or connecting failed. A read
	 * timeout is deliberately excluded - the source accepted the connection and then did not answer,
	 * which is a real source problem.
	 */
	fun isConnectionLevel(error: Throwable): Boolean {
		var current: Throwable? = error
		var depth = 0
		while (current != null && depth < MAX_CAUSE_DEPTH) {
			when (current) {
				is UnknownHostException,
				is ConnectException,
				is NoRouteToHostException,
				is PortUnreachableException -> return true

				// OkHttp reports a connect timeout as a SocketTimeoutException named after the phase.
				is SocketTimeoutException -> if (current.message?.contains("connect", ignoreCase = true) == true) {
					return true
				}
			}
			current = current.cause?.takeIf { it !== current }
			depth++
		}
		return false
	}

	private const val MAX_CAUSE_DEPTH = 8
}
