package org.koitharu.kotatsu.sourcescore.data

import java.security.SecureRandom
import java.util.Base64

/**
 * Generation and validation of the community identity key.
 *
 * Kept apart from [CommunitySettings] because this is the one piece of the identity that must be
 * *provably* right: a key that generation produces but validation rejects would make restore fail for
 * everyone, and a malformed key that validation accepts would silently replace a user's identity with
 * an empty one - losing their comments with no way back. Neither failure is visible until someone has
 * already lost something, so it is pure logic with tests rather than a method on a preferences class.
 */
object RecoveryKey {

	const val SECRET_BYTES = 32

	/**
	 * 32 bytes as unpadded base64url is exactly 43 characters, and that is the only format this app
	 * ever produces.
	 *
	 * Exact rather than a tolerant range, because a range this close to the real length cannot tell a
	 * valid key from one that lost a character on the way through a chat client - and a key missing
	 * one character is not a damaged identity, it is a *different* identity that does not exist. The
	 * user would see a successful restore and an empty account.
	 */
	const val LENGTH = 43

	private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

	fun generate(): String = encoder.encodeToString(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

	/**
	 * Trims and strips the whitespace a key picks up from being copied out of a chat message or read
	 * off a screen. Does not change the key itself - only what surrounds it.
	 */
	fun normalize(value: String): String = value.filterNot { it.isWhitespace() }

	/**
	 * Deliberately a shape check, not a checksum: the key is random bytes, so there is nothing to
	 * verify against. It exists to catch a paste that clearly is not a key - a URL, a sentence, half a
	 * key - before it silently becomes a new identity.
	 */
	fun isPlausible(value: String): Boolean {
		val key = normalize(value)
		return key.length == LENGTH && key.all(::isKeyChar)
	}

	private fun isKeyChar(c: Char): Boolean =
		c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
}
