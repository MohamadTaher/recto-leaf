package leaf.novel.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The library's Novels filter.
 *
 * Everything here turns on a library with no novel in it behaving exactly as upstream does: the row
 * is not drawn, the filter reports itself inactive so the toolbar's indicator stays dark, and a
 * choice left over from when there *was* a novel does not quietly hide anything.
 */
class NovelLibraryFilterTest {

    private val manga = item(id = 1, novel = false)
    private val novel = item(id = 2, novel = true)

    @Test
    fun `passes the library through untouched while the filter is off`() = runTest {
        val filter = filter(hasAnyNovel = true, setTo = TriState.DISABLED)

        filter.apply(MutableStateFlow(listOf(manga, novel))).first() shouldBe listOf(manga, novel)
    }

    @Test
    fun `narrows to novels, or to everything that is not one`() = runTest {
        val library = MutableStateFlow(listOf(manga, novel))

        filter(hasAnyNovel = true, setTo = TriState.ENABLED_IS)
            .apply(library).first() shouldBe listOf(novel)
        filter(hasAnyNovel = true, setTo = TriState.ENABLED_NOT)
            .apply(library).first() shouldBe listOf(manga)
    }

    /**
     * Without this the toolbar's "filters are active" dot would light up over a library that is not
     * filtered, on every install that has never held a novel.
     */
    @Test
    fun `reports itself inactive while the library holds no novel`() = runTest {
        val filter = filter(hasAnyNovel = false, setTo = TriState.ENABLED_IS)

        filter.filter.first() shouldBe TriState.DISABLED
    }

    /** And it must not hide the whole library on the way, which ENABLED_IS applied literally would. */
    @Test
    fun `keeps a novel-free library whole even with a choice left over`() = runTest {
        val filter = filter(hasAnyNovel = false, setTo = TriState.ENABLED_IS)

        filter.apply(MutableStateFlow(listOf(manga))).first() shouldBe listOf(manga)
    }

    @Test
    fun `caches whether the library holds a novel, for the sheet that opens before it emits`() = runTest {
        val preferences = NovelLibraryPreferences(FakePreferenceStore())
        val hasAnyNovel = MutableStateFlow(false)

        NovelLibraryFilter(preferences, hasAnyNovel).keepPreferencesCurrent(backgroundScope)
        preferences.hasAnyNovel.changes().first { !it }
        preferences.hasAnyNovel.get() shouldBe false

        hasAnyNovel.value = true
        preferences.hasAnyNovel.changes().first { it }

        preferences.hasAnyNovel.get() shouldBe true
    }

    /**
     * Deleting the last novel leaves the choice behind, and a filter for something the library no
     * longer contains is an empty screen with no obvious way out.
     */
    @Test
    fun `clears a stale choice once the last novel leaves the library`() = runTest {
        val preferences = NovelLibraryPreferences(FakePreferenceStore())
        preferences.filterNovels.set(TriState.ENABLED_IS)
        val hasAnyNovel = MutableStateFlow(true)

        NovelLibraryFilter(preferences, hasAnyNovel).keepPreferencesCurrent(backgroundScope)
        preferences.hasAnyNovel.changes().first { it }
        // While a novel is there the choice is the reader's own and is left alone.
        preferences.filterNovels.get() shouldBe TriState.ENABLED_IS

        hasAnyNovel.value = false
        preferences.filterNovels.changes().first { it == TriState.DISABLED }

        preferences.hasAnyNovel.get() shouldBe false
    }

    private fun filter(hasAnyNovel: Boolean, setTo: TriState) = NovelLibraryFilter(
        preferences = NovelLibraryPreferences(FakePreferenceStore()).apply { filterNovels.set(setTo) },
        hasAnyNovel = MutableStateFlow(hasAnyNovel),
    )

    private fun item(id: Long, novel: Boolean) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, isNovel = novel),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        sourceName = "source",
        sourceLanguage = "en",
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = "en"),
    )
}
