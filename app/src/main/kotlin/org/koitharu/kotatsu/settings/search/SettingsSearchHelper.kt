package org.koitharu.kotatsu.settings.search

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.XmlRes
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.get
import dagger.Reusable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.settings.AccountsSettingsFragment
import org.koitharu.kotatsu.settings.AppearanceSettingsFragment
import org.koitharu.kotatsu.settings.DownloadsSettingsFragment
import org.koitharu.kotatsu.settings.EInkSettingsFragment
import org.koitharu.kotatsu.settings.NetworkSettingsFragment
import org.koitharu.kotatsu.settings.NotificationsSettingsFragment
import org.koitharu.kotatsu.settings.PrivacySettingsFragment
import org.koitharu.kotatsu.settings.ProxySettingsFragment
import org.koitharu.kotatsu.settings.ReaderSettingsFragment
import org.koitharu.kotatsu.settings.SuggestionsSettingsFragment
import org.koitharu.kotatsu.settings.SyncSettingsFragment
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.discord.DiscordSettingsFragment
import org.koitharu.kotatsu.settings.sources.SourcesSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment
import org.koitharu.kotatsu.settings.userdata.BackupsSettingsFragment
import org.koitharu.kotatsu.settings.userdata.storage.DataCleanupSettingsFragment
import org.koitharu.kotatsu.sourcescore.ui.CommunitySettingsFragment
import javax.inject.Inject

@Reusable
@SuppressLint("RestrictedApi")
class SettingsSearchHelper @Inject constructor(
    @LocalizedAppContext private val context: Context,
) {

    fun inflatePreferences(): List<SettingsItem> {
        val preferenceManager = PreferenceManager(context)
        val result = ArrayList<SettingsItem>()
        val reader = listOf(context.getString(R.string.reader_settings))
        val downloads = listOf(context.getString(R.string.downloads_and_storage))
        val backups = listOf(context.getString(R.string.backup_restore))
        val accounts = listOf(context.getString(R.string.accounts_and_sync))
        val network = listOf(context.getString(R.string.network))
        preferenceManager.inflateTo(result, R.xml.pref_reader, emptyList(), ReaderSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_reader_eink, reader, EInkSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_appearance, emptyList(), AppearanceSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_sources, emptyList(), SourcesSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_suggestions, emptyList(), SuggestionsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_tracker, emptyList(), TrackerSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_downloads, emptyList(), DownloadsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_data_cleanup, downloads, DataCleanupSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_backups, emptyList(), BackupsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_backup_periodic, backups, PeriodicalBackupSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_accounts, emptyList(), AccountsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_sync, accounts, SyncSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_community, accounts, CommunitySettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_discord, accounts, DiscordSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_notifications, emptyList(), NotificationsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_privacy, emptyList(), PrivacySettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_network, emptyList(), NetworkSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_proxy, network, ProxySettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_about, emptyList(), AboutSettingsFragment::class.java)
        return result
    }

    private fun PreferenceManager.inflateTo(
        result: MutableList<SettingsItem>,
        @XmlRes resId: Int,
        breadcrumbs: List<String>,
        fragmentClass: Class<out PreferenceFragmentCompat>
    ) {
        val screen = inflateFromResource(context, resId, null)
        val screenTitle = screen.title?.toString()
        screen.inflateTo(
            result = result,
            breadcrumbs = if (screenTitle.isNullOrEmpty()) breadcrumbs else breadcrumbs + screenTitle,
            fragmentClass = fragmentClass,
        )
    }

    private fun PreferenceGroup.inflateTo(
        result: MutableList<SettingsItem>,
        breadcrumbs: List<String>,
        fragmentClass: Class<out PreferenceFragmentCompat>
    ): Unit = repeat(preferenceCount) { i ->
        val pref = this[i]
        if (pref is PreferenceCategory) {
            // A heading says nothing a setting needs, and the settings under it are as findable as any
            pref.inflateTo(result, breadcrumbs, fragmentClass)
        } else if (pref is PreferenceScreen && pref.preferenceCount > 0) {
            val screenTitle = pref.title?.toString()
            pref.inflateTo(
                result = result,
                breadcrumbs = if (screenTitle.isNullOrEmpty()) breadcrumbs else breadcrumbs + screenTitle,
                fragmentClass = fragmentClass,
            )
        } else if (pref.isVisible) {
            // Anything hidden from the start only shows up in some cases, and searching should not lead nowhere
            result.add(
                SettingsItem(
                    key = pref.key ?: return@repeat,
                    title = pref.title ?: return@repeat,
                    breadcrumbs = breadcrumbs,
                    fragmentClass = fragmentClass,
                ),
            )
        }
    }
}
