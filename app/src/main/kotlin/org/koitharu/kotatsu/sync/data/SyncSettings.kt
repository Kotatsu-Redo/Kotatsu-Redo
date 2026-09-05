package org.koitharu.kotatsu.sync.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import javax.inject.Inject

class SyncSettings(
	context: Context,
	private val account: Account?,
) {

	@Inject
	constructor(@ApplicationContext context: Context) : this(
		context,
		AccountManager.get(context)?.getAccountsByType(
			context.getString(R.string.account_type_sync),
		)?.firstOrNull(),
	)

	private val accountManager = AccountManager.get(context)
	private val defaultSyncUrl = context.resources.getStringArray(R.array.sync_url_list).first()

	@get:WorkerThread
	@set:WorkerThread
	var syncUrl: String
		get() {
			val stored = account?.let {
				accountManager.getUserData(it, KEY_SYNC_URL)
			}.ifNullOrEmpty { return defaultSyncUrl }.withHttpSchema()
			// An account that was set up against a server that no longer exists would keep pointing at
			// it forever: the address is stored per-account, not read from the picker, so dropping the
			// entry from sync_url_list alone would leave those users silently unable to sync.
			return if (stored.hostOrNull() in RETIRED_HOSTS) defaultSyncUrl else stored
		}
		set(value) {
			account?.let {
				accountManager.setUserData(it, KEY_SYNC_URL, value)
			}
		}

	companion object {

		/**
		 * Servers that have been shut down. Accounts still pointing at one are moved to the current
		 * default rather than left broken.
		 */
		private val RETIRED_HOSTS = setOf("kotatsu.clq.dev")

		private fun String.withHttpSchema(): String = if (isHttpUrl()) {
			this
		} else {
			"http://$this"
		}

		private fun String.hostOrNull(): String? = runCatching { toUri().host }.getOrNull()

		const val KEY_SYNC = "sync"
		const val KEY_SYNC_URL = "host"
		const val KEY_LOGOUT = "logout"
	}
}
