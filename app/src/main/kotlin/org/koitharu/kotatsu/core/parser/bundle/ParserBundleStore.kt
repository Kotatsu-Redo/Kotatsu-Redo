package org.koitharu.kotatsu.core.parser.bundle

import androidx.annotation.WorkerThread
import dalvik.system.PathClassLoader
import org.koitharu.kotatsu.parsers.ParsersApi
import org.koitharu.kotatsu.parsers.ParsersBundle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.zip.ZipFile

/**
 * Downloaded parsers on disk and the way from there into memory: one directory to a version, holding its dex files
 * as they are run and the signed manifest they came with.
 *
 * @param root where the directories are. To be inside the app's own storage and nowhere else can write.
 * @param parent the class loader of the app, where the parsers find everything they do not bring themselves
 */
class ParserBundleStore(
	private val root: File,
	private val trustedKeys: List<PublicKey>,
	private val parent: ClassLoader,
) {

	/**
	 * @throws ParsersManifestException if [envelope] is not a manifest signed with a key this app trusts
	 */
	fun readManifest(envelope: ByteArray): ParsersManifest = ParsersManifest.read(envelope, trustedKeys)

	/**
	 * Checks a downloaded archive against the manifest that names it and unpacks it, ready for [load].
	 *
	 * Nothing is taken from the archive but the files the manifest lists, and each has to be of the size and hash
	 * given there: the manifest is signed, the archive is only as good as its agreement with it.
	 *
	 * @param envelope the manifest as downloaded
	 * @throws IOException if anything does not match. Nothing is left behind then.
	 */
	@WorkerThread
	fun install(envelope: ByteArray, archive: File): ParsersManifest {
		val manifest = readManifest(envelope)
		if (archive.length() != manifest.size) {
			throw IOException("The parsers are ${archive.length()} bytes, and were to be ${manifest.size}")
		}
		if (archive.inputStream().use { it.sha256() } != manifest.sha256) {
			throw IOException("The parsers are not the file the manifest was signed for")
		}
		val dir = directoryOf(manifest.version)
		val staging = File(root, dir.name + STAGING_SUFFIX)
		staging.deleteRecursively()
		if (!staging.mkdirs()) {
			throw IOException("Cannot create $staging")
		}
		try {
			ZipFile(archive).use { zip ->
				for (dex in manifest.dex) {
					val entry = zip.getEntry(dex.name) ?: throw IOException("${dex.name} is not in the parsers file")
					val target = File(staging, dex.name)
					val hash = zip.getInputStream(entry).use { input ->
						target.outputStream().use { output ->
							val result = input.copyAndHash(output, limit = dex.size)
							output.fd.sync()
							result
						}
					}
					if (target.length() != dex.size || hash != dex.sha256) {
						throw IOException("${dex.name} is not the one the manifest was signed for")
					}
					// Android refuses to load code from a file that can still be written to
					target.setReadOnly()
				}
			}
			File(staging, MANIFEST_FILE).writeBytes(envelope)
			dir.deleteRecursively()
			if (!staging.renameTo(dir)) {
				throw IOException("Cannot move the parsers to $dir")
			}
		} catch (e: Throwable) {
			staging.deleteRecursively()
			throw e
		}
		return manifest
	}

	/**
	 * Loads the parsers of [version] into memory.
	 *
	 * The manifest kept with them is checked again for its signature, and the files for being the size it gives.
	 * Their contents were hashed when they were unpacked, into a place only this app writes to, and are not gone
	 * over again on every launch: that is megabytes to read before the app can say which sources it has.
	 *
	 * @throws ParsersFilesException if the files are not there or not the ones that were unpacked. Nothing is wrong
	 * with the parsers then: they can be downloaded again.
	 * @throws Exception whatever is wrong with the parsers themselves. They are not to be tried again then.
	 */
	@WorkerThread
	fun load(version: Long): ParsersBundle {
		val dir = directoryOf(version)
		val manifest: ParsersManifest
		val files: List<File>
		try {
			manifest = readManifest(File(dir, MANIFEST_FILE).readBytes())
			if (manifest.version != version) {
				throw IOException("The parsers in $dir are of version ${manifest.version}")
			}
			files = manifest.dex.map { dex ->
				val file = File(dir, dex.name)
				if (!file.isFile || file.length() != dex.size) {
					throw IOException("${dex.name} of the parsers is missing or damaged")
				}
				if (file.canWrite()) {
					file.setReadOnly()
				}
				file
			}
		} catch (e: IOException) {
			throw ParsersFilesException("The files of the parsers of version $version cannot be used: ${e.message}", e)
		}
		val loader = BundleClassLoader(files.joinToString(File.pathSeparator) { it.absolutePath }, parent)
		val entry = loader.loadClass(ENTRY_POINT)
		if (entry.classLoader !== loader) {
			// Not found in the files, and taken from the app. Files of the right size that are no longer what was
			// unpacked would do that: they are read through this once, to tell that from parsers that are wrong
			val isIntact = manifest.dex.all { dex -> File(dir, dex.name).inputStream().use { it.sha256() } == dex.sha256 }
			if (!isIntact) {
				throw ParsersFilesException("The files of the parsers of version $version are damaged")
			}
			throw IllegalStateException("The parsers loaded are the app's own")
		}
		val bundle = entry.getField("INSTANCE").get(null) as ParsersBundle
		check(bundle.apiVersion == ParsersApi.VERSION) {
			"The parsers are for version ${bundle.apiVersion} of the contract, this app has ${ParsersApi.VERSION}"
		}
		check(bundle.version == manifest.version) {
			"The parsers say they are of version ${bundle.version}, the manifest ${manifest.version}"
		}
		check(bundle.sources.isNotEmpty()) { "The parsers have no sources" }
		return bundle
	}

	fun has(version: Long): Boolean = File(directoryOf(version), MANIFEST_FILE).isFile

	/** Removes the files of [version]. What was loaded from them goes on working until the app is closed. */
	@WorkerThread
	fun delete(version: Long) {
		directoryOf(version).deleteRecursively()
	}

	/** A place to download to, on the same volume as where the parsers end up. */
	fun newDownloadFile(): File {
		root.mkdirs()
		return File(root, DOWNLOAD_FILE)
	}

	/** Removes everything but the parsers of [keep]: older ones, and whatever an interrupted download left. */
	@WorkerThread
	fun deleteAllExcept(keep: Long?) {
		val kept = keep?.let { directoryOf(it).name }
		root.listFiles()?.forEach { file ->
			if (file.name != kept) {
				file.deleteRecursively()
			}
		}
	}

	private fun directoryOf(version: Long) = File(root, "v$version")

	/**
	 * Looks in the downloaded parsers first for any class of theirs, and in the app for everything else.
	 *
	 * The usual order is the other way round, the app first, and it would never do here: the app has a copy of every
	 * parser too, the older one being replaced, and it would win every time. What the two have in common is not in
	 * the downloaded files at all, so that there is one class of it and both sides mean the same one.
	 */
	private class BundleClassLoader(dexPath: String, parent: ClassLoader) : PathClassLoader(dexPath, parent) {

		override fun loadClass(name: String, resolve: Boolean): Class<*> {
			if (name.startsWith(PARSERS_PACKAGE)) {
				findLoadedClass(name)?.let { return it }
				try {
					return findClass(name)
				} catch (_: ClassNotFoundException) {
					// Of the contract: the app's
				}
			}
			return super.loadClass(name, resolve)
		}

		/** Whether a class of this name was taken from the downloaded files, and not from the app. */
		fun hasLoadedItsOwn(name: String): Boolean = findLoadedClass(name)?.classLoader === this
	}

	companion object {

		/**
		 * Whether [className] names code that [bundle] brought with it and has run.
		 *
		 * A name is not enough to tell: the app has a class of the same name for every parser, its own older copy, and
		 * its shrinker is free to move unrelated code of the app into one of those. Only what the downloaded parsers
		 * have loaded themselves is theirs.
		 */
		fun isCodeOf(bundle: ParsersBundle, className: String): Boolean {
			val loader = bundle.javaClass.classLoader as? BundleClassLoader ?: return false
			return className.startsWith(PARSERS_PACKAGE) && loader.hasLoadedItsOwn(className)
		}

		private const val PARSERS_PACKAGE = "org.koitharu.kotatsu.parsers."

		/** `ParsersBundleImpl.CLASS_NAME`, which is not read from the app's own copy of that class on purpose. */
		private const val ENTRY_POINT = "org.koitharu.kotatsu.parsers.core.ParsersBundleImpl"
		private const val MANIFEST_FILE = "manifest.json"
		private const val DOWNLOAD_FILE = "download.zip"
		private const val STAGING_SUFFIX = ".new"

		private fun InputStream.sha256(): String = copyAndHash(null, Long.MAX_VALUE)

		/**
		 * @param limit one byte past this and the copy is given up: an archive can say anything about its sizes
		 */
		private fun InputStream.copyAndHash(output: java.io.OutputStream?, limit: Long): String {
			val digest = MessageDigest.getInstance("SHA-256")
			val buffer = ByteArray(64 * 1024)
			var total = 0L
			while (true) {
				val read = read(buffer)
				if (read < 0) break
				total += read
				if (total > limit) {
					throw IOException("More data than the manifest was signed for")
				}
				digest.update(buffer, 0, read)
				output?.write(buffer, 0, read)
			}
			return digest.digest().joinToString("") { "%02x".format(it) }
		}
	}
}

/** The files of downloaded parsers are not there any more, or not the ones that were checked when they were unpacked. */
class ParsersFilesException(message: String, cause: Throwable? = null) : IOException(message, cause)
