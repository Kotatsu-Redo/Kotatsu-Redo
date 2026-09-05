package org.koitharu.kotatsu.sync.domain

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
import android.content.Context
import android.os.Bundle
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SyncController @Inject constructor(
	@ApplicationContext context: Context,
	private val dbProvider: Provider<MangaDatabase>,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(arrayOf(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES)) {

	private val authorityHistory = context.getString(R.string.sync_authority_history)
	private val authorityFavourites = context.getString(R.string.sync_authority_favourites)
	private val am = AccountManager.get(context)
	private val accountType = context.getString(R.string.account_type_sync)
	private val mutex = Mutex()
	private val defaultGcPeriod = TimeUnit.DAYS.toMillis(2) // gc period if sync disabled

	override fun onInvalidated(tables: Set<String>) {
		val favourites = (TABLE_FAVOURITES in tables || TABLE_FAVOURITE_CATEGORIES in tables)
			&& !isSyncActiveOrPending(authorityFavourites)
		val history = TABLE_HISTORY in tables && !isSyncActiveOrPending(authorityHistory)
		if (favourites || history) {
			requestSync(favourites, history)
		}
	}

	fun addAccount(activity: Activity, callback: (account: Account?) -> Unit) {
		am.addAccount(accountType, accountType, null, null, activity, { _ -> onAccountUpdated(callback) }, null)
	}

	fun removeAccount(activity: Activity, account: Account, callback: (account: Account?) -> Unit) {
		val handler = AccountManagerCallback<Bundle> { _ -> onAccountUpdated(callback) }
		am.removeAccount(account, activity, handler, null)
	}

	private fun onAccountUpdated(callback: (account: Account?) -> Unit) {
		val account = am.getAccountsByType(accountType).firstOrNull()

		if(account != null) {
			setEnabled(account, syncFavorites = true, syncHistory = true)
			val job = SupervisorJob()
			val dispatcher = Dispatchers.IO
			val scope = CoroutineScope(job + dispatcher)
			scope.launch {
				requestFullSync()
			}
		}

		callback(account)
	}

	fun setEnabled(account: Account, syncFavorites: Boolean, syncHistory: Boolean) {
		ContentResolver.setSyncAutomatically(account, authorityFavourites, syncFavorites)
		ContentResolver.setSyncAutomatically(account, authorityHistory, syncHistory)
		updatePeriodicSync(account, authorityFavourites, syncFavorites)
		updatePeriodicSync(account, authorityHistory, syncHistory)
	}

	/**
	 * Brings the registered periodic syncs in line with the current settings.
	 *
	 * Idempotent, and safe to call with no account. Needed on startup as well as on a settings change:
	 * periodic syncs live in the system, not in the app, so an install that predates this - or one
	 * whose account was added before it - would otherwise never register one.
	 */
	fun updateSyncSchedule() {
		val account = peekAccount() ?: return
		for (authority in arrayOf(authorityFavourites, authorityHistory)) {
			updatePeriodicSync(account, authority, ContentResolver.getSyncAutomatically(account, authority))
		}
	}

	/**
	 * Without this the app only ever syncs when it writes to a synced table, so changes made on another
	 * device never arrive until something changes locally.
	 *
	 * The period is a hint: the system batches periodic syncs with other work and defers them under
	 * Doze, so one runs roughly, not exactly, this often.
	 */
	private fun updatePeriodicSync(account: Account, authority: String, isEnabled: Boolean) {
		val periodSeconds = TimeUnit.HOURS.toSeconds(settings.syncPeriodHours.toLong())
		// A fresh Bundle rather than Bundle.EMPTY: the periodic sync extras are retained by the system
		// and matched on later, and Bundle.EMPTY is an immutable shared instance. An empty bundle still
		// matches an empty bundle, so add and remove continue to refer to the same periodic sync.
		// addPeriodicSync replaces any existing entry with matching extras, so this doubles as an update.
		if (isEnabled && periodSeconds > 0L) {
			ContentResolver.addPeriodicSync(account, authority, Bundle(), periodSeconds)
		} else {
			ContentResolver.removePeriodicSync(account, authority, Bundle())
		}
	}

	fun isEnabled(account: Account): Boolean {
		return ContentResolver.getMasterSyncAutomatically() && (ContentResolver.getSyncAutomatically(
			account,
			authorityFavourites,
		) || ContentResolver.getSyncAutomatically(
			account,
			authorityHistory,
		))
	}

	fun isFavouritesEnabled(account: Account): Boolean {
		return ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(
			account,
			authorityFavourites,
		)
	}

	fun isHistoryEnabled(account: Account): Boolean {
		return ContentResolver.getMasterSyncAutomatically() && ContentResolver.getSyncAutomatically(
			account,
			authorityHistory,
		)
	}

	fun getLastSync(account: Account, authority: String): Long {
		val key = "last_sync_" + authority.substringAfterLast('.')
		val rawValue = am.getUserData(account, key) ?: return 0L
		return rawValue.toLongOrNull() ?: 0L
	}

	fun observeSyncStatus(): Flow<Boolean> = callbackFlow {
		val handle = ContentResolver.addStatusChangeListener(SYNC_OBSERVER_TYPE_ACTIVE) { which ->
			trySendBlocking(which and SYNC_OBSERVER_TYPE_ACTIVE != 0)
		}
		awaitClose { ContentResolver.removeStatusChangeListener(handle) }
	}

	suspend fun requestFullSync() = withContext(Dispatchers.Default) {
		requestSyncImpl(favourites = true, history = true)
	}

	private fun requestSync(favourites: Boolean, history: Boolean) = processLifecycleScope.launch(Dispatchers.Default) {
		requestSyncImpl(favourites = favourites, history = history)
	}

	private suspend fun requestSyncImpl(favourites: Boolean, history: Boolean) = mutex.withLock {
		if (!favourites && !history) {
			return
		}
		val db = dbProvider.get()
		val account = peekAccount()
		if (account == null || !ContentResolver.getMasterSyncAutomatically()) {
			db.gc(favourites, history)
			return
		}
		var gcHistory = false
		var gcFavourites = false
		if (favourites) {
			if (ContentResolver.getSyncAutomatically(account, authorityFavourites)) {
				ContentResolver.requestSync(account, authorityFavourites, Bundle.EMPTY)
			} else {
				gcFavourites = true
			}
		}
		if (history) {
			if (ContentResolver.getSyncAutomatically(account, authorityHistory)) {
				ContentResolver.requestSync(account, authorityHistory, Bundle.EMPTY)
			} else {
				gcHistory = true
			}
		}
		if (gcHistory || gcFavourites) {
			db.gc(gcFavourites, gcHistory)
		}
	}

	private fun peekAccount(): Account? {
		return am.getAccountsByType(accountType).firstOrNull()
	}

	private suspend fun MangaDatabase.gc(favourites: Boolean, history: Boolean) = withTransaction {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		if (history) {
			getHistoryDao().gc(deletedAt)
		}
		if (favourites) {
			getFavouritesDao().gc(deletedAt)
			getFavouriteCategoriesDao().gc(deletedAt)
		}
	}

	private fun isSyncActiveOrPending(authority: String): Boolean {
		val account = peekAccount() ?: return false
		return ContentResolver.isSyncActive(account, authority) || ContentResolver.isSyncPending(account, authority)
	}

	companion object {

		@JvmStatic
		fun setLastSync(context: Context, account: Account, authority: String, time: Long) {
			val key = "last_sync_" + authority.substringAfterLast('.')
			val am = AccountManager.get(context)
			am.setUserData(account, key, time.toString())
		}
	}
}
