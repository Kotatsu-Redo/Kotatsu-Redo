package org.koitharu.kotatsu.core.util

import android.content.Context
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import org.koitharu.kotatsu.BuildConfig

/**
 * The one place the Sentry SDK is configured, so the app process and ACRA's separate sender process
 * cannot drift apart.
 *
 * Deliberately narrow. Crash capture belongs to ACRA, which asks the user before sending anything, and
 * Sentry is only the transport for what ACRA collects. The single exception is ANRs: ACRA never sees
 * those - the process is wedged rather than crashed - and Android only surfaces them through
 * ApplicationExitInfo on the next start, so nothing but the SDK itself can report them.
 */
object SentryInitializer {

	/**
	 * @param enableAnr report ANRs from the previous run. Only meaningful in the app process; ACRA's
	 * sender process passes `false` because it is short-lived and never the one that hung.
	 * @return whether Sentry is usable afterwards.
	 */
	fun ensureInitialized(context: Context, enableAnr: Boolean): Boolean {
		if (Sentry.isEnabled()) {
			return true
		}
		if (BuildConfig.SENTRY_DSN.isEmpty()) {
			return false
		}
		SentryAndroid.init(context.applicationContext) { options ->
			options.dsn = BuildConfig.SENTRY_DSN
			options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
			options.environment = BuildConfig.BUILD_TYPE

			// ACRA owns crash capture, and the consent dialog in front of it.
			options.isEnableUncaughtExceptionHandler = false

			// ANR v2 reads ApplicationExitInfo on the next start instead of watchdogging a thread, so
			// it costs nothing while the app runs. The thread dump comes from the same record and is
			// what makes the report diagnosable rather than just "something hung".
			options.isAnrEnabled = enableAnr
			options.isAttachAnrThreadDump = enableAnr
			// Off deliberately: this would backfill every ANR Android still remembers the first time a
			// user updates, burying anything current under history nobody can act on.
			options.isReportHistoricalAnrs = false

			// Release health, performance tracing and profiling are all switched off by choice.
			options.isEnableAutoSessionTracking = false
			options.isEnableAutoActivityLifecycleTracing = false

			// Nothing that could carry what the user is reading.
			options.isAttachScreenshot = false
			options.isAttachViewHierarchy = false
			options.isSendDefaultPii = false
			options.isEnableSystemEventBreadcrumbs = false
			options.isEnableNetworkEventBreadcrumbs = false
			// Kept: which screen was open is the main clue for diagnosing an ANR.
			options.isEnableActivityLifecycleBreadcrumbs = enableAnr
		}
		return Sentry.isEnabled()
	}
}
