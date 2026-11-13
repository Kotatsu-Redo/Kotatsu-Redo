package org.koitharu.kotatsu.core.parser.bundle

import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * What is published along with a file of newer parsers: which file it is, what it has to hash to, and for which
 * build of the app it was made.
 *
 * It only ever exists read out of a signed envelope, see [read]. A file of parsers is code that runs with everything
 * the app can reach, on the phone of everyone who has it, so nothing of it is taken on the word of the place it was
 * downloaded from: this app trusts user-added certificates, and a network that can read its traffic can change it.
 */
class ParsersManifest private constructor(
	/** The version of the classes the app and the parsers have in common, see `ParsersApi.VERSION`. */
	val api: Int,
	/** Of two sets of parsers, the newer has the larger. */
	val version: Long,
	val revision: String,
	val minSdk: Int,
	val file: String,
	val size: Long,
	val sha256: String,
	val dex: List<DexFile>,
) {

	class DexFile(
		val name: String,
		val size: Long,
		val sha256: String,
	)

	companion object {

		private const val FORMAT = 1
		private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

		/** A manifest is a few hundred bytes. Anything far past that is not one, and is not read. */
		const val MAX_ENVELOPE_SIZE = 64 * 1024

		private const val MAX_FILE_SIZE = 64L * 1024 * 1024
		private const val MAX_DEX_FILES = 16
		private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,120}\\.zip")
		private val DEX_NAME = Regex("classes[0-9]{0,2}\\.dex")
		private val SHA256 = Regex("[0-9a-f]{64}")
		private val REVISION = Regex("[A-Za-z0-9._-]{1,64}")

		/**
		 * @param envelope the downloaded file: what was signed, kept byte for byte, next to its signature
		 * @param trustedKeys the keys this build of the app takes parsers from
		 * @throws ParsersManifestException if it is not signed with one of [trustedKeys], or says anything unexpected
		 */
		fun read(envelope: ByteArray, trustedKeys: Collection<PublicKey>): ParsersManifest {
			if (envelope.size > MAX_ENVELOPE_SIZE) {
				throw ParsersManifestException("Too large to be a manifest: ${envelope.size} bytes")
			}
			val payload: ByteArray
			val signature: ByteArray
			try {
				val json = JSONObject(String(envelope, Charsets.UTF_8))
				payload = json.getString("payload").decodeBase64Strict()
				signature = json.getString("signature").decodeBase64Strict()
			} catch (e: JSONException) {
				throw ParsersManifestException("Not a manifest", e)
			} catch (_: StackOverflowError) {
				// Brackets nested by the thousand, in what nobody has signed yet: made to bring the reader down
				throw ParsersManifestException("Not a manifest")
			}
			if (trustedKeys.none { key -> isSignedWith(key, payload, signature) }) {
				throw ParsersManifestException("The manifest is not signed with a key this app trusts")
			}
			return try {
				parse(JSONObject(String(payload, Charsets.UTF_8)))
			} catch (e: JSONException) {
				throw ParsersManifestException("The manifest is signed, and cannot be read", e)
			}
		}

		/**
		 * @param keys public keys as a build holds them, X.509 in Base64, separated by commas or spaces
		 */
		fun readKeys(keys: String): List<PublicKey> {
			val factory = KeyFactory.getInstance("EC")
			return keys.split(',', ' ', '\n').filter { it.isNotBlank() }.map { key ->
				factory.generatePublic(X509EncodedKeySpec(key.trim().decodeBase64Strict()))
			}
		}

		private fun String.decodeBase64Strict(): ByteArray {
			return decodeBase64()?.toByteArray() ?: throw ParsersManifestException("Not Base64")
		}

		private fun isSignedWith(key: PublicKey, payload: ByteArray, signature: ByteArray): Boolean = try {
			Signature.getInstance(SIGNATURE_ALGORITHM).run {
				initVerify(key)
				update(payload)
				verify(signature)
			}
		} catch (_: GeneralSecurityException) {
			false
		}

		private fun parse(json: JSONObject): ParsersManifest {
			val format = json.getInt("format")
			if (format != FORMAT) {
				throw ParsersManifestException("A manifest of format $format is not known to this app")
			}
			val dexArray = json.getJSONArray("dex")
			if (dexArray.length() !in 1..MAX_DEX_FILES) {
				throw ParsersManifestException("Unexpected number of dex files: ${dexArray.length()}")
			}
			val dex = List(dexArray.length()) { i ->
				val entry = dexArray.getJSONObject(i)
				DexFile(
					name = entry.getString("name").expect(DEX_NAME, "dex file name"),
					size = entry.getLong("size").expectSize(),
					sha256 = entry.getString("sha256").expect(SHA256, "hash"),
				)
			}
			if (dex.distinctBy { it.name }.size != dex.size) {
				throw ParsersManifestException("A dex file is named twice")
			}
			return ParsersManifest(
				api = json.getInt("api"),
				version = json.getLong("version").also {
					if (it <= 0) throw ParsersManifestException("Unexpected version: $it")
				},
				revision = json.getString("revision").expect(REVISION, "revision"),
				minSdk = json.getInt("minSdk"),
				file = json.getString("file").expect(FILE_NAME, "file name"),
				size = json.getLong("size").expectSize(),
				sha256 = json.getString("sha256").expect(SHA256, "hash"),
				dex = dex,
			)
		}

		private fun String.expect(pattern: Regex, what: String): String {
			if (!pattern.matches(this)) {
				throw ParsersManifestException("Unexpected $what in the manifest")
			}
			return this
		}

		private fun Long.expectSize(): Long {
			if (this !in 1..MAX_FILE_SIZE) {
				throw ParsersManifestException("Unexpected file size in the manifest: $this")
			}
			return this
		}
	}
}

class ParsersManifestException(message: String, cause: Throwable? = null) : IOException(message, cause)
