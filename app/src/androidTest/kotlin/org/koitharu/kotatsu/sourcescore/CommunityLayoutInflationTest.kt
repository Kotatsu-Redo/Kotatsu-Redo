package org.koitharu.kotatsu.sourcescore

import android.view.LayoutInflater
import android.widget.ViewFlipper
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R

/**
 * Inflates the community layouts against the real app theme.
 *
 * Two failure modes live here and neither is visible to the compiler or to AAPT:
 *
 *  1. **A style that does not resolve.** The community UI uses `Widget.Material3Expressive.*`, while
 *     the app's base theme is `Theme.Material3.DayNight`. A missing theme attribute is an
 *     `InflateException` at runtime, on the one screen nobody opens in a unit test.
 *  2. **A step count that drifts.** The onboarding `ViewFlipper` indexes by child, so a step made of
 *     several loose views silently shifts every later step. That shipped once: the nickname step
 *     displayed the policy text and there was no input field on screen to type into.
 */
@RunWith(AndroidJUnit4::class)
class CommunityLayoutInflationTest {

	private val context = ContextThemeWrapper(
		InstrumentationRegistry.getInstrumentation().targetContext,
		R.style.Theme_Kotatsu,
	)
	private val inflater get() = LayoutInflater.from(context)

	@Test
	@UiThreadTest
	fun theCommentSheetInflates() {
		val view = inflater.inflate(R.layout.sheet_community_comments, null)
		assertNotNull(view.findViewById(R.id.recyclerView))
		assertNotNull(view.findViewById(R.id.progressIndicator))
	}

	@Test
	@UiThreadTest
	fun aCommentRowInflates() {
		val view = inflater.inflate(R.layout.item_community_comment, null)
		assertNotNull(view.findViewById(R.id.button_up))
		assertNotNull(view.findViewById(R.id.button_down))
		assertNotNull(view.findViewById(R.id.button_reply))
		assertNotNull(view.findViewById(R.id.button_edit))
		assertNotNull(view.findViewById(R.id.button_delete))
	}

	// The rating row lives in activity_details, which cannot be inflated from a bare themed context:
	// CoverImageView needs the app's image-loading graph. Covering it would mean launching the real
	// activity with Hilt, which is a heavier harness than the one bug it would catch. The Expressive
	// widgets it uses are the same ones aCommentRowInflates already proves resolve.

	@Test
	@UiThreadTest
	fun theRecoveryDialogInflates() {
		val view = inflater.inflate(R.layout.dialog_community_recover, null)
		assertNotNull(view.findViewById(R.id.layout_key))
		assertNotNull(view.findViewById(R.id.edit_key))
	}

	@Test
	@UiThreadTest
	fun theOnboardingHasOneFlipperChildPerStep() {
		val view = inflater.inflate(R.layout.dialog_community_onboarding, null)
		val flipper = view.findViewById<ViewFlipper>(R.id.flipper)
		assertNotNull(flipper)
		// intro, telemetry, nickname, sponsor - and four dots to match.
		assertEquals("one flipper child per step", 4, flipper.childCount)

		// The nickname step is child 2, and it is the one that must carry the input field.
		val nicknameStep = flipper.getChildAt(2)
		assertNotNull(
			"the nickname step must contain the nickname input",
			nicknameStep.findViewById(R.id.edit_nickname),
		)
	}
}
