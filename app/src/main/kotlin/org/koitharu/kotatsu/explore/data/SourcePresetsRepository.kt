package org.koitharu.kotatsu.explore.data

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.mapItems
import javax.inject.Inject

@Reusable
class SourcePresetsRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	private val dao: SourcePresetsDao
		get() = db.getSourcePresetsDao()

	fun observeAll(): Flow<List<SourcePreset>> {
		return dao.observeAll().mapItems { it.toSourcePreset() }
	}

	fun observe(id: Long): Flow<SourcePreset?> {
		return dao.observe(id).map { it?.toSourcePreset() }
	}

	suspend fun getAll(): List<SourcePreset> {
		return dao.findAll().map { it.toSourcePreset() }
	}

	suspend fun getById(id: Long): SourcePreset? {
		return dao.find(id)?.toSourcePreset()
	}

	suspend fun createPreset(title: String, languages: Set<String>, sources: Set<String>): SourcePreset {
		val entity = SourcePresetEntity(
			presetId = 0,
			title = title,
			languages = languages.joinToString(","),
			sources = sources.joinToString(","),
			createdAt = System.currentTimeMillis(),
			sortKey = dao.getNextSortKey(),
			deletedAt = 0L,
		)
		val id = dao.insert(entity)
		return entity.copy(presetId = id).toSourcePreset()
	}

	suspend fun updatePreset(id: Long, title: String, languages: Set<String>) {
		dao.update(id, title, languages.joinToString(","))
	}

	suspend fun updatePresetSources(id: Long, sources: Set<String>) {
		dao.updateSources(id, sources.joinToString(","))
	}

	suspend fun deletePreset(id: Long) {
		dao.delete(id)
	}
}
