package org.koitharu.kotatsu.sourcescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.RecoveryKey

/**
 * The identity key is the only thing in the app whose loss cannot be undone: a user's comments and
 * ratings are attached to it and nobody - including the server operator - can recover it for them.
 *
 * So both directions get tested. A key that generation produces but validation rejects breaks restore
 * for everyone; a malformed key that validation accepts replaces a real identity with an empty one
 * that looks exactly like a successful restore.
 */
class RecoveryKeyTest {

	/**
	 * The property that matters most. If generation and validation ever disagree, every restore fails
	 * and the failure is invisible until someone changes phone.
	 */
	@Test
	fun `every generated key validates`() {
		repeat(500) {
			val key = RecoveryKey.generate()
			assertTrue("generated key rejected by its own validator: $key", RecoveryKey.isPlausible(key))
		}
	}

	@Test
	fun `generated keys are unique and the expected length`() {
		val keys = List(200) { RecoveryKey.generate() }

		assertEquals("generation produced a duplicate", keys.size, keys.toSet().size)
		keys.forEach { assertEquals(RecoveryKey.LENGTH, it.length) }
	}

	@Test
	fun `generated keys use the url-safe alphabet`() {
		repeat(100) {
			val key = RecoveryKey.generate()
			assertFalse("key needs url encoding: $key", key.contains('+') || key.contains('/'))
			assertFalse("key is padded: $key", key.contains('='))
		}
	}

	// -- what must be rejected -------------------------------------------------------------------

	/**
	 * Each of these is something a person could plausibly paste into the restore box. Accepting any
	 * of them would overwrite a real identity with an unusable one.
	 */
	@Test
	fun `obvious non-keys are rejected`() {
		listOf(
			"" to "empty",
			"   " to "whitespace",
			"hello" to "a word",
			"my recovery key is lost" to "a sentence",
			"https://github.com/sponsors/Kotatsu-Redo" to "a url",
			"raph#4f2a" to "a display name",
			"AAAA" to "far too short",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" to "far too long",
		).forEach { (value, description) ->
			assertFalse("accepted $description: '$value'", RecoveryKey.isPlausible(value))
		}
	}

	/**
	 * A key missing even one character is not a damaged identity, it is a different one that does not
	 * exist - so the user would see a successful restore and an empty account.
	 */
	@Test
	fun `a truncated key is rejected`() {
		val key = RecoveryKey.generate()

		assertFalse("accepted a half-copied key", RecoveryKey.isPlausible(key.take(key.length / 2)))
		assertFalse("accepted a key missing one character", RecoveryKey.isPlausible(key.dropLast(1)))
		assertFalse("accepted a key with one extra character", RecoveryKey.isPlausible(key + "A"))
	}

	@Test
	fun `a key with characters outside the alphabet is rejected`() {
		val key = RecoveryKey.generate()

		// Whitespace is excluded here on purpose: it is stripped rather than rejected, and the
		// resulting length change is what the truncation test covers.
		listOf('!', '@', '.', '/', '+', '=', '#').forEach { bad ->
			val corrupted = key.dropLast(1) + bad
			assertFalse("accepted a key containing '$bad'", RecoveryKey.isPlausible(corrupted))
		}
	}

	// -- what must be accepted -------------------------------------------------------------------

	/** Keys get copied out of chat messages and read off screens; surrounding whitespace is not a typo. */
	@Test
	fun `surrounding and internal whitespace is tolerated`() {
		val key = RecoveryKey.generate()

		listOf(
			"  $key  ",
			"$key\n",
			key.chunked(8).joinToString(" "),
			key.chunked(11).joinToString("\n"),
		).forEach { messy ->
			assertTrue("rejected a key with whitespace: '$messy'", RecoveryKey.isPlausible(messy))
			assertEquals("normalising changed the key", key, RecoveryKey.normalize(messy))
		}
	}

	@Test
	fun `normalising leaves a clean key untouched`() {
		val key = RecoveryKey.generate()
		assertEquals(key, RecoveryKey.normalize(key))
	}

	@Test
	fun `two generated keys are different identities`() {
		assertNotEquals(RecoveryKey.generate(), RecoveryKey.generate())
	}
}
