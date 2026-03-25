package org.koitharu.kotatsu.core.parser

import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.TestMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet

/*
 This class is for parser development and testing purposes
 You can open it in the app via Settings -> Debug
 */
class TestMangaRepository(
    @Suppress("unused") private val loaderContext: MangaLoaderContext,
    cache: MemoryContentCache
) : CachingMangaRepository(cache) {

    override val source = TestMangaSource

    override val sortOrders: Set<SortOrder> = EnumSet.allOf(SortOrder::class.java)

    override var defaultSortOrder: SortOrder
        get() = sortOrders.first()
        set(value) = Unit

    override val filterCapabilities = MangaListFilterCapabilities()

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?
    ): List<Manga> = emptyList()

    override suspend fun getDetailsImpl(
        manga: Manga
    ): Manga = manga

    override suspend fun getPagesImpl(
        chapter: MangaChapter
    ): List<MangaPage> = emptyList()

    override suspend fun getPageUrl(
        page: MangaPage
    ): String = page.url ?: ""

    override suspend fun getRelatedMangaImpl(
        seed: Manga
    ): List<Manga> = emptyList()
}
