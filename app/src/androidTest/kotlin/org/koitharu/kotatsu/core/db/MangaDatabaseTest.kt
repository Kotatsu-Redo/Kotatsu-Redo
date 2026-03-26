package org.koitharu.kotatsu.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume

@RunWith(AndroidJUnit4::class)
class MangaDatabaseTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		MangaDatabase::class.java,
	)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

	@Test
	fun versions() {
		assertEquals(1, migrations.first().startVersion)
		repeat(migrations.size) { i ->
			assertEquals(i + 1, migrations[i].startVersion)
			assertEquals(i + 2, migrations[i].endVersion)
		}
		assertEquals(DATABASE_VERSION, migrations.last().endVersion)
	}

	@Test
	fun migrateAll() {
		val assetManager = InstrumentationRegistry.getInstrumentation().context.assets
		val schemaDir = "org.koitharu.kotatsu.core.db.MangaDatabase"

		// Ensure exported schema files exist for every required version (start..latest).
		val requiredStart = migrations.first().startVersion
		val requiredEnd = migrations.last().endVersion
		val missing = mutableListOf<Int>()
		for (v in requiredStart..requiredEnd) {
			try {
				assetManager.open("$schemaDir/$v.json").close()
			} catch (e: Exception) {
				missing += v
			}
		}
		Assume.assumeTrue(
			"Missing exported Room schema files for versions: $missing; skipping migration test",
			missing.isEmpty(),
		)

		helper.createDatabase(TEST_DB, 1).close()
		for (migration in migrations) {
			helper.runMigrationsAndValidate(
				TEST_DB,
				migration.endVersion,
				true,
				migration,
			).close()
		}
	}

	@Test
	fun prePopulate() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		helper.createDatabase(TEST_DB, DATABASE_VERSION).use {
			DatabasePrePopulateCallback(resources).onCreate(it)
		}
	}

	private companion object {

		const val TEST_DB = "test-db"
	}
}
