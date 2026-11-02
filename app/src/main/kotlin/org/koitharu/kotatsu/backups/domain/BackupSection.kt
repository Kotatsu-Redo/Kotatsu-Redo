package org.koitharu.kotatsu.backups.domain

import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
	val entryName: String,
) {

	INDEX("index"),
	HISTORY("history"),
	CATEGORIES("categories"),
	FAVOURITES("favourites"),
	SETTINGS("settings"),
	SETTINGS_READER_GRID("reader_grid"),
	BOOKMARKS("bookmarks"),
	SOURCES("sources"),
	SCROBBLING("scrobbling"),
	STATS("statistics"),
	SAVED_FILTERS("saved_filters"),

	/**
	 * The community identity key.
	 *
	 * Included in the backup because it is the only thing that carries a user's comments and ratings
	 * to a new phone - losing it orphans them permanently, and a recovery phrase nobody wrote down is
	 * not a backup.
	 */
	IDENTITY("identity"),

	/** Images erased in the reader: "never again" should survive changing phones. */
	HIDDEN_IMAGES("hidden_images"),
	;

	companion object {

		fun of(entry: ZipEntry): BackupSection? {
			if (entry.isDirectory) {
				return null
			}
			// By file name alone: unzipping a backup and zipping it again usually puts it all in a folder,
			// and which tool did that, or how hard it compressed, makes no difference to what is inside
			val name = entry.name.replace('\\', '/')
				.substringAfterLast('/')
				.removeSuffix(".json")
				.lowercase(Locale.ROOT)
			return entries.find { x -> x.entryName == name }
		}
	}
}
