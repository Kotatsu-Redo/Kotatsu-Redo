package org.koitharu.kotatsu.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter

class FindSameChapterTest {

	@Test
	fun sameLinkInSameBranch() {
		// Mangago lists one upload in several scanlator branches, with one id per branch
		val list = listOf(
			chapter(1, "/a/ch-10", 10f, "Official"),
			chapter(2, "/a/ch-10", 10f, "Group"),
		)
		assertEquals(2L, list.findSameChapter(chapter(99, "/a/ch-10", 10f, "Group"))?.id)
	}

	@Test
	fun newLinkSameNumberInSameBranch() {
		// The source served the chapter under another link (mirror host): same branch, same number
		val list = listOf(
			chapter(1, "https://mirror-a/chapter/1/100/", 10f, "Official"),
			chapter(2, "https://mirror-b/chapter/1/200/", 10f, "Group"),
			chapter(3, "https://mirror-a/chapter/1/101/", 11f, "Group"),
		)
		val previous = chapter(99, "https://www.site/read-manga/a/uu/nml_chapter-10/pg-1/", 10f, "Group")
		assertEquals(2L, list.findSameChapter(previous)?.id)
	}

	@Test
	fun sameLinkInRenamedBranch() {
		// A scanlation group was renamed: the branch and the id changed, the link did not
		val list = listOf(
			chapter(1, "/t/x/5-chapter-5", 5f, "New name"),
			chapter(2, "/t/x/6-chapter-5", 5f, "Other group"),
		)
		assertEquals(1L, list.findSameChapter(chapter(99, "/t/x/5-chapter-5", 5f, "Old name"))?.id)
	}

	@Test
	fun ambiguousNumberGivesNothing() {
		val list = listOf(
			chapter(1, "/a/1", 3f, "Group"),
			chapter(2, "/a/2", 3f, "Group"),
		)
		assertNull(list.findSameChapter(chapter(99, "/a/3", 3f, "Group")))
	}

	@Test
	fun zeroNumberNeedsSameTitle() {
		val list = listOf(chapter(1, "/a/1", 0f, "Group", title = "Notice"))
		assertNull(list.findSameChapter(chapter(99, "/a/2", 0f, "Group", title = "Prologue")))
		assertEquals(1L, list.findSameChapter(chapter(99, "/a/2", 0f, "Group", title = "Notice"))?.id)
	}

	@Test
	fun otherBranchIsNotTaken() {
		val list = listOf(chapter(1, "/a/1", 7f, "Official"))
		assertNull(list.findSameChapter(chapter(99, "/a/9", 7f, "Group")))
	}

	private fun chapter(
		id: Long,
		url: String,
		number: Float,
		branch: String?,
		title: String? = "Chapter $number",
	) = MangaChapter(
		id = id,
		title = title,
		number = number,
		volume = 0,
		url = url,
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = TestMangaSource,
	)
}
