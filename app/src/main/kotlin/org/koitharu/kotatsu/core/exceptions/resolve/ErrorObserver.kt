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
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
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
	 * For CloudFlare captcha errors, silently start resolving instead of showing
	 * a "captcha required" message — the resolver falls back to the interactive
	 * screen only if the headless attempt fails.
	 * @return `true` if auto-resolution was started and no error UI should be shown.
	 */
	protected fun tryAutoResolve(error: Throwable): Boolean {
		if (error is CloudFlareProtectedException && canResolve(error)) {
			resolve(error)
			return true
		}
		return false
	}

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
