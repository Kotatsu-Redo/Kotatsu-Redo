package org.koitharu.kotatsu.core.exceptions.resolve

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-threads CloudFlare auto-resolve attempts across the whole app so multiple screens (catalog,
 * details, reader, …) all hitting CAPTCHA for the same source at the same time don't stomp on each
 * other's WebView state. The coordinator also OWNS the activity lifecycle — `CloudFlareActivity`
 * reports its result back via [notifyResolveResult], so the result is delivered even if the screen
 * that originally requested the resolve has since been destroyed.
 *
 *  * **Same source already in-flight** → subsequent callers don't launch a new resolve, they just await
 *    the existing one's result. The first session stays alive and the duplicate callers piggy-back on it.
 *  * **Different sources** → queue on the global mutex so only one resolve runs at any moment.
 */
@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val foregroundActivityHolder: ForegroundActivityHolder,
) {

	private val mutex = Mutex()
	// One deferred per source for the *whole* resolve (hidden + visible fallback). Callers await this.
	private val inFlight = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	// One deferred per source for the *current* CloudFlareActivity instance. Completed by
	// [notifyResolveResult] when that activity finishes. Distinct from `inFlight` so the orchestration
	// can launch hidden, observe its result, and then optionally launch the visible fallback.
	private val pendingActivityResult = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	// Wall-clock timestamps of the most recent successful resolve per source. Used to break tight
	// auto-resolve loops where the WebView passes CloudFlare (so we report success) but the parser's
	// OkHttp request immediately hits CF again (different fingerprint) → new captcha event → new
	// auto-resolve → "Solving captcha automatically…" toast retriggers every second.
	private val recentSuccessAt = ConcurrentHashMap<MangaSource, Long>()

	// Long-lived scope so the orchestration survives the originating Fragment / Activity getting
	// destroyed mid-flight. Awaiters in still-alive scopes pick up the result via [inFlight].
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	/** Called by [CloudFlareActivity] when a resolve session it was running finishes. */
	fun notifyResolveResult(source: MangaSource, success: Boolean) {
		pendingActivityResult.remove(source)?.complete(success)
	}

	/**
	 * Runs the full auto-resolve flow (hidden first, then visible fallback on cancel) for [source].
	 *
	 * If a resolve for [source] is already in progress when this is called, the caller awaits the
	 * in-flight resolve's result instead of launching a duplicate — the first session stays alive.
	 */
	suspend fun resolve(source: MangaSource, exception: CloudFlareProtectedException): Boolean {
		// Fast path: same-source resolve already in flight → just await its result.
		inFlight[source]?.let { return it.await() }
		// Loop-break: if we just successfully resolved this source, refuse a new auto-resolve so the
		// catalog falls back to the "Solve" error state instead of silently spinning the WebView.
		val lastSuccess = recentSuccessAt[source]
		if (lastSuccess != null && System.currentTimeMillis() - lastSuccess < RECENT_SUCCESS_COOLDOWN_MS) {
			return false
		}
		// Slow path: claim the slot under the global mutex (only one orchestration runs at a time).
		val deferred: CompletableDeferred<Boolean> = mutex.withLock {
			inFlight[source]?.let { return@withLock it }
			// Re-check the cooldown under the lock too, in case another caller just finished.
			val recheck = recentSuccessAt[source]
			if (recheck != null && System.currentTimeMillis() - recheck < RECENT_SUCCESS_COOLDOWN_MS) {
				return@withLock CompletableDeferred(false)
			}
			val fresh = CompletableDeferred<Boolean>()
			inFlight[source] = fresh
			// Show the "Solving captcha automatically…" toast only here — on the slow path, where we
			// actually kick off a new resolve. Awaiters that piggy-back on this in-flight resolve via
			// the fast path get no toast, so multiple captcha events in quick succession stop stacking
			// toasts on top of the loading text.
			showSolvingToast()
			// Detach the orchestration from the caller's scope so it survives Fragment/Activity death.
			scope.launch { runOrchestration(source, exception, fresh) }
			fresh
		}
		return deferred.await()
	}

	private suspend fun runOrchestration(
		source: MangaSource,
		exception: CloudFlareProtectedException,
		deferred: CompletableDeferred<Boolean>,
	) {
		try {
			val hiddenPassed = launchAndAwait(source, exception, hidden = true)
			val finalResult = if (hiddenPassed) {
				true
			} else {
				launchAndAwait(source, exception, hidden = false)
			}
			if (finalResult) {
				recentSuccessAt[source] = System.currentTimeMillis()
			}
			deferred.complete(finalResult)
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			deferred.complete(false)
		} finally {
			inFlight.remove(source)
			pendingActivityResult.remove(source)
		}
	}

	private suspend fun launchAndAwait(
		source: MangaSource,
		exception: CloudFlareProtectedException,
		hidden: Boolean,
	): Boolean {
		if (source == UnknownMangaSource) return false
		val resultDeferred = CompletableDeferred<Boolean>()
		pendingActivityResult[source] = resultDeferred
		val intent = AppRouter.cloudFlareResolveIntent(context, exception, hidden = hidden).apply {
			putExtra(CloudFlareActivity.EXTRA_AUTO_RESOLVE, true)
		}
		val launcher = foregroundActivityHolder.current
		if (launcher != null) {
			launcher.startActivity(intent)
		} else {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
		}
		return resultDeferred.await()
	}

	private fun showSolvingToast() {
		Handler(Looper.getMainLooper()).post {
			Toast.makeText(context, R.string.captcha_solving, Toast.LENGTH_LONG).show()
		}
	}

	private companion object {

		// How long to refuse a fresh auto-resolve for the same source after a successful one. Long
		// enough to break the "WebView passes, parser still fails, captcha event re-fires" loop;
		// short enough that a legitimate retry minutes later goes through normally.
		const val RECENT_SUCCESS_COOLDOWN_MS = 30_000L
	}
}
