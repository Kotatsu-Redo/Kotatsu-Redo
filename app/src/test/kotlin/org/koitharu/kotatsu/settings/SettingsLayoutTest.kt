package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps the settings tree honest: every setting in exactly one place, nothing depending on something it cannot
 * reach, and every screen it links to existing. A setting that goes missing from these files is still in effect
 * with no way to change it, which nothing else would catch.
 */
class SettingsLayoutTest {

	private class Entry(val file: String, val element: Element) {
		val key: String? = element.getAttribute("android:key").ifEmpty { null }
		val dependency: String? = element.getAttribute("android:dependency").ifEmpty { null }
		val fragment: String? = element.getAttribute("android:fragment").ifEmpty { null }
		val isInfo = element.getAttribute("android:selectable") == "false"
		val isLink = fragment != null
	}

	private val directory = listOf("src/main/res/xml", "app/src/main/res/xml").map(::File).first { it.isDirectory }

	/** The settings screens. Those for one source, or one account on one service, repeat keys on purpose. */
	private val files = directory.listFiles().orEmpty()
		.filter { it.name.startsWith("pref_") && it.name !in setOf("pref_source.xml", "pref_source_parser.xml", "pref_sync_header.xml") }

	private val entries: List<Entry> = files.flatMap { file ->
		val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
		buildList { collect(file.name, document.documentElement, this) }
	}

	private fun collect(file: String, parent: Element, into: MutableList<Entry>) {
		val children = parent.childNodes
		for (i in 0 until children.length) {
			val child = children.item(i)
			if (child.nodeType == Node.ELEMENT_NODE) {
				into += Entry(file, child as Element)
				collect(file, child, into)
			}
		}
	}

	@Test
	fun `every setting is in exactly one place`() {
		val repeated = entries
			.filter { it.key != null && !it.isLink && !it.isInfo }
			.groupBy { it.key }
			.filterValues { it.size > 1 }
			.mapValues { (_, places) -> places.map { it.file } }
		assertEquals(emptyMap<String?, List<String>>(), repeated)
	}

	@Test
	fun `no setting has gone missing`() {
		val present = entries.mapNotNull { it.key }.toSet()
		assertEquals(emptySet<String>(), EVERY_SETTING - present)
	}

	@Test
	fun `what a setting depends on is on the same screen`() {
		val broken = entries.filter { it.dependency != null }.filter { entry ->
			entries.none { it.file == entry.file && it.key == entry.dependency }
		}.map { "${it.file}: ${it.key} needs ${it.dependency}" }
		assertEquals(emptyList<String>(), broken)
	}

	@Test
	fun `every screen linked to exists`() {
		val missing = entries.mapNotNull { it.fragment }.distinct().filterNot { name ->
			runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
		}
		assertEquals(emptyList<String>(), missing)
	}

	@Test
	fun `the first screen has four headings and twelve entries`() {
		val root = entries.filter { it.file == "pref_root.xml" }
		assertEquals(4, root.count { it.element.tagName == "PreferenceCategory" })
		assertEquals(12, root.count { it.isLink })
		assertTrue(root.filter { it.isLink }.all { (it.element.parentNode as Element).tagName == "PreferenceCategory" })
	}

	private companion object {

		/** What the screens held before they were reorganised, less the links between them. */
		val EVERY_SETTING = setOf(
			"app_version", "updates_unstable", "changelog", "about_help", "about_github", "about_app_translation",
			"about_discord", "sponsor",
			"color_theme", "theme", "amoled_theme", "app_locale", "list_mode_2", "grid_size", "quick_filter",
			"progress_indicators", "manga_list_badges", "description_collapse", "pages_tab", "details_tab",
			"search_suggest_types", "nav_main", "main_fab", "nav_labels", "floating_nav", "nav_pinned", "exit_confirm",
			"dynamic_shortcuts", "shortcuts_no_nsfw", "protect_app", "screenshots_policy",
			"backup_periodic", "backup_periodic_output", "backup_periodic_freq", "backup_periodic_trim",
			"backup_periodic_count", "backup_periodic_last", "backup_periodic_tg", "backup_periodic_tg_enabled",
			"backup_periodic_tg_chat_id", "backup_periodic_tg_open", "backup_periodic_tg_test", "backup", "restore",
			"enabled", "telemetry", "account", "nickname", "server", "recovery", "export", "delete", "intro",
			"search_history_clear", "updates_feed_clear", "thumbs_cache_clear", "pages_cache_clear", "http_cache_clear",
			"manga_data_clear", "cookies_clear", "webview_clear", "chapters_clear", "chapters_clear_auto",
			"discord_rpc", "discord_token", "discord_rpc_skip_nsfw",
			"local_manga_dirs", "local_storage", "downloads_format", "downloads_metered_network", "ignore_dose",
			"pages_dir", "pages_dir_ask", "storage_usage", "prefetch_content", "pages_preload", "proxy", "doh",
			"images_proxy_2", "ssl_bypass", "no_offline",
			"tracker_notifications", "notifications_sound", "notifications_vibrate", "notifications_light",
			"tracker_notifications_info", "notifications_settings", "suggestions_notifications",
			"proxy_type_2", "proxy_address", "proxy_port", "proxy_auth", "proxy_login", "proxy_password", "proxy_test",
			"reader_mode", "reader_mode_detect", "zoom_mode", "reader_zoom_buttons", "webtoon_zoom", "webtoon_zoom_out",
			"webtoon_gaps", "reader_controls", "reader_tap_actions", "reader_taps_ltr", "reader_volume_buttons",
			"reader_navigation_inverted", "reader_animation2", "webtoon_pull_gesture", "reader_erase_gesture",
			"hidden_images", "enhanced_colors", "reader_optimize", "reader_crop", "eink_flash", "eink_flash_duration",
			"eink_flash_every", "eink_flash_color", "reader_fullscreen", "reader_orientation", "reader_screen_on",
			"reader_multitask", "reader_bar", "reader_bar_transparent", "reader_chapter_toast", "reader_background",
			"pages_numbers",
			"sync_settings", "community_settings", "suggestions", "related_manga", "stats_on", "reading_time", "anilist",
			"kitsu", "mal", "shikimori",
			"sources_sort_order", "sources_grid", "remote_sources", "sources_enabled_all", "sources_catalog", "no_nsfw",
			"incognito_nsfw", "mirror_switching", "handle_links",
			"suggestions_wifi", "suggestions_disabled_sources", "suggestions_exclude_tags", "suggestions_exclude_nsfw",
			"track_warning", "sync", "sync_period", "host", "logout",
			"tracker_enabled", "tracker_wifi", "tracker_freq", "track_sources", "track_categories", "tracker_no_nsfw",
			"tracker_download", "tracker_debug",
		)
	}
}
