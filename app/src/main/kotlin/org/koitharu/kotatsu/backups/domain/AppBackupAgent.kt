package org.koitharu.kotatsu.backups.domain

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import com.google.common.io.ByteStreams
import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.EnumSet
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppBackupAgent : BackupAgent() {

	override fun onBackup(
		oldState: ParcelFileDescriptor?,
		data: BackupDataOutput?,
		newState: ParcelFileDescriptor?
	) = Unit

	override fun onRestore(
		data: BackupDataInput?,
		appVersionCode: Int,
		newState: ParcelFileDescriptor?
	) = Unit

	/**
	 * The community identity travels with the automatic backup.
	 *
	 * It already did, incidentally: `backup_content.xml` and `backup_rules.xml` both include every
	 * SharedPreferences file, and `super.onFullBackup` honours that - so the key rode along in
	 * `community.xml` whether or not anyone intended it. Passing [CommunitySettings] here makes the
	 * agent's own archive carry it as well, so the two restore paths agree instead of one silently
	 * doing more than the other.
	 *
	 * The trade is deliberate and worth naming: the key leaves the device through Google's backup
	 * transport. That is the price of comments and ratings surviving a phone that was lost rather than
	 * replaced on purpose.
	 */
	override fun onFullBackup(data: FullBackupDataOutput) {
		super.onFullBackup(data)
		val file = createBackupFile(
			this,
			BackupRepository(
				database = MangaDatabase(context = applicationContext),
				settings = AppSettings(applicationContext),
				tapGridSettings = TapGridSettings(applicationContext),
				mangaSourcesRepository = MangaSourcesRepository(
					context = applicationContext,
					db = MangaDatabase(context = applicationContext),
					settings = AppSettings(applicationContext),
				),
				savedFiltersRepository = SavedFiltersRepository(
					context = applicationContext,
				),
				communitySettings = CommunitySettings(applicationContext),
			),
		)
		try {
			fullBackupFile(file, data)
		} finally {
			file.delete()
		}
	}

	override fun onRestoreFile(
		data: ParcelFileDescriptor,
		size: Long,
		destination: File?,
		type: Int,
		mode: Long,
		mtime: Long
	) {
		if (destination?.name?.endsWith(".bk.zip") == true) {
			restoreBackupFile(
				data.fileDescriptor,
				size,
				BackupRepository(
					database = MangaDatabase(applicationContext),
					settings = AppSettings(applicationContext),
					tapGridSettings = TapGridSettings(applicationContext),
					mangaSourcesRepository = MangaSourcesRepository(
						context = applicationContext,
						db = MangaDatabase(context = applicationContext),
						settings = AppSettings(applicationContext),
					),
					savedFiltersRepository = SavedFiltersRepository(
						context = applicationContext,
					),
					communitySettings = CommunitySettings(applicationContext),
				),
			)
			destination.delete()
		} else {
			super.onRestoreFile(data, size, destination, type, mode, mtime)
		}
	}

	@VisibleForTesting
	fun createBackupFile(context: Context, repository: BackupRepository): File {
		val file = BackupUtils.createTempFile(context)
		ZipOutputStream(file.outputStream()).use { output ->
			runBlocking {
				repository.createBackup(output, null)
			}
		}
		return file
	}

	@VisibleForTesting
	fun restoreBackupFile(fd: FileDescriptor, size: Long, repository: BackupRepository) {
		ZipInputStream(ByteStreams.limit(FileInputStream(fd), size)).use { input ->
			val sections = EnumSet.allOf(BackupSection::class.java)
			// managed externally
			sections.remove(BackupSection.SETTINGS)
			sections.remove(BackupSection.SETTINGS_READER_GRID)
			runBlocking {
				repository.restoreBackup(input, sections, null)
			}
		}
	}
}
