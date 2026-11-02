package org.koitharu.kotatsu.reader.data.erase

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.reader.domain.erase.HiddenImageRule
import org.koitharu.kotatsu.reader.domain.erase.ImageFingerprint
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.erase.PageResolution
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HiddenImagesRepository @Inject constructor(
	database: HiddenImagesDatabase,
) {

	private val dao = database.getDao()
	private var scansSinceTrim = 0

	/** The rules of the entities they were made from. They are asked for by every page, and rarely change. */
	@Volatile
	private var parsedRules: Pair<List<HiddenImageEntity>, List<HiddenImageRule>>? = null

	private val entities: StateFlow<List<HiddenImageEntity>?> = dao.observeAll()
		.stateIn(ProcessLifecycleOwner.get().lifecycleScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	/** Every remembered image, kept in memory: this is read for every page the reader opens. */
	val rules: Flow<List<HiddenImageRule>> = entities.filterNotNull().map { list -> list.map { it.toRule() } }

	/** What the reader erased. The rest of a cut off image found next door is part of that, not an entry. */
	fun observeAll(): Flow<List<HiddenImageEntity>> =
		entities.filterNotNull().map { list -> list.filter { it.parentId == null } }

	suspend fun hasRemainder(parentId: Long, pageId: Long): Boolean = dao.countRemainders(parentId, pageId) > 0

	fun observeHiddenPages(): Flow<Set<Long>> = dao.observeHiddenPages().map { it.toHashSet() }

	suspend fun getRules(): List<HiddenImageRule> {
		val list = entities.filterNotNull().first()
		parsedRules?.let { (source, parsed) -> if (source === list) return parsed }
		return list.map { it.toRule() }.also { parsedRules = list to it }
	}

	suspend fun getHiddenPages(): Set<Long> = dao.getHiddenPages().toHashSet()

	/**
	 * Saves first and returns only once the rule is live, so that nothing the reader does next - however
	 * quickly - can load a page without it.
	 */
	suspend fun add(entity: HiddenImageEntity): Long {
		val id = dao.insert(entity)
		entities.filterNotNull().first { list -> list.any { it.id == id } }
		return id
	}

	suspend fun markPageHidden(pageId: Long, imageId: Long) {
		dao.insertHiddenPage(HiddenPageEntity(pageId, imageId))
	}

	suspend fun delete(id: Long) {
		dao.delete(id)
		entities.filterNotNull().first { list -> list.none { it.id == id } }
	}

	/** For restoring a backup: restoring the same one twice must not stack duplicates. */
	suspend fun restore(entity: HiddenImageEntity) {
		if (dao.countSame(entity.hashHorizontal, entity.hashVertical, entity.pageId) == 0) {
			dao.insert(entity.copy(id = 0L))
		}
	}

	/** Without what was found next door: a rule's id changes on restore, and it is found again anyway. */
	suspend fun dump(): List<HiddenImageEntity> = observeAll().first()

	suspend fun getScan(pageId: Long): PageScan? = dao.getScan(pageId)?.toScan()

	suspend fun saveScan(pageId: Long, scan: PageScan) {
		val mask = (scan.resolution as? PageResolution.Masked)?.mask
		dao.upsertScan(
			PageScanEntity(
				pageId = pageId,
				newestRuleId = scan.newestRuleId,
				ruleIds = scan.resolution.ruleIds.joinToString(","),
				isHidden = scan.resolution is PageResolution.Hidden,
				sourceWidth = mask?.sourceWidth ?: 0,
				sourceHeight = mask?.sourceHeight ?: 0,
				bands = PageMask.encode(mask?.bands.orEmpty()),
				patches = PageMask.encode(mask?.patches.orEmpty()),
				scannedAt = System.currentTimeMillis(),
				matcherVersion = scan.matcherVersion,
			),
		)
		// About 100 bytes each, so this is a couple of MB at most, and rarely worth the query
		if (++scansSinceTrim >= SCANS_PER_TRIM) {
			scansSinceTrim = 0
			dao.trimScans(MAX_SCANS)
		}
	}

	private fun PageScanEntity.toScan(): PageScan? = runCatching {
		val ids = ruleIds.split(',').filter { it.isNotEmpty() }.mapTo(HashSet()) { it.toLong() }
		val resolution = when {
			isHidden -> PageResolution.Hidden(ids)
			ids.isEmpty() -> PageResolution.Visible
			else -> PageResolution.Masked(
				ids,
				PageMask(sourceWidth, sourceHeight, PageMask.decode(bands), PageMask.decode(patches)),
			)
		}
		PageScan(newestRuleId, resolution, matcherVersion)
	}.getOrNull()

	private fun HiddenImageEntity.toRule() = HiddenImageRule(
		id = id,
		fingerprint = ImageFingerprint(hashHorizontal, hashVertical, mean),
		aspect = aspect,
		xStart = xStart,
		xEnd = xEnd,
		signature = signature,
		pageId = pageId,
		yStart = yStart,
		padTop = padTop ?: 0f,
		padBottom = padBottom ?: 0f,
		removesRows = removesRows,
		colour = colour,
		keypoints = keypoints,
	)

	private companion object {
		const val MAX_SCANS = 20_000
		const val SCANS_PER_TRIM = 256
	}
}

/** What was found on a page, and how many remembered images that took into account. */
class PageScan(val newestRuleId: Long, val resolution: PageResolution, val matcherVersion: Int)
