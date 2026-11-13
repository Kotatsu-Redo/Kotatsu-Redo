package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy of a favourite wins when the phone and the server disagree on whether it is still one.
 *
 * A sync sends every row before it reads any, and the server keeps what reaches it last. A manga removed on one
 * device so stayed on another that still had it, which put it back on the server every time, and it came back on
 * the first device once the record of its removal was cleared out there.
 */
class SyncMergeTest {

	@Test
	fun `removed on another device after it was added here, it goes here too`() {
		assertTrue(isRemoteCopyNewer(localChangedAt = ADDED, localDeletedAt = 0, remoteChangedAt = ADDED, remoteDeletedAt = LATER))
	}

	@Test
	fun `removed here, the copy another device still has does not bring it back`() {
		assertFalse(isRemoteCopyNewer(localChangedAt = ADDED, localDeletedAt = LATER, remoteChangedAt = ADDED, remoteDeletedAt = 0))
	}

	@Test
	fun `added again on another device after it was removed here, it comes back`() {
		assertTrue(isRemoteCopyNewer(localChangedAt = ADDED, localDeletedAt = LATER, remoteChangedAt = LATEST, remoteDeletedAt = 0))
	}

	@Test
	fun `added again here after it was removed on another device, it stays`() {
		assertFalse(isRemoteCopyNewer(localChangedAt = LATEST, localDeletedAt = 0, remoteChangedAt = ADDED, remoteDeletedAt = LATER))
	}

	@Test
	fun `two copies that agree on whether it is a favourite are left to the rest of the sync`() {
		assertFalse(isRemoteCopyNewer(localChangedAt = ADDED, localDeletedAt = 0, remoteChangedAt = LATEST, remoteDeletedAt = 0))
		assertFalse(isRemoteCopyNewer(localChangedAt = ADDED, localDeletedAt = LATER, remoteChangedAt = ADDED, remoteDeletedAt = LATEST))
	}

	@Test
	fun `with nothing to tell which is later, the copy here is kept`() {
		assertFalse(isRemoteCopyNewer(localChangedAt = LATER, localDeletedAt = 0, remoteChangedAt = ADDED, remoteDeletedAt = LATER))
	}

	private companion object {

		const val ADDED = 1_000L
		const val LATER = 2_000L
		const val LATEST = 3_000L
	}
}
