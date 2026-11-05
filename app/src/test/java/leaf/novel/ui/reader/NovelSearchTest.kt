package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import leaf.novel.api.NovelChapterContent
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import leaf.novel.ui.reader.setting.NovelTextReplacement
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter

class NovelSearchTest {

    @Test
    fun `typed punctuation finds its typeset forms and spaces stretch`() {
        val pattern = NovelSearch.patternOf("don't  stop", matchCase = false, wholeWords = false)!!
        pattern.find("I DON’T stop.")?.value shouldBe "DON’T stop"
        pattern.find("dont stop") shouldBe null
    }

    @Test
    fun `query characters are literal`() {
        NovelSearch.patternOf("a.b (c)", matchCase = false, wholeWords = false)!!
            .find("axb (c) a.b (c)")?.range shouldBe 8..14
    }

    @Test
    fun `match case and whole words narrow the search`() {
        val text = "Dragon dragons dragon"
        NovelSearch.patternOf("dragon", matchCase = true, wholeWords = false)!!
            .findAll(text).map { it.range.first }.toList() shouldBe listOf(7, 15)
        NovelSearch.patternOf("dragon", matchCase = false, wholeWords = true)!!
            .findAll(text).map { it.range.first }.toList() shouldBe listOf(0, 15)
    }

    @Test
    fun `a blank query searches nothing`() {
        NovelSearch.patternOf("  ", matchCase = false, wholeWords = false) shouldBe null
    }

    @Test
    fun `snippets cut at spaces and say where the match sits`() {
        val text =
            (1..30).joinToString(" ") { "word$it" } + " the dragon king " + (1..60).joinToString(" ") { "tail$it" }
        val start = text.indexOf("dragon")
        val (snippet, highlight) = NovelSearch.snippetOf(text, start..start + 5)
        snippet.substring(highlight.first, highlight.last + 1) shouldBe "dragon"
        snippet.startsWith("…word") shouldBe true
        snippet.endsWith("…") shouldBe true
        snippet.length shouldBe snippet.trim().length
    }

    @Test
    fun `a short paragraph is its own snippet`() {
        NovelSearch.snippetOf("Here be dragons.", 8..14) shouldBe ("Here be dragons." to 8..14)
    }

    @Test
    fun `results name the block and offset speech and the page use`() {
        val html = "<h1>Chapter 1</h1><p>No beast here.</p><p>The <em>dragon</em> woke. A dragon!</p>"
        val blocks = NovelSearch.blocksIn(html, listOf("Chapter 1"))
        val results = NovelSearch.search(
            blocks,
            NovelSearch.patternOf("DRAGON", matchCase = false, wholeWords = false)!!,
            chapter(7),
            chapterIndex = 3,
        )
        results.map { it.position } shouldBe listOf(
            NovelSpeech.Position(7, 2, 4, "dragon"),
            NovelSpeech.Position(7, 2, 19, "dragon"),
        )
        results.first().chapterIndex shouldBe 3
    }

    @Test
    fun `the title the page drops is not searched and the blocks keep their numbers`() {
        val html = "<p>Chapter 2: First Sanctuary</p><p>The First Sanctuary waited.</p>"
        // The chapter list misspells the name; the page also knows the chapter's own title.
        NovelSearch.blocksIn(html, listOf("Chapter 2: First Sancutary", "Chapter 2: First Sanctuary")) shouldBe
            listOf("", "The First Sanctuary waited.")
        NovelSpeech.positions(html, NovelSpeechDivision.PARAGRAPH, 1).map { it.block } shouldBe listOf(0, 1)
    }

    @Test
    fun `whole book search counts what it could not read and caps the list`() = runTest {
        val chapters = (1L..4L).map(::chapter)
        val search = NovelSearch(
            scope = backgroundScope,
            chapters = { chapters },
            currentIndex = { 1 },
            rules = { "" },
            content = { chapter ->
                when (chapter.id) {
                    2L -> null
                    4L -> page("<p>" + "x ".repeat(NovelSearch.MAX_RESULTS) + "</p>")
                    else -> page("<p>x marks chapter ${chapter.id}</p>")
                }
            },
            history = history(),
        )
        search.setQuery("x")
        val done = search.state.first { it.toSearch > 0 && !it.running }
        done.skipped shouldBe 1
        done.total shouldBe NovelSearch.MAX_RESULTS + 2
        done.results.size shouldBe NovelSearch.MAX_RESULTS
        done.chapterCount shouldBe 3
        done.results.first().position.chapterId shouldBe 1L
    }

    @Test
    fun `chapter search looks only at the open chapter`() = runTest {
        val chapters = (1L..3L).map(::chapter)
        val search = NovelSearch(
            scope = backgroundScope,
            chapters = { chapters },
            currentIndex = { 1 },
            rules = { "" },
            content = { page("<p>dragon in ${it.id}</p>") },
            history = history(),
        )
        search.setWholeBook(false)
        search.setQuery("dragon")
        val done = search.state.first { it.toSearch > 0 && !it.running }
        done.results.map { it.position.chapterId } shouldBe listOf(2L)
    }

    @Test
    fun `text replacements apply before matching`() = runTest {
        val rules = NovelTextReplacements.encode(listOf(NovelTextReplacement(pattern = "wyrm", replacement = "dragon")))
        val search = NovelSearch(
            scope = backgroundScope,
            chapters = { listOf(chapter(1)) },
            currentIndex = { 0 },
            rules = { rules },
            content = { page("<p>The wyrm slept.</p>") },
            history = history(),
        )
        search.setQuery("dragon")
        search.state.first { it.toSearch > 0 && !it.running }.results.single().snippet shouldBe "The dragon slept."
    }

    @Test
    fun `opening a result remembers its query newest first without repeats`() = runTest {
        val history = history()
        val search = NovelSearch(
            scope = backgroundScope,
            chapters = { listOf(chapter(1)) },
            currentIndex = { 0 },
            rules = { "" },
            content = { page("<p>dragon king</p>") },
            history = history,
        )
        listOf("king", "dragon", " king ").forEach { query ->
            search.setQuery(query)
            search.select(search.state.first { it.query == query && it.toSearch > 0 && !it.running }.results.first())
        }
        history.get() shouldBe "king\ndragon"
    }

    private fun page(html: String) = NovelChapterContent(html = html)

    private fun history() = InMemoryPreferenceStore().getString("history", "")

    private fun chapter(id: Long) = Chapter.create().copy(id = id, url = "/chapter/$id", name = "Chapter $id")
}
