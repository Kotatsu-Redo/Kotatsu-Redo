package org.koitharu.kotatsu.sourcescore.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.text.Spanned
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.ButtonBarLayout
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import android.widget.Toast
import org.koitharu.kotatsu.sourcescore.data.IdentityResponse
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.stats.ui.views.PieChartView
import javax.inject.Inject

/**
 * Three-step introduction to the community features.
 *
 * Consent is asked for once, in order, and nothing is created before it is given: the identity is
 * generated lazily on first use, so declining at step one means the app never contacts the server and
 * no row exists anywhere (PLAN.md §1).
 *
 * The steps are deliberately separate questions. Bundling "do you want comments" with "may we collect
 * reliability data" into one button would make the second one unanswerable.
 */
@AndroidEntryPoint
class CommunityOnboardingDialog : DialogFragment() {

	@Inject
	lateinit var settings: CommunitySettings

	@Inject
	lateinit var syncScheduler: SourceScoreSyncWorker.Scheduler

	@Inject
	lateinit var api: CommunityApi

	private var step = STEP_INTRO

	/**
	 * Who the server says we already are, when a key survived on this device.
	 *
	 * The identity is the secret and nothing else - the server will not hand an account over on the
	 * strength of a device id, which is the right call and also means a restore can only happen here,
	 * from a key Android's backup brought back or a previous install left behind. When there is one,
	 * asking for a nickname would be asking somebody to name an account they already have.
	 */
	private var restored: IdentityResponse? = null
	private lateinit var flipper: ViewFlipper
	private lateinit var titleView: TextView
	private lateinit var heroIcon: ImageView
	private lateinit var hero: ViewGroup
	private lateinit var dots: List<View>
	private lateinit var nicknameLayout: TextInputLayout
	private lateinit var nicknameInput: TextInputEditText
	private lateinit var nicknamePreview: TextView

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		val view = LayoutInflater.from(context).inflate(R.layout.dialog_community_onboarding, null)
		flipper = view.findViewById(R.id.flipper)
		titleView = view.findViewById(R.id.textView_title)
		heroIcon = view.findViewById(R.id.imageView_hero)
		hero = view.findViewById(R.id.layout_hero)
		dots = listOf(
			view.findViewById(R.id.dot_1),
			view.findViewById(R.id.dot_2),
			view.findViewById(R.id.dot_3),
			view.findViewById(R.id.dot_4),
		)
		nicknameLayout = view.findViewById(R.id.layout_nickname)
		nicknameInput = view.findViewById(R.id.edit_nickname)
		nicknamePreview = view.findViewById(R.id.textView_nickname_preview)
		nicknameInput.doAfterTextChanged { updateNicknamePreview() }
		updateNicknamePreview()

		bindPolicyLinks(view)
		bindDonationSplit(view)

		val dialog = MaterialAlertDialogBuilder(requireContext())
			.setView(view)
			// Non-dismissable: this is a consent decision, and a dialog that can be swiped away
			// leaves the app in a state where the answer is neither yes nor no.
			.setCancelable(false)
			.setPositiveButton(R.string.community_intro_optin, null)
			.setNegativeButton(R.string.community_intro_skip, null)
			// Shown on the first screen only: somebody arriving on a new phone needs the way back to
			// their account before they are offered a new one, not after.
			.setNeutralButton(R.string.community_recover, null)
			.create()

