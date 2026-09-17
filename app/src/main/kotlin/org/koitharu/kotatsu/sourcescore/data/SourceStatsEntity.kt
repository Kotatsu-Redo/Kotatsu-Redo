package org.koitharu.kotatsu.sourcescore.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * This device's own experience of a source.
 *
 * The reason it exists at all: a source can be perfectly healthy globally and completely broken *for
 * this user* - geo-blocked, behind a captcha the device keeps failing, or blocked by their ISP's DNS.
 * A purely server-side score would keep recommending it forever. Local stats are also what make
 * ranking work offline and on the very first launch, before any score has been downloaded.
 */
@Entity(tableName = "source_stats")
data class SourceStatsEntity(
	@PrimaryKey
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "ok_count") val okCount: Int = 0,
	@ColumnInfo(name = "fail_count") val failCount: Int = 0,
	@ColumnInfo(name = "empty_count") val emptyCount: Int = 0,
	@ColumnInfo(name = "cf_count") val cfCount: Int = 0,
	/** Exponential moving average, so a single slow request does not define the source. */
	@ColumnInfo(name = "latency_ema_ms") val latencyEmaMs: Int = 0,
	@ColumnInfo(name = "last_ok_at") val lastOkAt: Long = 0L,
	@ColumnInfo(name = "last_fail_at") val lastFailAt: Long = 0L,
	/** Drives the circuit breaker. Reset to zero by any success. */
	@ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int = 0,
	/** Counters already sent to the server; the delta is what gets uploaded next. */
	@ColumnInfo(name = "uploaded_ok") val uploadedOk: Int = 0,
	@ColumnInfo(name = "uploaded_fail") val uploadedFail: Int = 0,
	@ColumnInfo(name = "uploaded_empty") val uploadedEmpty: Int = 0,
	@ColumnInfo(name = "uploaded_cf") val uploadedCf: Int = 0,
) {

	val total: Int get() = okCount + failCount

	/** Unsent counters. Uploads are deltas, so a failed upload simply retries next time. */
	fun pendingOk() = (okCount - uploadedOk).coerceAtLeast(0)

	fun pendingFail() = (failCount - uploadedFail).coerceAtLeast(0)

	fun pendingEmpty() = (emptyCount - uploadedEmpty).coerceAtLeast(0)

	fun pendingCf() = (cfCount - uploadedCf).coerceAtLeast(0)

	fun hasPending() = pendingOk() > 0 || pendingFail() > 0 || pendingEmpty() > 0 || pendingCf() > 0
}
