package org.koitharu.kotatsu.settings.about

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.parser.bundle.ActiveParsers
import org.koitharu.kotatsu.core.parser.bundle.ParsersUpdater
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
	private val parsersUpdater: ParsersUpdater,
) : BaseViewModel() {

	/** Whether this build, on this phone, takes newer parsers than its own, and the user has not turned that off. */
	val isParsersUpdateSupported: Boolean
		get() = parsersUpdater.isOn

	/** The parsers in use, and again whenever newer ones are. */
	val parsers = ActiveParsers.observe().map {
		val bundle = ActiveParsers.bundle
		ParsersInfo(
			revision = bundle.revision,
			timestamp = bundle.version * 1000L,
			isBuiltIn = ActiveParsers.isBuiltIn,
		)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, null)

	/** Whether newer parsers were found and put in use, or what kept from finding out. */
	val onParsersChecked = MutableEventFlow<Result<Boolean>>()

	val isUpdateSupported = flow {
		emit(appUpdateRepository.isUpdateSupported())
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	val onUpdateAvailable = MutableEventFlow<AppVersion?>()

	fun checkForUpdates() {
		launchLoadingJob {
			val update = appUpdateRepository.fetchUpdate()
			onUpdateAvailable.call(update)
		}
	}

	fun checkParsers() {
		launchLoadingJob {
			onParsersChecked.call(runCatchingCancellable { parsersUpdater.checkNow() })
		}
	}

	data class ParsersInfo(
		val revision: String,
		/** When their code was last changed, in milliseconds since the epoch, or 0 if not known. */
		val timestamp: Long,
		val isBuiltIn: Boolean,
	)
}
