package org.koitharu.kotatsu.core

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.internal.platform.PlatformRegistry
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.conscrypt.Conscrypt
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.os.AppValidator
import org.koitharu.kotatsu.core.os.RomCompat
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.CrashReportActivity
import org.koitharu.kotatsu.core.util.SentryInitializer
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.settings.work.WorkScheduleManager
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
open class BaseApp : Application(), Configuration.Provider {

	@Inject
	lateinit var databaseObserversProvider: Provider<Set<@JvmSuppressWildcards InvalidationTracker.Observer>>

	@Inject
	lateinit var activityLifecycleCallbacks: Set<@JvmSuppressWildcards ActivityLifecycleCallbacks>

	@Inject
	lateinit var database: Provider<MangaDatabase>

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var appValidator: AppValidator

	@Inject
	lateinit var workScheduleManager: WorkScheduleManager

	@Inject
	lateinit var localMangaIndexProvider: Provider<LocalMangaIndex>

	@Inject
	@LocalStorageChanges
	lateinit var localStorageChanges: MutableSharedFlow<LocalManga?>

	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	override fun onCreate() {
		super.onCreate()
		PlatformRegistry.applicationContext = this // TODO replace with OkHttp.initialize
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		// The app process is the one that can hang, so ANR reporting is armed here. Everything else
		// Sentry could do stays off; see SentryInitializer.
		SentryInitializer.ensureInitialized(this, enableAnr = true)
		AppCompatDelegate.setDefaultNightMode(settings.theme)
		// TLS 1.3 support for Android < 10
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			Security.insertProviderAt(Conscrypt.newProvider(), 1)
		}
		setupActivityLifecycleCallbacks()
		processLifecycleScope.launch {
			ACRA.errorReporter.putCustomData("isOriginalApp", appValidator.isOriginalApp.getOrNull().toString())
			ACRA.errorReporter.putCustomData("isMiui", RomCompat.isMiui.getOrNull().toString())
		}
		processLifecycleScope.launch(Dispatchers.Default) {
			setupDatabaseObservers()
			localStorageChanges.collect(localMangaIndexProvider.get())
		}
		workScheduleManager.init()
	}

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		if (ACRA.isACRASenderServiceProcess()) {
			return
		}
		if (BuildConfig.SENTRY_DSN.isEmpty()) {
			// No reporting endpoint configured for this build, so don't ask the user to send anything.
			return
		}
		initAcra {
			buildConfigClass = BuildConfig::class.java
			reportFormat = StringFormat.JSON
			// Reports are delivered by SentryReportSender, registered through
			// META-INF/services/org.acra.sender.ReportSenderFactory.
			reportContent = listOf(
				ReportField.PACKAGE_NAME,
				ReportField.INSTALLATION_ID,
				ReportField.APP_VERSION_CODE,
				ReportField.APP_VERSION_NAME,
				ReportField.ANDROID_VERSION,
				ReportField.PHONE_MODEL,
				ReportField.STACK_TRACE,
				ReportField.CRASH_CONFIGURATION,
				ReportField.CUSTOM_DATA,
				// Tells the sender whether this was a real crash or a Throwable.report() call, so the
				// two don't land in Sentry at the same severity.
				ReportField.IS_SILENT,
			)

			dialog {
				// CrashReportActivity draws the prompt; these are the fallback copy ACRA would use if it
				// ever fell back to its own dialog, and are kept in step with the layout's strings.
				reportDialogClass = CrashReportActivity::class.java
				text = getString(R.string.crash_text)
				title = getString(R.string.error_occurred)
				positiveButtonText = getString(R.string.send)
				negativeButtonText = getString(R.string.no_thanks)
				resIcon = R.drawable.ic_alert_outline
			}
		}
	}

	@WorkerThread
	private fun setupDatabaseObservers() {
		val tracker = database.get().invalidationTracker
		databaseObserversProvider.get().forEach {
			tracker.addObserver(it)
		}
	}

	private fun setupActivityLifecycleCallbacks() {
		activityLifecycleCallbacks.forEach {
			registerActivityLifecycleCallbacks(it)
		}
	}
}
