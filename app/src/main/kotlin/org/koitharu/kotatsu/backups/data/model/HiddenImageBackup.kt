package org.koitharu.kotatsu.backups.data.model

import android.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.reader.data.erase.HiddenImageEntity

/** Everything needed to go on recognising an image. The thumbnail stays behind: it only decorates a list. */
@Serializable
class HiddenImageBackup(
	@SerialName("hash_horizontal") val hashHorizontal: Long,
	@SerialName("hash_vertical") val hashVertical: Long,
	@SerialName("mean") val mean: Int,
	@SerialName("aspect") val aspect: Float,
	@SerialName("x_start") val xStart: Float,
	@SerialName("x_end") val xEnd: Float,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("manga_title") val mangaTitle: String? = null,
	@SerialName("signature") val signature: String? = null,
	@SerialName("page_id") val pageId: Long? = null,
	@SerialName("y_start") val yStart: Float? = null,
	@SerialName("pad_top") val padTop: Float? = null,
	@SerialName("pad_bottom") val padBottom: Float? = null,
	@SerialName("removes_rows") val removesRows: Boolean? = null,
	@SerialName("colour") val colour: String? = null,
	@SerialName("keypoints") val keypoints: String? = null,
) {

	constructor(entity: HiddenImageEntity) : this(
		hashHorizontal = entity.hashHorizontal,
		hashVertical = entity.hashVertical,
		mean = entity.mean,
		aspect = entity.aspect,
		xStart = entity.xStart,
		xEnd = entity.xEnd,
		createdAt = entity.createdAt,
		mangaTitle = entity.mangaTitle,
		signature = entity.signature?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
		pageId = entity.pageId,
		yStart = entity.yStart,
		padTop = entity.padTop,
		padBottom = entity.padBottom,
		removesRows = entity.removesRows,
		colour = entity.colour?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
		keypoints = entity.keypoints?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
	)

	fun toEntity() = HiddenImageEntity(
		hashHorizontal = hashHorizontal,
		hashVertical = hashVertical,
		mean = mean,
		aspect = aspect,
		xStart = xStart,
		xEnd = xEnd,
		createdAt = createdAt,
		mangaTitle = mangaTitle,
		thumbnail = null,
		signature = signature?.let { Base64.decode(it, Base64.NO_WRAP) },
		pageId = pageId,
		yStart = yStart,
		padTop = padTop,
		padBottom = padBottom,
		removesRows = removesRows,
		colour = colour?.let { Base64.decode(it, Base64.NO_WRAP) },
		keypoints = keypoints?.let { Base64.decode(it, Base64.NO_WRAP) },
	)
}
