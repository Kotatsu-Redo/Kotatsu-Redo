package org.koitharu.kotatsu.sourcescore.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.await
import kotlin.concurrent.withLock
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class HelloRequest(
	val ssaid: String,
	@SerialName("drm_id") val drmId: String? = null,
)

@Serializable
data class IdentityResponse(
	@SerialName("user_id") val userId: String,
	val nickname: String? = null,
	@SerialName("display_name") val displayName: String = "",
	val tier: Int = 0,
	/** Kept for wire compatibility with older servers. Hardened servers always return false. */
	val restored: Boolean = false,
)

@Serializable
data class NicknameRequest(val nickname: String)

@Serializable
data class FingerprintDto(
	val source: String,
	val key: String,
	val title: String,
	@SerialName("alt_titles") val altTitles: List<String> = emptyList(),
	val year: Int? = null,
	@SerialName("content_type") val contentType: String? = null,
	val nsfw: Boolean = false,
	@SerialName("external_ids") val externalIds: Map<String, String> = emptyMap(),
)

@Serializable
data class ResolveRequest(val works: List<FingerprintDto>)

@Serializable
data class ResolvedDto(
	val source: String,
	val key: String,
	@SerialName("work_id") val workId: String,
	val method: String = "",
	val created: Boolean = false,
)

@Serializable
data class ResolveResponse(val resolved: List<ResolvedDto> = emptyList())

@Serializable
data class RatingResponse(
	@SerialName("work_id") val workId: String,
	val count: Int = 0,
	val average: Double = 0.0,
	val bayesian: Double = 0.0,
	val histogram: List<Int> = emptyList(),
	/** This user's own rating in stars, or null. */
	val mine: Double? = null,
)

@Serializable
data class SetRatingRequest(val value: Int)

/**
 * A comment as the server renders it for this caller.
 *
 * `deleted` is a tombstone: the row survives so the replies under it still make sense, and carries
 * nothing else.
 */
@Serializable
data class CommentDto(
	val id: String,
	@SerialName("work_id") val workId: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	@SerialName("parent_id") val parentId: String? = null,
	val depth: Int = 0,
	val author: String = "",
	val body: String = "",
	@SerialName("is_spoiler") val isSpoiler: Boolean = false,
	val lang: String? = null,
	@SerialName("created_at") val createdAt: String = "",
	val up: Int = 0,
	val down: Int = 0,
	val score: Double = 0.0,
	@SerialName("my_vote") val myVote: Int = 0,
	@SerialName("is_mine") val isMine: Boolean = false,
	val deleted: Boolean = false,
)

@Serializable
data class CommentPageDto(
	val comments: List<CommentDto> = emptyList(),
	val total: Int = 0,
	/** Visible comments per language, for the "also 12 in Spanish" affordance. */
	@SerialName("by_language") val byLanguage: Map<String, Int> = emptyMap(),
	/**
	 * What this server will accept, sent with every page.
	 *
	 * Holding our own copy of the minimum meant the app could refuse a comment the server would have
	 * taken, and a change to the rule needed a release before anyone could use it. Defaulted so an
	 * older server that does not send it still works.
	 */
	val rules: CommentRulesDto = CommentRulesDto(),
)

@Serializable
data class CommentRulesDto(
	@SerialName("min_length") val minLength: Int = 10,
	@SerialName("max_length") val maxLength: Int = 4000,
)

@Serializable
data class PostCommentRequest(
	val body: String,
	@SerialName("parent_id") val parentId: String? = null,
	@SerialName("is_spoiler") val isSpoiler: Boolean = false,
	val lang: String? = null,
)

@Serializable
data class EditCommentRequest(val body: String, val lang: String? = null)

@Serializable
data class VoteRequest(val value: Int)

