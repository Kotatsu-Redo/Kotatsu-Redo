package org.koitharu.kotatsu.core.util

import android.content.Context
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.User
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import org.koitharu.kotatsu.BuildConfig

/**
 * Delivers ACRA reports to Sentry.
 *
 * ACRA still owns crash *capture* - it keeps the consent dialog, the report fields and the
 * screen-history breadcrumb trail [AcraScreenLogger] builds - and this only replaces the transport
 * that used to POST to the upstream project's Acrarium instance.
 *
 * ACRA hands over a stack trace as plain text, which Sentry cannot group on, so
 * [SentryStackTraceParser] turns it back into structured frames.
 */
class SentryReportSender : ReportSender {

	override fun send(context: Context, errorContent: CrashReportData) {
		if (!ensureSentry(context)) {
			return
		}
		Sentry.captureEvent(errorContent.toSentryEvent())
		// The sender runs in its own short-lived process that may be killed the moment this returns.
		Sentry.flush(FLUSH_TIMEOUT)
	}

	private fun ensureSentry(context: Context): Boolean =
		// The sender runs in its own process, so it has to initialise Sentry itself; ANR reporting
		// belongs to the app process, which is the one that can hang.
		SentryInitializer.ensureInitialized(context, enableAnr = false)

	private fun CrashReportData.toSentryEvent(): SentryEvent {
		val stackTrace = getString(ReportField.STACK_TRACE)
		val event = SentryEvent()
		val exceptions = stackTrace?.let(SentryStackTraceParser::parse).orEmpty()
		if (exceptions.isEmpty()) {
			// Nothing parseable: still send something rather than dropping the report on the floor.
			event.message = Message().apply {
				message = stackTrace ?: toJSON()
			}
		} else {
			event.exceptions = exceptions
		}
		event.level = if (getString(ReportField.IS_SILENT).toBoolean()) SentryLevel.ERROR else SentryLevel.FATAL
		getString(ReportField.INSTALLATION_ID)?.let { installationId ->
			event.user = User().apply { id = installationId }
		}
		getString(ReportField.APP_VERSION_NAME)?.let { versionName ->
			val versionCode = getString(ReportField.APP_VERSION_CODE)
			val packageName = getString(ReportField.PACKAGE_NAME) ?: BuildConfig.APPLICATION_ID
			event.release = "$packageName@$versionName" + versionCode?.let { "+$it" }.orEmpty()
		}
		// The report may be sent long after the crash - even after an app update - so the values
		// captured at crash time are recorded as tags rather than left to Sentry's own device context,
		// which describes the device as it is at send time.
		getString(ReportField.ANDROID_VERSION)?.let { event.setTag("android_version", it) }
		getString(ReportField.PHONE_MODEL)?.let { event.setTag("phone_model", it) }
		get(ReportField.CUSTOM_DATA.name)?.let { event.setExtra("screen_history", it.toString()) }
		get(ReportField.CRASH_CONFIGURATION.name)?.let { event.setExtra("crash_configuration", it.toString()) }
		return event
	}

	companion object {

		private const val FLUSH_TIMEOUT = 15_000L
	}
}

class SentryReportSenderFactory : ReportSenderFactory {

	override fun create(context: Context, config: CoreConfiguration): ReportSender = SentryReportSender()

	override fun enabled(config: CoreConfiguration): Boolean = BuildConfig.SENTRY_DSN.isNotEmpty()
}
