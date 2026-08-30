package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.NovelTextEmphasis.Run
import org.junit.jupiter.api.Test

/**
 * Both aids rewrite a book's own markup, so the thing worth pinning down is that they emphasise the
 * right characters and leave everything else exactly as it was.
 */
class NovelTextEmphasisTest {

    private val wholeFirstWord = { word: String, opensSentence: Boolean ->
        if (opensSentence) word.length else 0
    }

    @Test
    fun `splits a sentence into its opening word and the rest`() {
        NovelTextEmphasis.runsOf("One two. Three four", wholeFirstWord) shouldBe listOf(
            Run("One", emphasised = true),
            Run(" two. ", emphasised = false),
            Run("Three", emphasised = true),
            Run(" four", emphasised = false),
        )
    }

    @Test
    fun `treats a question mark as ending a sentence`() {
        NovelTextEmphasis.runsOf("Who? Nobody", wholeFirstWord) shouldBe listOf(
            Run("Who", emphasised = true),
            Run("? ", emphasised = false),
            Run("Nobody", emphasised = true),
        )
    }

    @Test
    fun `emphasises the opening word of each sentence`() {
        NovelTextEmphasis.firstWordOfSentence("<p>One two. Three four</p>") shouldBe
            "<p><b>One</b> two. <b>Three</b> four</p>"
    }

    @Test
    fun `emphasises two fifths of a word, rounded up`() {
        NovelTextEmphasis.initialCharsOfWord("<p>Hello</p>") shouldBe "<p><b>He</b>llo</p>"
    }

    @Test
    fun `emphasises a one letter word whole`() {
        NovelTextEmphasis.initialCharsOfWord("<p>I am</p>") shouldBe "<p><b>I</b> <b>a</b>m</p>"
    }

    /** The pass works on the parsed tree, so an entity is never escaped a second time. */
    @Test
    fun `leaves entities as one character`() {
        NovelTextEmphasis.initialCharsOfWord("<p>Tom &amp; Jerry</p>") shouldBe
            "<p><b>To</b>m &amp; <b>Je</b>rry</p>"
    }

    @Test
    fun `keeps a book's own emphasis and nests inside it`() {
        NovelTextEmphasis.initialCharsOfWord("<p>Hello <em>brave</em> world</p>") shouldBe
            "<p><b>He</b>llo <em><b>br</b>ave</em> <b>wo</b>rld</p>"
    }

    @Test
    fun `leaves headings alone`() {
        NovelTextEmphasis.initialCharsOfWord("<h1>Title</h1><p>Text</p>") shouldBe
            "<h1>Title</h1><p><b>Te</b>xt</p>"
    }

    @Test
    fun `leaves code alone`() {
        NovelTextEmphasis.initialCharsOfWord("<p>run <code>value</code></p>") shouldBe
            "<p><b>ru</b>n <code>value</code></p>"
    }

    @Test
    fun `handles a paragraph that is a single word`() {
        NovelTextEmphasis.initialCharsOfWord("<p>Word</p>") shouldBe "<p><b>Wo</b>rd</p>"
    }

    /** An apostrophe is inside the word, so the contraction is emphasised as one. */
    @Test
    fun `keeps a contraction together`() {
        NovelTextEmphasis.initialCharsOfWord("<p>don't</p>") shouldBe "<p><b>do</b>n't</p>"
    }

    @Test
    fun `leaves an empty paragraph untouched`() {
        NovelTextEmphasis.initialCharsOfWord("<p></p>") shouldBe "<p></p>"
    }
}
