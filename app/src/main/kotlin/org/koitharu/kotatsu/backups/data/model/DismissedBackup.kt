package org.koitharu.kotatsu.backups.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.discover.data.DismissedEntry
import org.koitharu.kotatsu.discover.data.UploadState

/** One title the reader is not interested in, under the key it is kept by. */
@Serializable
class DismissedBackup(
	@SerialName("key") val key: String,
	@SerialName("entry") val entry: DismissedEntry,
) {

	companion object {

		/** Whether the server was told is about this device and this moment: it is not written, nor trusted when read. */
		fun of(key: String, entry: DismissedEntry) = DismissedBackup(key, entry.copy(upload = UploadState.NONE))
	}
}
