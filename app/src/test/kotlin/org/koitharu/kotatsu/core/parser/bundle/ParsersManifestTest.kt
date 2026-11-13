package org.koitharu.kotatsu.core.parser.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * What is taken on trust from wherever newer parsers are published: nothing that is not signed with a key the app was
 * built to trust, whatever it says and however well formed it is.
 */
class ParsersManifestTest {

	private val key = newKey()
	private val otherKey = newKey()

	@Test
	fun `a manifest signed with a trusted key is read`() {
		val manifest = ParsersManifest.read(envelope(payload()), listOf(key.public))
		assertEquals(1, manifest.api)
		assertEquals(1_800_000_000L, manifest.version)
		assertEquals("abc1234", manifest.revision)
		assertEquals(26, manifest.minSdk)
		assertEquals("parsers-v1-1800000000.zip", manifest.file)
		assertEquals(2_228_437L, manifest.size)
		assertEquals(HASH_A, manifest.sha256)
		assertEquals(listOf("classes.dex", "classes2.dex"), manifest.dex.map { it.name })
		assertEquals(HASH_B, manifest.dex.first().sha256)
	}

	@Test
	fun `any one of several trusted keys will do`() {
		val manifest = ParsersManifest.read(envelope(payload(), signedWith = otherKey), listOf(key.public, otherKey.public))
		assertEquals("abc1234", manifest.revision)
	}

	@Test
	fun `a manifest signed with another key is refused`() {
		assertRefused(envelope(payload(), signedWith = otherKey))
	}

	@Test
	fun `nothing is read with no key to trust`() {
		assertThrows(ParsersManifestException::class.java) {
			ParsersManifest.read(envelope(payload()), emptyList())
		}
	}

	@Test
	fun `a manifest changed after it was signed is refused`() {
		val signed = payload()
		val signature = sign(signed, key)
		// The same manifest pointing at another file, under the signature of the first
		assertRefused(envelope(payload(file = "parsers-v1-1900000000.zip"), signature))
		assertRefused(envelope(payload(version = 1_900_000_000L), signature))
		assertRefused(envelope(signed.dropLast(1) + " }", signature))
		assertRefused(envelope(signed, signature.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
		assertRefused(envelope(signed, ByteArray(0)))
	}

	@Test
	fun `what is not an envelope at all is refused`() {
		assertRefused("".toByteArray())
		assertRefused("<html>Not found</html>".toByteArray())
		assertRefused("{}".toByteArray())
		assertRefused("""{"payload":"***","signature":"***"}""".toByteArray())
		assertRefused(payload().toByteArray())
		assertRefused(ByteArray(ParsersManifest.MAX_ENVELOPE_SIZE + 1) { ' '.code.toByte() })
	}

	@Test
	fun `a signed manifest that asks for anything out of the way is refused`() {
		// Signed with the right key, each of them: the key being trusted is no reason to do whatever it says
		assertRefused(envelope(payload(file = "../../databases/kotatsu-db.zip")))
		assertRefused(envelope(payload(file = "parsers.apk")))
		assertRefused(envelope(payload(dexName = "../classes.dex")))
		assertRefused(envelope(payload(dexName = "lib.so")))
		assertRefused(envelope(payload(sha256 = "not-a-hash")))
		assertRefused(envelope(payload(sha256 = HASH_A.uppercase())))
		assertRefused(envelope(payload(size = 0)))
		assertRefused(envelope(payload(size = 3L * 1024 * 1024 * 1024)))
		assertRefused(envelope(payload(version = 0)))
		assertRefused(envelope(payload(format = 2)))
		assertRefused(envelope(payload(revision = "a b\"c")))
		assertRefused(envelope("""{"format":1,"api":1,"version":5,"dex":[]}"""))
		assertRefused(envelope("not json"))
	}

	@Test
	fun `the same dex file cannot be listed twice`() {
		val twice = """{"format":1,"api":1,"version":5,"revision":"r","minSdk":26,"file":"p.zip","size":10,""" +
			""""sha256":"$HASH_A","dex":[{"name":"classes.dex","size":4,"sha256":"$HASH_B"},""" +
			"""{"name":"classes.dex","size":4,"sha256":"$HASH_B"}]}"""
		assertRefused(envelope(twice))
	}

	@Test
	fun `the keys of a build are read from its configuration`() {
		val encoded = Base64.getEncoder().encodeToString(key.public.encoded)
		val other = Base64.getEncoder().encodeToString(otherKey.public.encoded)
		assertEquals(listOf(key.public), ParsersManifest.readKeys(encoded))
		assertEquals(listOf(key.public, otherKey.public), ParsersManifest.readKeys(" $encoded, $other\n"))
		assertTrue(ParsersManifest.readKeys("").isEmpty())
		assertThrows(Exception::class.java) { ParsersManifest.readKeys("not a key") }
	}

	private fun assertRefused(envelope: ByteArray) {
		assertThrows(ParsersManifestException::class.java) {
			ParsersManifest.read(envelope, listOf(key.public))
		}
	}

	private fun payload(
		format: Int = 1,
		version: Long = 1_800_000_000L,
		revision: String = "abc1234",
		file: String = "parsers-v1-1800000000.zip",
		size: Long = 2_228_437L,
		sha256: String = HASH_A,
		dexName: String = "classes.dex",
	): String = """{"format":$format,"api":1,"version":$version,"revision":"$revision","minSdk":26,""" +
		""""file":"$file","size":$size,"sha256":"$sha256","dex":[""" +
		"""{"name":"$dexName","size":6072048,"sha256":"$HASH_B"},""" +
		"""{"name":"classes2.dex","size":1024,"sha256":"$HASH_A"}]}"""

	private fun envelope(payload: String, signedWith: KeyPair = key): ByteArray {
		return envelope(payload, sign(payload, signedWith))
	}

	private fun envelope(payload: String, signature: ByteArray): ByteArray {
		val encoder = Base64.getEncoder()
		val encodedPayload = encoder.encodeToString(payload.toByteArray())
		return """{"payload":"$encodedPayload","signature":"${encoder.encodeToString(signature)}"}""".toByteArray()
	}

	private fun sign(payload: String, with: KeyPair): ByteArray = Signature.getInstance("SHA256withECDSA").run {
		initSign(with.private)
		update(payload.toByteArray())
		sign()
	}

	private fun newKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
		initialize(ECGenParameterSpec("secp256r1"))
		generateKeyPair()
	}

	private companion object {

		const val HASH_A = "b5fbc9c5329920cd805c34323263bca62ce44cc8e6642f4c162e59acd0bddd19"
		const val HASH_B = "81282da72667856b57d3f0358d00e0c4640209d62a8fb6ee02b71fead04304f4"
	}
}
