package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 27 -> 28
 *
 * - Migrate legacy `nsfw` boolean column into `content_rating` where appropriate
 * - Remove the `nsfw` column by recreating the `manga` table without it
 */
class Migration27To28 : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create new table without `nsfw` column. Keep content_rating and populate it from nsfw.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manga_new (
                manga_id INTEGER NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                alt_title TEXT,
                url TEXT NOT NULL,
                public_url TEXT,
                rating REAL NOT NULL,
                content_rating TEXT,
                cover_url TEXT NOT NULL,
                large_cover_url TEXT,
                state TEXT,
                author TEXT,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Copy rows, setting content_rating = 'ADULT' when nsfw = 1 and content_rating is null/empty
        db.execSQL(
            """
            INSERT INTO manga_new (manga_id, title, alt_title, url, public_url, rating, content_rating, cover_url, large_cover_url, state, author, source)
            SELECT manga_id, title, alt_title, url, public_url, rating,
                   CASE WHEN nsfw = 1 AND (content_rating IS NULL OR content_rating = '') THEN 'ADULT' ELSE content_rating END,
                   cover_url, large_cover_url, state, author, source
            FROM manga
            """.trimIndent()
        )

        // Drop old table and rename new one
        db.execSQL("DROP TABLE IF EXISTS manga")
        db.execSQL("ALTER TABLE manga_new RENAME TO manga")
    }
}
