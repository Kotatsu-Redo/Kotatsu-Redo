package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""CREATE TABLE IF NOT EXISTS source_presets (
				preset_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				title TEXT NOT NULL,
				languages TEXT NOT NULL,
				sources TEXT NOT NULL,
				created_at INTEGER NOT NULL,
				sort_key INTEGER NOT NULL,
				deleted_at INTEGER NOT NULL DEFAULT 0
			)""",
		)
	}
}