@Serializable
data class NotificationDto(
	@SerialName("comment_id") val commentId: String,
	@SerialName("work_id") val workId: String,
	@SerialName("chapter_id") val chapterId: String? = null,
	@SerialName("parent_id") val parentId: String? = null,
	val author: String = "",
	val preview: String = "",
	@SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class NotificationsResponse(
	val replies: List<NotificationDto> = emptyList(),
	/**
	 * Store this and send it back as `since`. The server keeps no record of what it has delivered,
	 * so this value is the only thing stopping a reply being announced twice.
	 */
	val cursor: String = "",
)

/**
 * A rejection, as a code plus its data.
 *
 * The server never sends a user-facing sentence: the userbase spans fifteen languages and the server
 * has no business holding translations, so every message is built here from a code and the app's own
 * string resources.
 */
@Serializable
data class ApiErrorDto(
	val error: String = "internal_error",
	/** `filter_blocked`: the user's own word, echoed back untranslated. */
	val term: String? = null,
	val tier: String? = null,
	@SerialName("rules_url") val rulesUrl: String? = null,
	/** `filter_blocked`: what "this was wrong" reports against. */
	@SerialName("block_id") val blockId: String? = null,
	/** `too_short`: the minimum length. */
	val min: Int? = null,
	@SerialName("retry_after_s") val retryAfterSeconds: Long? = null,
	val limit: String? = null,
	@SerialName("moved_to") val movedTo: String? = null,
	val field: String? = null,
) {
	companion object {
		const val FILTER_BLOCKED = "filter_blocked"
		const val TOO_SHORT = "too_short"
		const val CHAIN_DEPTH_EXCEEDED = "chain_depth_exceeded"
		const val RATE_LIMITED = "rate_limited"
		const val BANNED = "banned"
		const val UNAUTHORIZED = "unauthorized"
		const val WORK_MOVED = "work_moved"
		const val NOT_FOUND = "not_found"
	}
}

/** A rejection the user should see explained, as opposed to a network failure they should not. */
class CommunityApiException(
	val status: Int,
	val error: ApiErrorDto,
) : java.io.IOException("HTTP $status: ${error.error}")

@Serializable
data class ProbeDto(
	val source: String,
	val op: String,
	val ok: Int = 0,
	val fail: Int = 0,
	val empty: Int = 0,
	@SerialName("cf_blocked") val cfBlocked: Int = 0,
	@SerialName("p50_ms") val latencyP50Ms: Int = 0,
	@SerialName("p90_ms") val latencyP90Ms: Int = 0,
)

@Serializable
data class ProbeBatchRequest(val region: String, val probes: List<ProbeDto>)

@Serializable
data class SourceScoreDto(
	val source: String,
	val stability: Double,
	val popularity: Double,
	val composite: Double,
	val samples: Int,
)

@Serializable
data class ScoresResponse(
	val region: String,
	@SerialName("generated_at") val generatedAt: String? = null,
	@SerialName("median_composite") val medianComposite: Double = 0.0,
	@SerialName("median_stability") val medianStability: Double = 0.0,
	val sources: List<SourceScoreDto> = emptyList(),
)

/**
 * Client for the community server.
 *
 * Every call is best-effort by contract: the caller treats a failure as "community features
 * unavailable" and carries on. Nothing here may ever block reading a manga.
 */
@Singleton
class CommunityApi @Inject constructor(
	private val settings: CommunitySettings,
	@CommunityHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	/**
	 * Reachability check for the settings screen.
	 *
	 * Everything else in this client degrades to silence when the server is down, which is right in
	 * normal use and useless while setting one up: a missing rating row looks identical to a broken
	 * feature. This is the one call that reports the difference.
	 */
	suspend fun ping(): Boolean = runCatching {
		okHttpClient.newCall(
			Request.Builder().url(settings.serverUrl + "/v1/health").get().build(),
		).await().use { it.isSuccessful }
	}.getOrDefault(false)

	suspend fun hello(): IdentityResponse = helloWithStatus().identity

	/**
	 * @property created true when the server had never seen this secret and made a new account for it.
	 *
	 * Only a restore cares, and it cares a great deal: a recovery key with a typo is still 43 valid
	 * characters, so it does not fail - it names an account nobody has ever used. Without this the
	 * user is shown a successful restore and an empty history.
	 */
	data class HelloResult(val identity: IdentityResponse, val created: Boolean)

	suspend fun helloWithStatus(): HelloResult {
		val body = json.encodeToString(
			HelloRequest(ssaid = settings.androidId(), drmId = settings.drmId()),
		)
		val response = authedClient.newCall(request("/v1/identity/hello").post(body.asJson()).build()).await()
		return response.use { HelloResult(json.decodeFromString(it.requireBody()), it.code == 201) }
	}

	suspend fun setNickname(nickname: String): IdentityResponse {
		val body = json.encodeToString(NicknameRequest(nickname))
		val response = authedClient.newCall(
			request("/v1/identity/nickname").post(body.asJson()).build(),
		).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/** "Delete everything about me": ratings erased, comments tombstoned, identity dropped. */
	suspend fun deleteIdentity() {
		authedClient.newCall(request("/v1/identity/me").delete().build()).await().use {
			check(it.isSuccessful || it.code == 404) { "Delete failed: ${it.code}" }
		}
	}

	suspend fun uploadProbes(probes: List<ProbeDto>) {
		if (probes.isEmpty()) return
		val body = json.encodeToString(ProbeBatchRequest(region = currentRegion(), probes = probes))
		authedClient.newCall(request("/v1/telemetry/probes").post(body.asJson()).build()).await().use {
			check(it.isSuccessful) { "Probe upload failed: ${it.code}" }
		}
	}

	suspend fun resolveWork(fingerprint: FingerprintDto): ResolvedDto? {
		val body = json.encodeToString(ResolveRequest(listOf(fingerprint)))
		val response = authedClient.newCall(request("/v1/works/resolve").post(body.asJson()).build()).await()
		return response.use { json.decodeFromString<ResolveResponse>(it.requireBody()) }.resolved.firstOrNull()
	}

	suspend fun fetchRating(workId: String): RatingResponse {
		val response = authedClient.newCall(request("/v1/works/$workId/rating").get().build()).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/** @param value half-stars: 2 is one star, 10 is five. */
	suspend fun setRating(workId: String, value: Int): RatingResponse {
		val body = json.encodeToString(SetRatingRequest(value))
		val response = authedClient.newCall(
			request("/v1/works/$workId/rating").put(body.asJson()).build(),
		).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	suspend fun clearRating(workId: String): RatingResponse {
		val response = authedClient.newCall(
			request("/v1/works/$workId/rating").delete().build(),
		).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/**
	 * A page of root comments with their replies attached.
	 *
	 * @param chapterId null for the work's own thread. Until chapters are aligned across sources this
	 *  is the chapter number scaled by 100, so 12.5 is 1250.
	 * @param lang show only threads in this language. Languages are deliberately not unioned - a wall
	 *  of comments nobody in the room can read is worse than a short list they can.
	 */
	suspend fun fetchComments(
		workId: String,
		chapterId: Long? = null,
		sort: String = "top",
		lang: String? = null,
		limit: Int = 25,
		offset: Int = 0,
	): CommentPageDto {
		val url = buildString {
			append("/v1/works/").append(workId).append("/comments?sort=").append(sort)
			append("&limit=").append(limit).append("&offset=").append(offset)
			chapterId?.let { append("&chapter=").append(it) }
			lang?.let { append("&lang=").append(it) }
		}
		val response = authedClient.newCall(request(url).get().build()).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	suspend fun postComment(
		workId: String,
		body: String,
		parentId: String? = null,
		chapterId: Long? = null,
		isSpoiler: Boolean = false,
	): CommentDto {
		val payload = json.encodeToString(
			// `lang` is a fallback only: the server detects the language from the text, because that
			// is what chooses which word list applies.
			PostCommentRequest(body, parentId, isSpoiler, Locale.getDefault().language),
		)
		val url = "/v1/works/$workId/comments" + (chapterId?.let { "?chapter=$it" } ?: "")
		val response = authedClient.newCall(request(url).post(payload.asJson()).build()).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	suspend fun editComment(commentId: String, body: String): CommentDto {
		val payload = json.encodeToString(EditCommentRequest(body, Locale.getDefault().language))
		val response = authedClient.newCall(
			request("/v1/comments/$commentId").patch(payload.asJson()).build(),
		).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	suspend fun deleteComment(commentId: String) {
		authedClient.newCall(request("/v1/comments/$commentId").delete().build()).await().use {
			if (!it.isSuccessful) it.requireBody()
		}
	}

	/** @param value +1, -1, or 0 to withdraw. Idempotent, so a retry cannot double-count. */
	suspend fun voteComment(commentId: String, value: Int): CommentDto {
		val payload = json.encodeToString(VoteRequest(value))
		val response = authedClient.newCall(
			request("/v1/comments/$commentId/vote").put(payload.asJson()).build(),
		).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/**
	 * Replies to this user's comments since [since].
	 *
	 * Delivered once: the cursor lives on the device, because a table of who-was-told-what is exactly
	 * the kind of per-user history the server refuses to hold.
	 */
	suspend fun fetchNotifications(since: String?): NotificationsResponse {
		val url = "/v1/notifications" + (since?.takeIf { it.isNotEmpty() }?.let { "?since=$it" } ?: "")
		val response = authedClient.newCall(request(url).get().build()).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/**
	 * "This was wrong", from the rejection dialog.
	 *
	 * The cheapest false-positive signal there is - reported by the one person who just saw exactly
	 * which term stopped them - and it is what eventually demotes a misfiring rule.
	 */
	suspend fun disputeFilterBlock(blockId: String) {
		authedClient.newCall(
			request("/v1/filter/blocks/$blockId/dispute").post("{}".asJson()).build(),
		).await().use { if (!it.isSuccessful) it.requireBody() }
	}

	/**
	 * A copy of everything the server holds about this account, as JSON.
	 *
	 * Returned as text rather than parsed: the point is to hand the user the file the server produced,
	 * and re-encoding it through a data class would quietly drop any field the app does not model.
	 */
	suspend fun exportMyData(): String {
		val response = authedClient.newCall(request("/v1/identity/export").get().build()).await()
		return response.use { it.requireBody() }
	}

	suspend fun fetchScores(): ScoresResponse {
		val url = "/v1/sources/scores?region=${currentRegion()}"
		// Scores are public aggregate data. Do not attach the identity credential: besides being
		// unnecessary, an Authorization header would disclose the user's secret to an endpoint that
		// deliberately does not authenticate and is intended to be shared by HTTP caches.
		val response = okHttpClient.newCall(publicRequest(url).get().build()).await()
		return response.use { json.decodeFromString(it.requireBody()) }
	}

	/**
	 * The same client, plus one rule: a 401 means register and try again.
	 *
	 * The secret is generated on the device and only becomes an account when `identity/hello` tells
	 * the server about it. That used to happen solely inside the daily sync worker - which is
	 * constrained to unmetered Wi-Fi and a healthy battery, so on mobile data it might not run for
	 * days. Until it did, every call went out with a secret the server had never seen, came back 401,
	 * and the feature looked broken: no rating, no comments, and the whole row hidden.
	 *
	 * Doing it here rather than at each call site also covers the case the flag cannot: the server
	 * forgetting a secret it once knew - a fresh instance, a restored backup, an account deleted from
	 * another device. A sticky `isRegistered` would leave the app 401ing forever with no way back.
	 */
	private val authedClient: OkHttpClient by lazy {
		okHttpClient.newBuilder()
			.authenticator(
				Authenticator { _, response ->
					// One retry only. If registering did not help, the 401 is real - a ban, say - and
					// looping would turn it into a flood.
					if (response.priorResponse != null) return@Authenticator null
					settings.isRegistered = false
					if (!registerBlocking()) return@Authenticator null
					response.request
				},
			)
			.build()
	}

	private val registerLock = ReentrantLock()

	/**
	 * Introduces this secret to the server, synchronously, from OkHttp's own thread.
	 *
	 * Uses the bare client: registering must never be able to trigger the authenticator that called
	 * it. The lock serialises a burst of parallel 401s, and the re-check inside means only the first
	 * of them actually sends anything.
	 */
	private fun registerBlocking(): Boolean = registerLock.withLock {
		if (settings.isRegistered) return@withLock true
		android.util.Log.i(
			IDENTITY_TAG,
			"registering: key " + (if (settings.peekSecret() == null) "absent, one will be generated" else "present on this device"),
		)
		val body = json.encodeToString(
			HelloRequest(ssaid = settings.androidId(), drmId = settings.drmId()),
		)
		val ok = runCatching {
			okHttpClient.newCall(
				request("/v1/identity/hello").post(body.asJson()).build(),
			).execute().use { response ->
				// 201 means the server had never seen this key and made a new account for it. 200
				// means it recognised the key and gave the existing one back. The device ids in the
				// request are recorded for ban enforcement only - they are never a way to look an
				// account up, so a phone with no key is always a new account, never a recovered one.
				android.util.Log.i(
					IDENTITY_TAG,
					"hello -> HTTP " + response.code + " (" +
						(if (response.code == 201) "new account created" else "existing account recognised") + ")",
				)
				response.isSuccessful
			}
		}.getOrDefault(false)
		settings.isRegistered = ok
		ok
	}

	private fun request(path: String) = Request.Builder()
		.url(settings.serverUrl + path)
		// The secret *is* the credential. It travels only over TLS and is never logged.
		.header(CommonHeaders.AUTHORIZATION, "Bearer ${settings.requireSecret()}")
		.header("X-Redo-Region", currentRegion())

	private fun publicRequest(path: String) = Request.Builder()
		.url(settings.serverUrl + path)
		.header("X-Redo-Region", currentRegion())

	private fun String.asJson() = toRequestBody(JSON_MEDIA_TYPE)

	/**
	 * The body, or the server's own rejection turned into something renderable.
	 *
	 * Errors arrive as codes rather than prose (the server holds no translations), so a failed call
	 * has to keep its code intact all the way to the string resource that explains it. Throwing a
	 * bare `HTTP 422` here would lose exactly the part the user needs.
	 */
	private fun okhttp3.Response.requireBody(): String {
		val text = body?.string().orEmpty()
		if (isSuccessful) return text
		val parsed = runCatching { json.decodeFromString<ApiErrorDto>(text) }.getOrNull()
		throw CommunityApiException(code, parsed ?: ApiErrorDto(error = "internal_error"))
	}

	/**
	 * Region is derived from the device's own locale and timezone, **never from the IP address**.
	 *
	 * Coarse on purpose: it exists so a source geo-blocked in one region is not demoted everywhere
	 * else, and any finer granularity would start to identify people.
	 */
	fun currentRegion(): String {
		val country = Locale.getDefault().country.uppercase(Locale.ROOT)
		return when {
			country in NORTH_AMERICA -> "NA"
			country in LATAM -> "LATAM"
			country in SOUTH_ASIA -> "SOUTH_ASIA"
			country in MENA -> "MENA"
			country in AFRICA -> "AFRICA"
			country in APAC -> "APAC"
			country in EUROPE -> "EU"
			// Timezone is a weaker signal than the country but still better than guessing.
			TimeZone.getDefault().id.startsWith("Europe/") -> "EU"
			TimeZone.getDefault().id.startsWith("Asia/") -> "APAC"
			else -> "OTHER"
		}
	}

	private companion object {

		const val IDENTITY_TAG = "CommunityIdentity"

		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

		val NORTH_AMERICA = setOf("US", "CA")
		val LATAM = setOf("MX", "BR", "AR", "CL", "CO", "PE", "VE", "EC", "BO", "PY", "UY", "CR", "PA")
		val SOUTH_ASIA = setOf("IN", "PK", "BD", "LK", "NP")
		val MENA = setOf("SA", "AE", "EG", "QA", "KW", "OM", "BH", "JO", "LB", "IQ", "MA", "DZ", "TN")
		val AFRICA = setOf("NG", "ZA", "KE", "GH", "ET", "TZ", "UG", "SN", "CI", "CM")
		val APAC = setOf("JP", "KR", "CN", "TW", "HK", "SG", "MY", "TH", "VN", "PH", "ID", "AU", "NZ")
		val EUROPE = setOf(
			"GB", "IE", "FR", "DE", "ES", "IT", "PT", "NL", "BE", "LU", "CH", "AT", "PL", "CZ", "SK",
			"HU", "RO", "BG", "GR", "SE", "NO", "DK", "FI", "IS", "EE", "LV", "LT", "UA", "RU", "BY",
			"RS", "HR", "SI", "BA", "AL", "MK", "MD", "TR",
		)
	}
}
