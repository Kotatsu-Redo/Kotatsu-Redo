package org.koitharu.kotatsu.sourcescore.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationReminderTest {

	private val day = 24 * 60 * 60 * 1000L
	private val start = 1_800_000_000_000L

	@Test
	fun `nobody is asked before the clock has started`() {
		assertFalse(DonationReminder.isDue(start, last = 0L))
	}

	@Test
	fun `not again within the month`() {
		assertFalse(DonationReminder.isDue(start + 29 * day, last = start))
	}

	@Test
	fun `asked once the month is up`() {
		assertTrue(DonationReminder.isDue(start + 30 * day, last = start))
	}

	@Test
	fun `a clock set backwards does not ask`() {
		assertFalse(DonationReminder.isDue(start - 40 * day, last = start))
	}
}
