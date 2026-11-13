package org.koitharu.kotatsu.suggestions.ui

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.annotation.FloatRange
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.text.HtmlCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.parseAsHtml
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.request.ImageRequest
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.getLocale
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.awaitWorkInfosByTag
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.flatten
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.core.util.ext.trySetForeground
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.discover.domain.ReaderProfiles
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.discover.data.DismissedIndex
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.suggestions.domain.CommunitySuggestionsSource
import org.koitharu.kotatsu.suggestions.domain.MangaSuggestion
import org.koitharu.kotatsu.suggestions.domain.SeedCandidate
import org.koitharu.kotatsu.suggestions.domain.SuggestionRanking
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.TagStatistics
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random
import androidx.appcompat.R as appcompatR

@HiltWorker
class SuggestionsWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val coil: ImageLoader,
	private val suggestionRepository: SuggestionRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val appSettings: AppSettings,
	private val captchaHandler: CaptchaHandler,
	private val workManager: WorkManager,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val sourcesRepository: MangaSourcesRepository,
	private val communitySuggestions: CommunitySuggestionsSource,
	private val tagStatistics: TagStatistics,
	private val readerProfiles: ReaderProfiles,
	private val dismissals: DiscoverDismissals,
) : CoroutineWorker(appContext, params) {

	private val notificationManager by lazy { NotificationManagerCompat.from(appContext) }

	override suspend fun doWork(): Result {
		// This is up to dozens of requests, and parsing them, on the sites and the processor the reader is
		// waiting on. Nothing about it is urgent unless they asked for it, so it waits until they are done.
		if (TAG_ONESHOT !in tags && isAppOnScreen()) {
			return Result.retry()
		}
		trySetForeground()
		if (!appSettings.isSuggestionsEnabled) {
			suggestionRepository.clear()
			return Result.success()
		}
		val count = doWorkImpl()
		val outputData = workDataOf(DATA_COUNT to count)
		return Result.success(outputData)
	}

	private fun isAppOnScreen(): Boolean {
		val state = ActivityManager.RunningAppProcessInfo()
		ActivityManager.getMyMemoryState(state)
		return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		val title = applicationContext.getString(R.string.suggestions_updating)
		val channel = NotificationChannelCompat.Builder(WORKER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
			.setName(title)
			.setShowBadge(true)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(true)
			.build()
		notificationManager.createNotificationChannel(channel)

		val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
			.setContentTitle(title)
			.setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.suggestionsSettingsIntent(applicationContext),
					0,
					false,
				),
			).addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				workManager.createCancelPendingIntent(id),
			)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setDefaults(0)
			.setOngoing(false)
			.setSilent(true)
			.setProgress(0, 0, true)
			.setSmallIcon(android.R.drawable.stat_notify_sync)
			.setForegroundServiceBehavior(
				if (TAG_ONESHOT in tags) {
					NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
				} else {
					NotificationCompat.FOREGROUND_SERVICE_DEFERRED
				},
			)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val actionIntent = PendingIntentCompat.getActivity(
				applicationContext, SETTINGS_ACTION_CODE,
				Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
					.putExtra(Settings.EXTRA_CHANNEL_ID, WORKER_CHANNEL_ID),
				0, false,
			)
			notification.addAction(
				R.drawable.ic_settings,
				applicationContext.getString(R.string.notifications_settings),
				actionIntent,
			)
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(WORKER_NOTIFICATION_ID, notification.build())
		}
	}

	private suspend fun doWorkImpl(): Int {
		val profile = runCatchingCancellable { readerProfiles.get() }.onFailure { it.printStackTraceDebug() }.getOrNull()
		// "Not interested" said on the Explore screen goes for this list too
		val dismissed = runCatchingCancellable { dismissals.get() }.onFailure { it.printStackTraceDebug() }.getOrDefault(DismissedIndex.EMPTY)
		val taste = DiscoverRanking.applyDismissals(profile?.taste.orEmpty(), dismissed.tagLists)
		val history = historyRepository.getListWithProgress(0, MAX_SEED_POOL)
		val favourites = favouritesRepository.getLastManga(20)
		val favouriteIds = favourites.mapTo(HashSet()) { it.id }
		// Opened, barely read and left for weeks: what the reader turned away from, not a taste to
		// echo. A favourite is never that, however little of it has been read.
		val dropped = history.filter { (manga, progress) ->
			manga.id !in favouriteIds && progress.isDropped()
		}.map { it.manga }
		val droppedIds = dropped.mapTo(HashSet()) { it.id }
		// What they have been enjoying and not all alike, since only so many can be asked about
		val candidates = history.mapIndexed { index, (manga, progress) ->
			SeedCandidate(manga, DiscoverRanking.tagsOf(manga), SuggestionRanking.seedWeight(index, progress.percent, manga.id in favouriteIds, pagesRead = profile?.pagesRead?.get(manga.id) ?: 0))
		} + favourites.mapIndexed { index, manga ->
			SeedCandidate(manga, DiscoverRanking.tagsOf(manga), SuggestionRanking.seedWeight(index, -1f, isFavourite = true))
		}
		val seed = SuggestionRanking.pickSeeds(
			candidates.distinctBy { it.item.id }.filterNot { it.item.id in droppedIds },
			MAX_SEED_CANDIDATES,
		)
		val sources = getSources()
		if (seed.isEmpty() || sources.isEmpty()) {
			return 0
		}
		val tagsBlacklist = TagsBlacklist(appSettings.suggestionsTagsBlacklist, TAG_EQ_THRESHOLD)
		val excludeNsfw = appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled

		// The server knows which works are the same work across sources, which sources still carry
		// them, and genres many readers agree on - none of which a per-source tag search can see.
		// It is asked first, and everything below remains exactly as it was for when it cannot
		// answer: community features off, no network, a library nobody else has opened.
		val remote = runCatchingCancellable {
			communitySuggestions.getSuggestions(seed, dropped, MAX_RESULTS, tagsBlacklist, excludeNsfw, taste, dismissed) { work ->
				profile?.knows(work.title, work.sources.map { it.source to it.key }) == true
			}
		}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
			// The server only knows its own name for a work and the addresses it has seen
			.filterNot { profile?.knows(it.manga) == true || dismissed.matches(it.manga) }

		val suggestions = if (remote.size >= MIN_REMOTE_RESULTS) {
			remote
		} else {
			val local = localSuggestions(seed, sources, tagsBlacklist)
				.filterNot { profile?.knows(it.manga) == true || dismissed.matches(it.manga) }
			// Not cut off at 1: that left most of the list tied at the top, in no order at all
			val relevances = SuggestionRanking.rescale(
				local.map { DiscoverRanking.personalize(it.relevance.toDouble(), it.manga.tags.map { tag -> tag.title }, taste) },
			)
			local.mapIndexed { index, suggestion -> suggestion.copy(relevance = relevances[index]) }
				.sortedByDescending { it.relevance }
		}
		return publish(suggestions, tagsBlacklist)
	}

	/**
	 * The original: ask every enabled source for the tags the reader's own library is made of, then
	 * rank what comes back by how much of that it echoes.
	 */
	private suspend fun localSuggestions(
		seed: List<Manga>,
		sources: List<MangaSource>,
		tagsBlacklist: TagsBlacklist,
	): List<MangaSuggestion> {
		// The reader's most telling tags rather than their most frequent: frequency alone always
		// crowns `action`, and a source searched for `action` answers with its best-sellers.
		val statistics = runCatchingCancellable { tagStatistics.snapshot() }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
		val tags = SuggestionRanking.distinctiveTags(
			// "Adaptation" is rare and tells nothing, which is just what would put it first
			seedTags = seed.flatMap { it.tags.map { x -> x.title } }.filterNot(DiscoverRanking::isNoiseTag),
			rarity = { title -> statistics?.rarity(title) ?: 1.0 },
			limit = 10,
		)

		val semaphore = Semaphore(MAX_PARALLELISM)
		val producer = channelFlow {
			for (it in sources) {
				if (it.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)) {
					continue
				}
				launch {
					semaphore.withPermit {
						send(getList(it, tags, tagsBlacklist))
					}
				}
			}
		}
		return producer
			.flatten()
			.take(MAX_RAW_RESULTS)
			.map { manga ->
				MangaSuggestion(
					manga = manga,
					relevance = computeRelevance(manga.tags, tags),
				)
			}.toList()
			// Best first: higher is a closer tag match, and only the first MAX_RESULTS are kept
			.sortedByDescending { it.relevance }
			.take(MAX_RESULTS)
	}

	/** Stores the list and, if the reader asked for it, picks one of the best to notify about. */
	private suspend fun publish(suggestions: List<MangaSuggestion>, tagsBlacklist: TagsBlacklist): Int {
		if (suggestions.isEmpty()) {
			return 0
		}
		suggestionRepository.replace(suggestions)
		if (appSettings.isSuggestionsNotificationAvailable
			&& applicationContext.checkNotificationPermission(MANGA_CHANNEL_ID)
		) {
			for (i in 0..3) {
				try {
					// The best third, and never an empty range: a short remote list is still a list.
					val manga = suggestions[Random.nextInt(0, (suggestions.size / 3).coerceAtLeast(1))]
					val details = mangaRepositoryFactory.create(manga.manga.source)
						.getDetails(manga.manga)
					if (details.chapters.isNullOrEmpty()) {
						continue
					}
					if (details.rating > 0 && details.rating < RATING_MIN) {
						continue
					}
					if (details.isNsfw() && (appSettings.isSuggestionsExcludeNsfw || appSettings.isNsfwContentDisabled)) {
						continue
					}
					if (details in tagsBlacklist) {
						continue
					}
					showNotification(details)
					break
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					e.printStackTraceDebug()
				}
			}
		}
		return suggestions.size
	}

	private suspend fun getSources(): List<MangaSource> {
		if (appSettings.isSuggestionsIncludeDisabledSources) {
			val result = sourcesRepository.allMangaSources.toMutableList<MangaSource>()
			result.addAll(sourcesRepository.getExternalSources())
			result.shuffle()
			result.sortWith(compareBy(nullsLast(LocaleComparator())) { it.getLocale() })
			return result
		} else {
			return sourcesRepository.getEnabledSources().shuffled()
		}
	}

	private suspend fun getList(
		source: MangaSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
	): List<Manga> = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(source)
		val availableOrders = repository.sortOrders
		val order = preferredSortOrders.first { it in availableOrders }
		val availableTags = repository.getFilterOptions().availableTags.filterNot { it in blacklist }
		val matched = tags.mapNotNull { title -> DiscoverRanking.findTag(availableTags, title, TAG_EQ_THRESHOLD) }.distinct()
		// A source carrying none of the reader's tags used to be searched with no tag at all, which
		// is its whole catalogue, newest or most popular first - nothing to do with the reader.
		val first = matched.firstOrNull() ?: return@runCatchingCancellable emptyList()
		// Two telling tags together find what is actually alike; one alone casts a wide net
		val narrow = if (repository.filterCapabilities.isMultipleTagsSupported) matched.take(2).toSet() else setOf(first)
		var list = repository.getList(
			offset = 0,
			order = order,
			filter = MangaListFilter(tags = narrow),
		).asArrayList()
		if (list.isEmpty() && narrow.size > 1) {
			list = repository.getList(
				offset = 0,
				order = order,
				filter = MangaListFilter(tags = setOf(first)),
			).asArrayList()
		}
		if (appSettings.isSuggestionsExcludeNsfw) {
			list.removeAll { it.isNsfw() }
		}
		if (blacklist.isNotEmpty()) {
			list.removeAll { manga -> manga in blacklist }
		}
		list.shuffle()
		list.take(MAX_SOURCE_RESULTS)
	}.onFailure { e ->
		if (e is CloudFlareException) {
			// Background suggestion fetch: just record state, no headless WebView attempt — would
			// otherwise pop the captcha overlay on users mid-reading.
			captchaHandler.handle(e, tryAutoResolve = false)
		}
		e.printStackTraceDebug()
	}.getOrDefault(emptyList())

	@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
	private suspend fun showNotification(manga: Manga) {
		val channel = NotificationChannelCompat.Builder(MANGA_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.suggestions))
			.setDescription(applicationContext.getString(R.string.suggestions_summary))
			.setLightsEnabled(true)
			.setShowBadge(true)
			.build()
		notificationManager.createNotificationChannel(channel)

		val id = manga.url.hashCode()
		val title = applicationContext.getString(R.string.suggestion_manga, manga.title)
		val builder = NotificationCompat.Builder(applicationContext, MANGA_CHANNEL_ID)
		val tagsText = manga.tags.joinToString(", ") { it.title }
		with(builder) {
			setContentText(tagsText)
			setContentTitle(title)
			setGroup(GROUP_SUGGESTION)
			setLargeIcon(
				coil.execute(
					ImageRequest.Builder(applicationContext)
						.data(manga.coverUrl)
						.mangaSourceExtra(manga.source)
						.build(),
				).toBitmapOrNull(),
			)
			setSmallIcon(R.drawable.ic_stat_suggestion)
			val description = manga.description?.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT)?.sanitize()
			if (!description.isNullOrBlank()) {
				val style = NotificationCompat.BigTextStyle()
				style.bigText(
					buildSpannedString {
						append(tagsText)
						val chaptersCount = manga.chapters.sizeOrZero()
						appendLine()
						bold {
							append(
								applicationContext.resources.getQuantityStringSafe(
									R.plurals.chapters,
									chaptersCount,
									chaptersCount,
								),
							)
						}
						appendLine()
						append(description)
					},
				)
				style.setBigContentTitle(title)
				setStyle(style)
			}
			val intent = AppRouter.detailsIntent(applicationContext, manga)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					id,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setAutoCancel(true)
			setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
			setVisibility(if (manga.isNsfw()) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
			setShortcutId(manga.id.toString())
			priority = NotificationCompat.PRIORITY_DEFAULT

			addAction(
				R.drawable.ic_read,
				applicationContext.getString(R.string.read),
				PendingIntentCompat.getActivity(
					applicationContext,
					id + 2,
					ReaderIntent.Builder(applicationContext).manga(manga).build().intent,
					0,
					false,
				),
			)

			addAction(
				R.drawable.ic_suggestion,
				applicationContext.getString(R.string.more),
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.suggestionsIntent(applicationContext),
					0,
					false,
				),
			)
		}
		notificationManager.notify(TAG, id, builder.build())
	}

	@FloatRange(from = 0.0, to = 1.0)
	private fun MangaHistory.isDropped(): Boolean = SuggestionRanking.isDropped(percent, updatedAt, Instant.now())

	private fun computeRelevance(mangaTags: Set<MangaTag>, allTags: List<String>): Float {
		val maxWeight = (allTags.size + allTags.size + 1 - mangaTags.size) * mangaTags.size / 2.0
		val weight = mangaTags.sumOf { tag ->
			val index = allTags.inexactIndexOf(tag.title, TAG_EQ_THRESHOLD)
			if (index < 0) 0 else allTags.size - index
		}
		return (weight / maxWeight).pow(2.0).toFloat()
	}

	private fun Iterable<String>.inexactIndexOf(element: String, threshold: Float): Int {
		forEachIndexed { i, t ->
			if (t.almostEquals(element, threshold)) {
				return i
			}
		}
		return -1
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val settings: AppSettings,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<SuggestionsWorker>(6, TimeUnit.HOURS)
				.setConstraints(createConstraints())
				.addTag(TAG)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
				.build()
			workManager
				.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
				.await()
		}

		override suspend fun unschedule() {
			workManager
				.cancelUniqueWork(TAG)
				.await()
		}

		override suspend fun isScheduled(): Boolean {
			return workManager
				.awaitUniqueWorkInfoByName(TAG)
				.any { !it.state.isFinished }
		}

		suspend fun startNow() {
			if (workManager.awaitWorkInfosByTag(TAG_ONESHOT).any { !it.state.isFinished }) {
				return
			}
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = OneTimeWorkRequestBuilder<SuggestionsWorker>()
				.setConstraints(constraints)
				.addTag(TAG_ONESHOT)
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.build()
			workManager.enqueue(request).await()
		}

		private fun createConstraints() = Constraints.Builder()
			.setRequiredNetworkType(if (settings.isSuggestionsWiFiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {

		const val TAG = "suggestions"
		const val TAG_ONESHOT = "suggestions_oneshot"
		const val DATA_COUNT = "count"
		const val WORKER_CHANNEL_ID = "suggestion_worker"
		const val MANGA_CHANNEL_ID = "suggestions"
		const val GROUP_SUGGESTION = "org.koitharu.kotatsu.SUGGESTIONS"
		const val WORKER_NOTIFICATION_ID = 36
		const val MAX_RESULTS = 160

		/** More than can be asked about, so a local file or an adult title among them does not leave fewer. */
		const val MAX_SEED_CANDIDATES = 16

		/** Deep enough that a long series they went back to matters, without reading the whole history. */
		const val MAX_SEED_POOL = 40

		/**
		 * Below this the server has told us too little to be worth showing on its own - a library of
		 * works nobody else has opened yet - so the local sweep runs instead.
		 */
		const val MIN_REMOTE_RESULTS = 12
		const val MAX_PARALLELISM = 3
		const val MAX_SOURCE_RESULTS = 20
		const val MAX_RAW_RESULTS = 280
		const val TAG_EQ_THRESHOLD = 0.4f
		const val RATING_MIN = 0.5f
		const val SETTINGS_ACTION_CODE = 4

		// Popularity last: it hands back the same best-sellers whatever the reader likes
		val preferredSortOrders = listOf(
			SortOrder.UPDATED,
			SortOrder.NEWEST,
			SortOrder.RATING,
			SortOrder.POPULARITY,
		)
	}
}
