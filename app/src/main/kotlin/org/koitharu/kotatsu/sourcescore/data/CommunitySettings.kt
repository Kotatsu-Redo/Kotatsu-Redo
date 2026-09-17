package org.koitharu.kotatsu.sourcescore.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity and configuration for the community server.
 *
 * Kept in its own preferences file rather than added to [org.koitharu.kotatsu.core.prefs.AppSettings]
 * so the whole feature stays revertible, and so "turn it off" can mean "delete this file" rather than
 * "hope nothing else was stored in there".
 */
@Singleton
class CommunitySettings @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val prefs: SharedPreferences
		get() = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/**
	 * Master switch for everything that needs an identity: comments, ratings and the telemetry upload.
	 * With this off no identity is ever created and nothing about this user is ever sent, which is
	 * stricter than a server-side opt-out and is the point. Score downloads are not behind it: they are
	 * a public, identity-free fetch that every user benefits from.
	 */
	var isEnabled: Boolean
		get() = prefs.getBoolean(KEY_ENABLED, false)
		set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

	/**
	 * Telemetry is a separate toggle from comments and ratings, because it carries no identity at
	 * all. Turning off community features should not silently stop source scoring from working, and
	 * vice versa.
	 */
	var isTelemetryEnabled: Boolean
		get() = prefs.getBoolean(KEY_TELEMETRY, false)
		set(value) = prefs.edit { putBoolean(KEY_TELEMETRY, value) }

	/**
	 * How far reply notifications have been delivered.
	 *
	 * Lives on the device because the server keeps no record of what it has sent - a table of
	 * who-was-told-what is exactly the kind of per-user history this design refuses to hold. Losing
	 * it costs at most one repeated announcement, and nothing else.
	 */
	var replyCursor: String?
		get() = prefs.getString(KEY_REPLY_CURSOR, null)
		set(value) = prefs.edit { putString(KEY_REPLY_CURSOR, value) }

	var serverUrl: String
		get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
		set(value) {
			val normalized = value.trimEnd('/')
			val changed = normalized != serverUrl
			prefs.edit {
				putString(KEY_SERVER, normalized)
				if (changed) remove(KEY_LAST_PROBE_UPLOAD_DAY)
			}
		}

	/**
	 * Whether the three-step introduction has been answered. Separate from [isEnabled] because
	 * "declined" and "never asked" are different states: only the second should show the dialog.
	 */
	var hasSeenOnboarding: Boolean
		get() = prefs.getBoolean(KEY_ONBOARDED, false)
		set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

	var nickname: String?
		get() = prefs.getString(KEY_NICKNAME, null)
		set(value) = prefs.edit { putString(KEY_NICKNAME, value) }

	/** The nickname the server has actually accepted, so a failed push is retried and a no-op is not. */
	var syncedNickname: String?
		get() = prefs.getString(KEY_SYNCED_NICKNAME, null)
		set(value) = prefs.edit { putString(KEY_SYNCED_NICKNAME, value) }

	var lastScoreSyncAt: Long
		get() = prefs.getLong(KEY_LAST_SYNC, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

	/**
	 * UTC epoch day of the last successful probe upload.
	 *
	 * The server stores one replaceable snapshot per reporter/day. Local counters are deltas, so a
	 * second successful upload on the same day would replace the first snapshot with only the later
	 * delta. Deferring that later delta until tomorrow keeps both uploads truthful and idempotent.
	 */
	var lastProbeUploadDay: Long
		get() = prefs.getLong(KEY_LAST_PROBE_UPLOAD_DAY, Long.MIN_VALUE)
		set(value) = prefs.edit { putLong(KEY_LAST_PROBE_UPLOAD_DAY, value) }

	/**
	 * True once `identity/hello` has succeeded.
	 *
	 * The bearer key remains the only account credential. Device values can be sent again when a
	 * server forgets its registration, but they are only abuse-control signals and can never recover
	 * or select an account.
	 */
	var isRegistered: Boolean
		get() = prefs.getBoolean(KEY_REGISTERED, false)
		set(value) = prefs.edit { putBoolean(KEY_REGISTERED, value) }

	/**
	 * The 32-byte secret that *is* the identity, created lazily on first use and never on launch.
	 *
	 * Deliberately not wrapped in hardware-backed Keystore: the secret has to be exportable, because
	 * it rides in the app backup and can be shown as a recovery phrase so an identity survives a new
	 * phone. Encrypting at rest what is also printed on screen would be theatre.
	 */
	fun requireSecret(): String {
		prefs.getString(KEY_SECRET, null)?.let { return it }
		val generated = RecoveryKey.generate()
		prefs.edit { putString(KEY_SECRET, generated) }
		return generated
	}

	fun peekSecret(): String? = prefs.getString(KEY_SECRET, null)

	/**
	 * Takes on an identity from a recovery key - a restored backup, or one typed in from another
	 * phone.
	 *
	 * Everything describing *this install's* relationship with the server is cleared: registration
	 * happens again so the new device is recorded, and the synced-nickname marker is dropped so the
	 * adopted nickname is pushed rather than assumed to be already there. The key itself is all that
	 * carries over, because the key is the identity.
	 *
	 * Having the same key on two phones is fine - they are the same user.
	 *
	 * @return false when the value is not a plausible key, so a typo cannot silently orphan the
	 * user's existing comments. See [RecoveryKey] for why that check is tested so carefully.
	 */
	fun adoptSecret(secret: String): Boolean {
		if (!RecoveryKey.isPlausible(secret)) return false
		prefs.edit {
			putString(KEY_SECRET, RecoveryKey.normalize(secret))
			remove(KEY_REGISTERED)
			remove(KEY_SYNCED_NICKNAME)
		}
		return true
	}

	/**
	 * Puts back a key that [adoptSecret] replaced, for a restore that turned out not to name anybody.
	 *
	 * @param previous the value [peekSecret] returned before adopting, or null if there was none - in
	 *  which case the key is dropped entirely and the next call generates a fresh one.
	 */
	fun revertSecret(previous: String?) {
		prefs.edit {
			if (previous == null) remove(KEY_SECRET) else putString(KEY_SECRET, previous)
			remove(KEY_REGISTERED)
			remove(KEY_SYNCED_NICKNAME)
		}
	}

	fun isPlausibleSecret(value: String): Boolean = RecoveryKey.isPlausible(value)

	/**
	 * Deletes everything local, including the key.
	 *
	 * The server side is a separate call the caller makes *first* - if that fails the identity must
	 * survive, or the user is left with data on a server they can no longer prove they own.
	 */
	fun forget() = prefs.edit { clear() }

	/**
	 * `ANDROID_ID`: stable across reinstalls of the same signing key, reset by a factory reset, and -
	 * unlike the DRM identifier - it never collides between devices. That is why it is the only value
	 * the server will ban on automatically.
	 */
	@SuppressLint("HardwareIds")
	fun androidId(): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
		?: ""

	/**
	 * The Widevine device id often survives a factory reset, which is the gap ANDROID_ID leaves - but
	 * a measurable share of devices share one, and degoogled ROMs have no Widevine at all. The server
	 * treats it only as a flag for a human to confirm, never as grounds for a ban.
	 */
	fun drmId(): String? = runCatching {
		val mediaDrm = android.media.MediaDrm(WIDEVINE_UUID)
		try {
			val bytes = mediaDrm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
			Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		} finally {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
				mediaDrm.close()
			} else {
				@Suppress("DEPRECATION")
				mediaDrm.release()
			}
		}
	}.getOrNull()

	companion object {

		/** Its own preferences file, so "turn it off" can mean "delete this file". */
		const val FILE_NAME = "community"
		const val KEY_ENABLED = "enabled"
		const val KEY_TELEMETRY = "telemetry"
		const val KEY_SERVER = "server"
		const val KEY_REPLY_CURSOR = "reply_cursor"
		const val KEY_SECRET = "secret"
		const val KEY_LAST_SYNC = "last_sync"
		const val KEY_LAST_PROBE_UPLOAD_DAY = "last_probe_upload_day"
		const val KEY_REGISTERED = "registered"
		const val KEY_ONBOARDED = "onboarded"
		const val KEY_NICKNAME = "nickname"
		const val KEY_SYNCED_NICKNAME = "nickname_synced"
		const val DEFAULT_SERVER = "https://community.kotatsuredo.app"

		val WIDEVINE_UUID: java.util.UUID =
			java.util.UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
	}
}
