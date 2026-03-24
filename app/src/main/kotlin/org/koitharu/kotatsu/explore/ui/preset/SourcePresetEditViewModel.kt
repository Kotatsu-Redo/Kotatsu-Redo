package org.koitharu.kotatsu.explore.ui.preset

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.SourcePreset
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import javax.inject.Inject

@HiltViewModel
class SourcePresetEditViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val presetsRepository: SourcePresetsRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val presetId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID

	val onSaved = MutableEventFlow<Unit>()
	val preset = MutableStateFlow<SourcePreset?>(null)

	val allLocales: Set<String> = sourcesRepository.allMangaSources
		.mapNotNullTo(LinkedHashSet()) { it.locale.takeIf { l -> l.isNotEmpty() } }

	init {
		launchLoadingJob(Dispatchers.Default) {
			preset.value = if (presetId != NO_ID) {
				presetsRepository.getById(presetId)
			} else {
				null
			}
		}
	}

	fun save(title: String, selectedLanguages: Set<String>) {
		launchLoadingJob(Dispatchers.Default) {
			check(title.isNotEmpty())
			if (presetId == NO_ID) {
				val initialSources = getSourcesForLanguages(selectedLanguages)
				presetsRepository.createPreset(title, selectedLanguages, initialSources)
			} else {
				presetsRepository.updatePreset(presetId, title, selectedLanguages)
			}
			onSaved.call(Unit)
		}
	}

	private fun getSourcesForLanguages(languages: Set<String>): Set<String> {
		if (languages.isEmpty()) return emptySet()
		val skipNsfw = settings.isNsfwContentDisabled
		return sourcesRepository.allMangaSources
			.filter { it.locale in languages && (!skipNsfw || !it.isNsfw()) }
			.mapTo(HashSet()) { it.name }
	}

	companion object {
		const val NO_ID = -1L
	}
}
