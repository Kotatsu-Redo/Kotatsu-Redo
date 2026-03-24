package org.koitharu.kotatsu.explore.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.list.ui.model.ListModel

@Parcelize
data class SourcePreset(
	val id: Long,
	val title: String,
	val languages: Set<String>,
	val sources: Set<String>,
	val createdAt: Long,
	val sortKey: Int,
) : Parcelable, ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is SourcePreset && id == other.id
	}
}

fun SourcePresetEntity.toSourcePreset() = SourcePreset(
	id = presetId,
	title = title,
	languages = if (languages.isBlank()) emptySet() else languages.split(',').toSet(),
	sources = if (sources.isBlank()) emptySet() else sources.split(',').toSet(),
	createdAt = createdAt,
	sortKey = sortKey,
)
