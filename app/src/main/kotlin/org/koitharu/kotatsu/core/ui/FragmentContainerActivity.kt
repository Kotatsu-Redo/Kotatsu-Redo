package org.koitharu.kotatsu.core.ui

import android.os.Bundle
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import org.koitharu.kotatsu.core.util.ext.isRtl
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.ActivityContainerBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.main.ui.owners.SnackbarOwner

@AndroidEntryPoint
abstract class FragmentContainerActivity(private val fragmentClass: Class<out Fragment>) :
	BaseActivity<ActivityContainerBinding>(),
	AppBarOwner,
	SnackbarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val snackbarHost: CoordinatorLayout
		get() = viewBinding.root

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityContainerBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val fm = supportFragmentManager
		if (fm.findFragmentById(R.id.container) == null) {
			fm.commit {
				setReorderingAllowed(true)
				replace(R.id.container, fragmentClass, getFragmentExtras())
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val src = insets.getInsets(typeMask)
		val start = false
		val top = true
		val end = false
		val bottom = false
		val left = if (if (v.isRtl) end else start) 0 else src.left
		val topVal = if (top) 0 else src.top
		val right = if (if (v.isRtl) start else end) 0 else src.right
		val bottomVal = if (bottom) 0 else src.bottom
		val newInsets = Insets.of(left, topVal, right, bottomVal)
		return WindowInsetsCompat.Builder(insets).setInsets(typeMask, newInsets).build()
	}

	protected open fun getFragmentExtras(): Bundle? = intent.extras
}
