package org.koitharu.kotatsu.core.util

import android.os.Build
import android.webkit.MimeTypeMap
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.nio.file.Files
import coil3.util.MimeTypeMap as CoilMimeTypeMap

object MimeTypes {

	// Neither Coil's table nor the platform's knows JPEG XL. Without this, .jxl pages are not
	// recognised as images in local archives, and downloaded ones are saved without an extension.
	private const val EXTENSION_JXL = "jxl"
	val JXL = MimeType("image/jxl")

	// The platform's table only knows AVIF on recent Android versions
	private const val EXTENSION_AVIF = "avif"
	val AVIF = MimeType("image/avif")

	fun getMimeTypeFromExtension(fileName: String): MimeType? {
		val extension = getNormalizedExtension(fileName) ?: return null
		if (extension == EXTENSION_JXL) {
			return JXL
		}
		return CoilMimeTypeMap.getMimeTypeFromExtension(extension)?.toMimeTypeOrNull()
	}

	fun getMimeTypeFromUrl(url: String): MimeType? {
		val fileName = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
		if (getNormalizedExtension(fileName) == EXTENSION_JXL) {
			return JXL
		}
		return CoilMimeTypeMap.getMimeTypeFromUrl(url)?.toMimeTypeOrNull()
	}

	fun getExtension(mimeType: MimeType?): String? {
		if (mimeType == JXL) {
			return EXTENSION_JXL
		}
		if (mimeType == AVIF) {
			return EXTENSION_AVIF
		}
		return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType?.toString() ?: return null)?.nullIfEmpty()
	}

	@Blocking
	fun probeMimeType(file: File): MimeType? {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			runCatchingCancellable {
				Files.probeContentType(file.toPath())?.toMimeTypeOrNull()
			}.getOrNull()?.let { return it }
		}
		return getMimeTypeFromExtension(file.name)
	}

	fun getNormalizedExtension(name: String): String? = name
		.lowercase()
		.removeSuffix('~')
		.removeSuffix(".tmp")
		.substringAfterLast('.', "")
		.takeIf { it.length in 2..5 }
}