		// Buttons are wired after show() so a step can advance without the dialog dismissing itself.
		dialog.setCanceledOnTouchOutside(false)
		isCancelable = false // also blocks the back gesture, which setCancelable alone does not

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPositive(dialog) }
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onNegative(dialog) }
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { askForKey(dialog) }
			// Three buttons, one row. ButtonBarLayout stacks them the moment they do not fit, and a
			// stacked bar reads as three unrelated choices rather than one question with an aside.
			(dialog.getButton(AlertDialog.BUTTON_POSITIVE).parent as? ButtonBarLayout)
				?.setAllowStacking(false)
			render(dialog)
		}
		return dialog
	}

	/**
	 * The terms and privacy notice, pointed at whichever instance this device is configured for.
	 *
	 * Hardcoding the official instance's URLs would send a self-hoster's users to somebody else's
	 * policies, which is worse than not linking at all - the documents describe the server that holds
	 * the data, and that is whichever one this app is talking to.
	 */
	private fun bindPolicyLinks(view: View) {
		val textView = view.findViewById<TextView>(R.id.textView_policies) ?: return
		val base = settings.serverUrl
		val terms = getString(R.string.community_terms)
		val privacy = getString(R.string.community_privacy)

		val text = getString(R.string.community_policy_links, terms, privacy)
		val spannable = SpannableString(text)
		linkify(spannable, terms, "$base/terms", terms)
		linkify(spannable, privacy, "$base/privacy", privacy)

		textView.text = spannable
		textView.movementMethod = LinkMovementMethod.getInstance()
	}

	private fun linkify(spannable: SpannableString, label: String, url: String, title: String) {
		val start = spannable.indexOf(label)
		if (start < 0) return
		spannable.setSpan(
			object : ClickableSpan() {
				// Kotatsu registers its own VIEW filter, so a bare implicit intent can resolve back
				// into this app. The router picks a browser explicitly.
				override fun onClick(widget: View) {
					router.openExternalBrowser(url, title)
				}
			},
			start,
			start + label.length,
			Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
	}

	private fun render(dialog: AlertDialog) {
		flipper.displayedChild = step
		updateSteps()
		val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
		val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isVisible = step == STEP_INTRO

		when (step) {
			STEP_INTRO -> {
				titleView.setText(R.string.community_intro_title)
				heroIcon.setImageResource(R.drawable.ic_info_outline)
				positive.setText(R.string.community_intro_optin)
				negative.setText(R.string.community_intro_skip)
			}

			STEP_TELEMETRY -> {
				titleView.setText(R.string.community_telemetry_title)
				heroIcon.setImageResource(R.drawable.ic_chart)
				positive.setText(R.string.community_telemetry_yes)
				negative.setText(R.string.community_telemetry_no)
			}

			STEP_NICKNAME -> {
				titleView.setText(R.string.community_nickname_title)
				heroIcon.setImageResource(R.drawable.ic_user)
				positive.setText(R.string.community_nickname_continue)
				negative.setText(R.string.community_nickname_back)
			}

			STEP_SPONSOR -> {
				titleView.setText(R.string.community_sponsor_title)
				heroIcon.setImageResource(R.drawable.ic_heart)
				positive.setText(R.string.community_sponsor_open)
				negative.setText(R.string.community_sponsor_dismiss)
			}
		}
	}

	/**
	 * The hero shape reacts to each step rather than swapping silently - expressive motion is how a
	 * step change reads as progress instead of as the same dialog with different words.
	 */
	private fun animateHero() {
		hero.animate().cancel()
		hero.scaleX = 0.86f
		hero.scaleY = 0.86f
		hero.animate()
			.scaleX(1f)
			.scaleY(1f)
			.setDuration(320)
			.setInterpolator(OvershootInterpolator(1.6f))
			.start()
	}

	/** Active step is a stadium, the others are dots: shape carries the state, not colour alone. */
	private fun updateSteps() {
		dots.forEachIndexed { index, dot ->
			val active = index == step
			dot.setBackgroundResource(
				if (active) R.drawable.bg_onboarding_dot_active else R.drawable.bg_onboarding_dot,
			)
			dot.updateLayoutParams { width = if (active) activeDotWidth else inactiveDotWidth }
		}
	}

	private fun onPositive(dialog: AlertDialog) {
		when (step) {
			STEP_INTRO -> {
				settings.isEnabled = true
				tryRestore()
				// Answered here, not at the last step. The consent question is this one; the rest are
				// follow-ups. Marking it only on the sponsor screen meant anyone who opted in and did
				// not walk all four steps got asked again on every single launch.
				settings.hasSeenOnboarding = true
				advance(dialog, STEP_TELEMETRY)
			}

			STEP_TELEMETRY -> {
				settings.isTelemetryEnabled = true
				advance(dialog, nextAfterTelemetry())
			}

			// Validated here rather than on the server round trip, so a typo is caught before the
			// step advances and the user has to come back to Settings to fix it.
			STEP_NICKNAME -> if (saveNickname()) advance(dialog, STEP_SPONSOR)

			STEP_SPONSOR -> {
				openSponsorPage()
				finish(dialog)
			}
		}
	}

	private fun onNegative(dialog: AlertDialog) {
		when (step) {
			// Declining the feature declines everything that needs an identity: no key, no telemetry,
			// nothing about this user stored. Public score downloads still run for everyone.
			STEP_INTRO -> {
				settings.isEnabled = false
				settings.isTelemetryEnabled = false
				finish(dialog)
			}

			STEP_TELEMETRY -> {
				settings.isTelemetryEnabled = false
				advance(dialog, nextAfterTelemetry())
			}

			// There is no anonymous participation: a name is part of joining, so the only way out
			// of this step is back to the opt-in, where declining is still a real choice.
			STEP_NICKNAME -> advance(dialog, STEP_INTRO)

			STEP_SPONSOR -> finish(dialog)
		}
	}

	/**
	 * Takes a recovery key and proves it belongs to somebody before keeping it.
	 *
	 * The proof is the whole point. A key with one character wrong is still 43 valid characters, so
	 * it does not fail - it names an account that has never existed, and the user is shown a
	 * successful restore and an empty history. `created` in the server's answer is the only thing
	 * that tells the two apart, so a key the server had to invent an account for is rejected and the
	 * previous key put back.
	 */
	private fun askForKey(parent: AlertDialog) {
		val view = LayoutInflater.from(requireContext())
			.inflate(R.layout.dialog_community_recover, null)
		val layout = view.findViewById<TextInputLayout>(R.id.layout_key)
		val input = view.findViewById<TextInputEditText>(R.id.edit_key)
		input.doAfterTextChanged { layout.error = null }

		val sheet = MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.community_recover_title)
			.setView(view)
			.setPositiveButton(R.string.community_recover_action, null)
			.setNegativeButton(android.R.string.cancel, null)
			.create()

		sheet.setOnShowListener {
			sheet.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val key = input.text?.toString()?.trim().orEmpty()
				if (!settings.isPlausibleSecret(key)) {
					layout.error = getString(R.string.community_recover_malformed)
					return@setOnClickListener
				}
				val button = sheet.getButton(AlertDialog.BUTTON_POSITIVE)
				button.isEnabled = false
				lifecycleScope.launch {
					when (val outcome = adopt(key)) {
						is Recovery.Restored -> {
							sheet.dismiss()
							restored = outcome.identity
							settings.isEnabled = true
							settings.hasSeenOnboarding = true
							Toast.makeText(
								requireContext(),
								getString(R.string.community_recover_done, outcome.identity.displayName),
								Toast.LENGTH_LONG,
							).show()
							syncScheduler.schedule()
							advance(parent, STEP_TELEMETRY)
						}

						Recovery.Unknown -> layout.error = getString(R.string.community_recover_unknown)
						Recovery.Unreachable -> layout.error = getString(R.string.community_recover_failed)
					}
					button.isEnabled = true
				}
			}
		}
		sheet.show()
	}

	private sealed interface Recovery {
		data class Restored(val identity: IdentityResponse) : Recovery
		data object Unknown : Recovery
		data object Unreachable : Recovery
	}

	private suspend fun adopt(key: String): Recovery {
		val previous = settings.peekSecret()
		if (!settings.adoptSecret(key)) return Recovery.Unknown
		val result = runCatchingCancellable { api.helloWithStatus() }.getOrNull()
		if (result == null) {
			settings.revertSecret(previous)
			return Recovery.Unreachable
		}
		if (result.created) {
			// The server had never seen this key, so it just made an empty account for it. Undo both
			// halves: the key here, and nothing to do about the stray account beyond leaving it idle.
			settings.revertSecret(previous)
			return Recovery.Unknown
		}
		settings.isRegistered = true
		settings.nickname = result.identity.nickname
		settings.syncedNickname = result.identity.nickname
		settings.replyCursor = null
		settings.lastProbeUploadDay = Long.MIN_VALUE
		return Recovery.Restored(result.identity)
	}

	/**
	 * Asks the server whether this key is already somebody, in the background, while the telemetry
	 * question is on screen - so by the time the nickname step would appear the answer is usually in.
	 *
	 * Failure is silent and costs only the skip: an unreachable server means the nickname step is
	 * shown as normal, which is the same place the user would have been anyway.
	 */
	private fun tryRestore() {
		// Device identifiers are abuse-control signals, never account credentials. With no surviving
		// key there is therefore nothing to recover and saying hello would only create a new account.
		if (settings.peekSecret() == null) return
		android.util.Log.i(TAG_LOG, "restore: asking the server whether it knows the saved key")
		lifecycleScope.launch {
			val result = runCatchingCancellable { api.helloWithStatus() }.getOrNull() ?: run {
				android.util.Log.w(TAG_LOG, "restore: could not reach the server")
				return@launch
			}
			settings.isRegistered = true
			val identity = result.identity
			if (result.created || identity.nickname.isNullOrEmpty()) {
				android.util.Log.i(TAG_LOG, "restore: nothing to come back to, so we will ask as usual")
				return@launch
			}
			android.util.Log.i(
				TAG_LOG,
				"restore: recovered " + identity.displayName + " by key",
			)
			restored = identity
			settings.nickname = identity.nickname
			settings.syncedNickname = identity.nickname
			// Said out loud rather than silently skipping a step: coming back to an account you had
			// forgotten about is exactly the moment to be told whose it is.
			Toast.makeText(
				requireContext(),
				getString(R.string.community_recover_done, identity.displayName),
				Toast.LENGTH_LONG,
			).show()
		}
	}

	/** Skips the nickname question for somebody who already has one. */
	private fun nextAfterTelemetry(): Int =
		if (restored?.nickname.isNullOrEmpty()) STEP_NICKNAME else STEP_SPONSOR

	/**
	 * @return true when the nickname is acceptable and stored. It is pushed to the server by the sync
	 * job rather than here, because at this point the identity may not be registered yet.
	 */
	private fun saveNickname(): Boolean {
		val value = nicknameInput.text?.toString()?.trim().orEmpty()
		if (value.isEmpty()) {
			nicknameLayout.error = getString(R.string.community_nickname_required)
			return false
		}
		if (value.length < NICKNAME_MIN) {
			nicknameLayout.error = getString(R.string.community_nickname_too_short)
			return false
		}
		if (value.any { !it.isLetterOrDigit() && !it.isWhitespace() && it != '_' && it != '-' }) {
			nicknameLayout.error = getString(R.string.community_nickname_invalid)
			return false
		}
		nicknameLayout.error = null
		settings.nickname = value
		return true
	}

	/** Shows the discriminator as it will actually appear, so it is not a surprise later. */
	private fun updateNicknamePreview() {
		nicknameLayout.error = null
		val typed = nicknameInput.text?.toString()?.trim().orEmpty()
		nicknamePreview.isVisible = typed.isNotEmpty()
		nicknamePreview.text = getString(R.string.community_nickname_preview, "$typed#••••")
	}

	private fun advance(dialog: AlertDialog, next: Int) {
		step = next
		render(dialog)
		animateHero()
	}

	private fun finish(dialog: AlertDialog) {
		settings.hasSeenOnboarding = true
		// Scheduling reads the toggles, so it both starts and cancels the job correctly.
		syncScheduler.schedule()
		dialog.dismiss()
	}

	/**
	 * Routed through [AppRouter] rather than a bare ACTION_VIEW intent: Kotatsu registers its own VIEW
	 * filter for manga urls, so an implicit intent can be caught by the app itself.
	 */
	private fun openSponsorPage() {
		router.openExternalBrowser(SPONSOR_URL, getString(R.string.community_sponsor))
	}

	private val activeDotWidth by lazy { resources.getDimensionPixelSize(R.dimen.onboarding_dot_active) }
	private val inactiveDotWidth by lazy { resources.getDimensionPixelSize(R.dimen.onboarding_dot) }

	/**
	 * The split shown at step three. Server costs come out first, and what remains is divided evenly
	 * between translation work and the V Foundation for Cancer Research - so the chart shows the
	 * halves as equal and the server slice as the deduction it is.
	 */
	private fun bindDonationSplit(view: View) {
		val chart = view.findViewById<PieChartView>(R.id.chart_split)
		val entries = listOf(
			Triple(R.id.legend_translation, R.string.community_sponsor_split_translation, COLOR_TRANSLATION),
			Triple(R.id.legend_charity, R.string.community_sponsor_split_charity, COLOR_CHARITY),
			Triple(R.id.legend_server, R.string.community_sponsor_split_server, COLOR_SERVER),
		)
		val percents = listOf(SHARE_TRANSLATION, SHARE_CHARITY, SHARE_SERVER)

		chart.setData(
			entries.mapIndexed { index, (_, labelRes, color) ->
				PieChartView.Segment(
					value = (percents[index] * 100).toInt(),
					label = getString(labelRes),
					percent = percents[index],
					color = color,
					tag = null,
				)
			},
		)

		entries.forEachIndexed { index, (viewId, labelRes, color) ->
			view.findViewById<TextView>(viewId).apply {
				val percent = getString(R.string.percent_format, (percents[index] * 100).toInt())
				text = "$percent  ${getString(labelRes)}"
				setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_dot_small, 0, 0, 0)
				TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(color))
			}
		}
	}

	companion object {

		private const val TAG = "community_onboarding"

		/** Shared with CommunityApi so one logcat filter shows the whole identity story. */
		private const val TAG_LOG = "CommunityIdentity"
		private const val STEP_INTRO = 0
		private const val STEP_TELEMETRY = 1
		private const val STEP_NICKNAME = 2
		private const val STEP_SPONSOR = 3

		private const val NICKNAME_MIN = 2

		const val SPONSOR_URL = "https://github.com/sponsors/Kotatsu-Redo"

		/** Server first, then an even split of what is left - which is what the wording promises. */
		private const val SHARE_SERVER = 0.20f
		private const val SHARE_TRANSLATION = 0.40f
		private const val SHARE_CHARITY = 0.40f

		private const val COLOR_TRANSLATION = 0xFF4E9F5B.toInt()
		private const val COLOR_CHARITY = 0xFFC0504D.toInt()
		private const val COLOR_SERVER = 0xFF7A7A7A.toInt()

		fun showIfNeeded(context: Context, settings: CommunitySettings, fm: FragmentManager) {
			// Either flag is proof the question has been put. `isEnabled` is set the moment somebody
			// opts in, so it covers anyone who answered on a build that only recorded the answer at
			// the last step - they would otherwise be asked again on every single launch, forever.
			if (settings.hasSeenOnboarding || settings.isEnabled) return
			if (fm.findFragmentByTag(TAG) != null) return
			CommunityOnboardingDialog().show(fm, TAG)
		}

		fun show(fm: FragmentManager) {
			if (fm.findFragmentByTag(TAG) == null) CommunityOnboardingDialog().show(fm, TAG)
		}
	}
}
