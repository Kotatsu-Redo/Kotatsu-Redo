package org.koitharu.kotatsu.core.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.google.android.material.R as materialR
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBar
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarColors
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarItem
import org.koitharu.kotatsu.main.ui.nav.FloatingNavTheme

/**
 * A [SlidingBottomNavigationView] whose visible face is drawn with Compose as a floating,
 * pill-shaped bar.
 *
 * Material's own [com.google.android.material.navigation.NavigationBarView] cannot produce this
 * layout: its menu view divides the available width evenly between items, so an item carrying both
 * an icon and a label never gets enough room and its text collapses to an ellipsis. Rather than
 * fight that, the inherited menu view is hidden and a sibling ComposeView draws the bar instead.
 *
 * The NavigationBarView underneath is still the source of truth for the menu, so
 * MainNavigationDelegate keeps driving this through the ordinary menu / selectedItemId / listener
 * APIs and does not need to know the face is Compose.
 */
class FloatingBottomNavigationView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SlidingBottomNavigationView(context, attrs) {

	private val composeItemsState = MutableStateFlow<List<FloatingNavBarItem>>(emptyList())
	private val selectedIdState = MutableStateFlow(0)
	private val labeledState = MutableStateFlow(true)
	private val navColorsState = MutableStateFlow(readNavColors())
	private val continueVisibleState = MutableStateFlow(false)
	private var continueClickListener: (() -> Unit)? = null
	private val sourceItems = mutableListOf<NavItem>()
	private val hiddenIds = mutableSetOf<Int>()
	private val badgeCounts = mutableMapOf<Int, Int>()
	private val legacyBackground: Drawable = ColorDrawable(context.getThemeColor(materialR.attr.colorSurfaceContainer))
	private val legacyElevation = elevation
	private var useLegacyNavigation = false
	private var reselectedListener: ((MenuItem) -> Unit)? = null

	private val composeView: ComposeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			FloatingNavTheme {
				val items by composeItemsState.collectAsState()
				val selectedId by selectedIdState.collectAsState()
				val labeled by labeledState.collectAsState()
				val navColors by navColorsState.collectAsState()
				val showContinue by continueVisibleState.collectAsState()
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 8.dp),
					contentAlignment = Alignment.Center,
				) {
					FloatingNavBar(
						items = items,
						selectedId = selectedId,
						showLabels = labeled,
						colors = navColors,
						onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
						onItemReselected = { id ->
							val menuItem = menu.findItem(id) ?: return@FloatingNavBar
							reselectedListener?.invoke(menuItem)
						},
						modifier = Modifier.fillMaxWidth(),
						showContinue = showContinue,
						onContinueClick = { continueClickListener?.invoke() },
					)
				}
			}
		}
	}

	init {
		// The parent paints a solid surface across the whole width - clear it so the pill can float.
		background = null
		elevation = 0f
		// Hide what the superclass added, before composeView joins the tree so it is not caught too.
		syncChildVisibility()
		addView(
			composeView,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			),
		)
	}

	override val maxItemCountOverride: Int
		get() = MAX_RENDERED_ITEMS

	/**
	 * Beside the pill there is only the list showing through, so a tap there is for the list. The pill and its buttons take
	 * their own touches, as Compose surfaces do.
	 */
	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean =
		if (useLegacyNavigation) super.onTouchEvent(event) else false

	override fun setSelectedItemId(@IdRes itemId: Int) {
		super.setSelectedItemId(itemId)
		selectedIdState.value = selectedItemId
	}

	override fun setOnItemReselectedListener(listener: OnItemReselectedListener?) {
		super.setOnItemReselectedListener(listener)
		reselectedListener = listener?.let { l -> { item -> l.onNavigationItemReselected(item) } }
	}

	/** Items configured in settings. At most [MAX_RENDERED_ITEMS] are drawn. */
	fun setComposeItems(items: List<NavItem>) {
		sourceItems.clear()
		sourceItems.addAll(items)
		rebuildComposeItems()
	}

	fun setComposeLabeled(value: Boolean) {
		labeledState.value = value
	}

	/** The round "continue reading" button beside the bar. Ignored while in legacy mode. */
	fun setContinueVisible(value: Boolean) {
		continueVisibleState.value = value
	}

	fun setOnContinueClickListener(listener: (() -> Unit)?) {
		continueClickListener = listener
	}

	/** Falls back to Material's own bar, for when the floating setting is off. */
	fun setUseLegacyNavigation(value: Boolean) {
		if (useLegacyNavigation == value) return
		useLegacyNavigation = value
		navColorsState.value = readNavColors()
		composeView.visibility = if (value) View.GONE else View.VISIBLE
		syncChildVisibility()
		background = if (value) legacyBackground else null
		elevation = if (value) legacyElevation else 0f
	}

	fun setComposeBadge(@IdRes itemId: Int, count: Int) {
		if (count == 0) badgeCounts.remove(itemId) else badgeCounts[itemId] = count
		rebuildComposeItems()
	}

	fun setComposeItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		if (isVisible) hiddenIds.remove(itemId) else hiddenIds.add(itemId)
		rebuildComposeItems()
	}

	private fun rebuildComposeItems() {
		val out = ArrayList<FloatingNavBarItem>(sourceItems.size.coerceAtMost(MAX_RENDERED_ITEMS))
		for (item in sourceItems) {
			if (item.id in hiddenIds) continue
			out += FloatingNavBarItem(
				id = item.id,
				titleRes = item.title,
				icon = item.icon,
				badgeCount = badgeCounts[item.id] ?: 0,
			)
			if (out.size >= MAX_RENDERED_ITEMS) break
		}
		composeItemsState.value = out
		selectedIdState.value = selectedItemId
	}

	private fun syncChildVisibility() {
		// BottomNavigationMenuView is @RestrictTo, so it is identified by exclusion instead.
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child !== composeView) {
				child.visibility = if (useLegacyNavigation) View.VISIBLE else View.GONE
			}
		}
	}

	/**
	 * Colours are read from the theme rather than from the inherited tint lists.
	 *
	 * Those lists resolve through Material macros that assume the stock surface roles, and the
	 * Expressive overlay remaps several of them (colorSurface to colorSurfaceContainerLow, and so on).
	 *
	 * Unselected icons use colorOnSurface rather than the colorOnSurfaceVariant the macro would give:
	 * against this bar's container that variant is dim enough that the inactive icons read as
	 * disabled next to the selected one.
	 */
	private fun readNavColors() = FloatingNavBarColors(
		container = context.getThemeColor(materialR.attr.colorSurfaceContainer),
		selectedContainer = context.getThemeColor(materialR.attr.colorSecondaryContainer),
		selectedContent = context.getThemeColor(materialR.attr.colorOnSecondaryContainer),
		unselectedContent = context.getThemeColor(materialR.attr.colorOnSurface),
	)

	companion object {

		const val MAX_RENDERED_ITEMS = 5
	}
}
