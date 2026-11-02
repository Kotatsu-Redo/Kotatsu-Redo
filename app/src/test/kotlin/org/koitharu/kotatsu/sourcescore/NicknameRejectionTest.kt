package org.koitharu.kotatsu.sourcescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.data.CommunityApiException
import org.koitharu.kotatsu.sourcescore.data.nicknameRejection
import java.io.IOException

/**
 * A refused nickname is dropped from the device; anything else leaves it pending.
 *
 * Getting this backwards is exactly how users ended up shown a name locally while every comment they
 * wrote said `anon#xxxx`: in one direction a refused name stays on screen forever, in the other a
 * flaky connection throws away a name the server would have accepted.
 */
class NicknameRejectionTest {

	@Test
	fun `the server refusing the name is a rejection`() {
		val reserved = ApiErrorDto(error = "bad_request", field = "nickname_reserved")
		assertEquals(reserved, nicknameRejection(CommunityApiException(400, reserved)))
		val filtered = ApiErrorDto(error = ApiErrorDto.FILTER_BLOCKED, term = "x")
		assertEquals(filtered, nicknameRejection(CommunityApiException(422, filtered)))
	}

	@Test
	fun `not being able to deliver it is not a rejection`() {
		assertNull(nicknameRejection(IOException("offline")))
		assertNull(nicknameRejection(CommunityApiException(401, ApiErrorDto(error = ApiErrorDto.UNAUTHORIZED))))
		assertNull(nicknameRejection(CommunityApiException(429, ApiErrorDto(error = ApiErrorDto.RATE_LIMITED))))
		assertNull(nicknameRejection(CommunityApiException(503, ApiErrorDto())))
	}
}
