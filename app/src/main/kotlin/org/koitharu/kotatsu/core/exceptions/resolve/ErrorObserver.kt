package org.koitharu.kotatsu.core.exceptions.resolve

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope

abstract class ErrorObserver(
	protected val host: View,
	protected val fragment: Fragment?,
	private val resolver: ExceptionResolver?,
	private val onResolved: Consumer<Boolean>?,
) : FlowCollector<Throwable> {

	protected open val activity = host.context.findActivity()

	private val lifecycleScope: LifecycleCoroutineScope
		get() = checkNotNull(fragment?.viewLifecycleScope ?: (activity as? LifecycleOwner)?.lifecycle?.coroutineScope)

	protected val fragmentManager: FragmentManager?
		get() = fragment?.childFragmentManager ?: (activity as? AppCompatActivity)?.supportFragmentManager

	protected fun canResolve(error: Throwable): Boolean {
		return resolver != null && ExceptionResolver.canResolve(error)
	}

	/**
	 * Hook for CloudFlare captcha errors. Used to silently start the resolve flow from any screen
	 * that observed an error event — which turned out to be too aggressive: it triggered the
	 * full-screen CloudFlare WebView from reader / tracker / suggestions / favourites whenever any
	 * background-ish flow on those screens hit a CF error. Now a no-op by default; the only screens
	 * that should auto-resolve are those that handle it explicitly (e.g. the catalog via its
	 * `onCaptchaRequired` event). Other screens will just show the standard "Solve" error UI.
	 */
	@Suppress("UNUSED_PARAMETER")
	protected fun tryAutoResolve(error: Throwable): Boolean = false

	protected fun router() = fragment?.router ?: (activity as? FragmentActivity)?.router

	private fun isAlive(): Boolean {
		return when {
			fragment != null -> fragment.view != null
			activity != null -> activity?.isDestroyed == false
			else -> true
		}
	}

	protected fun resolve(error: Throwable) {
		if (isAlive()) {
			lifecycleScope.launch {
				val isResolved = resolver?.resolve(error) == true
				if (isActive) {
					onResolved?.accept(isResolved)
				}
			}
		}
	}
}
