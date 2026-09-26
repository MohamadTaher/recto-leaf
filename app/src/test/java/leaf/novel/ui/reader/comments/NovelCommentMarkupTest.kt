package leaf.novel.ui.reader.comments

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The markup pass is the one place a site's HTML reaches the reader, so the cases worth holding are
 * the two kinds of surprise a site produces: markup that carries meaning the sheet must keep, and
 * markup that carries none and must not survive.
 */
class NovelCommentMarkupTest {

    private fun text(html: String) = NovelCommentMarkup.plainText(html)

    @Test
    fun `keeps the words of a plain comment`() {
        text("Great chapter!") shouldBe "Great chapter!"
    }

    @Test
    fun `starts a new line at each block`() {
        text("<p>First</p><p>Second</p>") shouldBe "First\nSecond"
    }

    @Test
    fun `treats a line break as a line break`() {
        text("one<br>two") shouldBe "one\ntwo"
    }

    @Test
    fun `keeps emphasis as a span rather than as characters`() {
        val spans = NovelCommentMarkup.parse("plain <b>bold</b>")

        spans.map { it.text } shouldBe listOf("plain ", "bold")
        spans[0].bold shouldBe false
        spans[1].bold shouldBe true
    }

    @Test
    fun `merges neighbouring spans a site split for no reason`() {
        NovelCommentMarkup.parse("<span>one</span><span> two</span>").size shouldBe 1
    }

    @Test
    fun `keeps the text of a tag it does not know`() {
        text("<marquee>still words</marquee>") shouldBe "still words"
    }

    @Test
    fun `drops a script outright rather than unwrapping it to its source`() {
        text("<p>hi</p><script>alert(1)</script>") shouldBe "hi"
    }

    @Test
    fun `keeps an image as its own span, not as text`() {
        val spans = NovelCommentMarkup.parse("""look <img src="https://example.com/cat.gif"> here""")

        spans.mapNotNull { it.image } shouldBe listOf("https://example.com/cat.gif")
        text("""look <img src="https://example.com/cat.gif"> here""") shouldBe "look  here"
    }

    @Test
    fun `keeps a comment that is only an image`() {
        NovelCommentMarkup.parse("""<p><img src="https://example.com/cat.gif"></p>""")
            .mapNotNull { it.image } shouldBe listOf("https://example.com/cat.gif")
    }

    @Test
    fun `leaves no blank line where a picture stood at the end`() {
        val spans = NovelCommentMarkup.parse("""so true<br><img src="https://example.com/cat.gif">""")

        spans.filter { it.image == null }.joinToString("") { it.text } shouldBe "so true"
        spans.mapNotNull { it.image } shouldBe listOf("https://example.com/cat.gif")
    }

    @Test
    fun `keeps a picture where it stood among the words`() {
        val html = "before<br>" +
            """<img src="https://example.com/a.gif"> <img src="https://example.com/b.gif">""" +
            "<br>after"

        NovelCommentMarkup.blocks(NovelCommentMarkup.parse(html)) shouldBe listOf(
            NovelCommentBlock.Text(listOf(NovelCommentSpan("before"))),
            NovelCommentBlock.Media(listOf("https://example.com/a.gif", "https://example.com/b.gif")),
            NovelCommentBlock.Text(listOf(NovelCommentSpan("after"))),
        )
    }

    @Test
    fun `keeps a video GIF the same way as a picture`() {
        NovelCommentMarkup.parse("""<video src="https://example.com/clip.mp4"></video>""")
            .mapNotNull { it.image } shouldBe listOf("https://example.com/clip.mp4")
    }

    @Test
    fun `resolves a relative image and refuses one that is not http`() {
        NovelCommentMarkup.parse(
            """<img src="/a.png"><img src="data:image/png;base64,AAAA">""",
            "https://site.test/c/1",
        )
            .mapNotNull { it.image } shouldBe listOf("https://site.test/a.png")
    }

    @Test
    fun `takes a lazy image's real address over its placeholder`() {
        NovelCommentMarkup.parse(
            """<img src="/blank.gif" data-src="https://example.com/real.png">""",
            "https://site.test/",
        )
            .mapNotNull { it.image } shouldBe listOf("https://example.com/real.png")
    }

    @Test
    fun `drops a stylesheet outright`() {
        text("<style>p{color:red}</style><p>hi</p>") shouldBe "hi"
    }

    @Test
    fun `keeps an http link`() {
        NovelCommentMarkup.parse("""<a href="https://example.com">here</a>""")
            .single()
            .link shouldBe "https://example.com"
    }

    @Test
    fun `makes a pasted address a link, leaving the full stop after it`() {
        val spans = NovelCommentMarkup.parse("see https://example.com/a?b=1. and www.example.org")

        spans.map { it.text } shouldBe listOf("see ", "https://example.com/a?b=1", ". and ", "www.example.org")
        spans.map { it.link } shouldBe listOf(null, "https://example.com/a?b=1", null, "https://www.example.org")
    }

    /** The words are the comment; the scheme is the attack. Keep one, drop the other. */
    @Test
    fun `keeps the text of a javascript link but not the link`() {
        val span = NovelCommentMarkup.parse("""<a href="javascript:alert(1)">tap me</a>""").single()

        span.text shouldBe "tap me"
        span.link shouldBe null
    }

    @Test
    fun `drops a relative link it cannot resolve`() {
        NovelCommentMarkup.parse("""<a href="/u/someone">someone</a>""").single().link shouldBe null
    }

    @Test
    fun `resolves a relative link against the comment's own page`() {
        NovelCommentMarkup.parse(
            html = """<a href="/u/someone">someone</a>""",
            baseUrl = "https://example.com/chapter-1",
        ).single().link shouldBe "https://example.com/u/someone"
    }

    @Test
    fun `marks a spoiler however the site spells the class`() {
        listOf("spoiler", "md-spoiler-text", "js-spoiler")
            .forEach { className ->
                NovelCommentMarkup.parse("""<span class="$className">twist</span>""")
                    .single()
                    .spoiler shouldBe true
            }
    }

    @Test
    fun `marks a spoiler declared as an attribute`() {
        NovelCommentMarkup.parse("""<span data-spoiler="true">twist</span>""")
            .single()
            .spoiler shouldBe true
    }

    @Test
    fun `carries a spoiler down to the text inside it`() {
        NovelCommentMarkup.parse("""<div class="spoiler"><p><b>twist</b></p></div>""")
            .all { it.spoiler } shouldBe true
    }

    @Test
    fun `bullets a list item, having no list of its own to draw`() {
        text("<ul><li>one</li><li>two</li></ul>") shouldBe "• one\n• two"
    }

    @Test
    fun `sets a quote apart`() {
        NovelCommentMarkup.parse("<blockquote>said</blockquote>").single().quote shouldBe true
    }

    @Test
    fun `sets code apart`() {
        NovelCommentMarkup.parse("<code>x = 1</code>").single().code shouldBe true
    }

    @Test
    fun `trims the empty paragraphs sites wrap comments in`() {
        text("<p></p><p>hi</p><p></p>") shouldBe "hi"
    }

    @Test
    fun `treats a non-breaking space as a space`() {
        text("one&nbsp;two") shouldBe "one two"
    }

    @Test
    fun `keeps no more than one blank line between lines`() {
        text("one<br><br><br><br>two") shouldBe "one\n\ntwo"
        text("<p>one</p>\n \n\n<p>two</p><br><p>three</p>") shouldBe "one\n\ntwo\n\nthree"
    }

    @Test
    fun `keeps a single line break and a single blank line as written`() {
        text("one<br>two<br><br>three") shouldBe "one\ntwo\n\nthree"
        text("one\n\ntwo") shouldBe "one\n\ntwo"
    }

    @Test
    fun `keeps the blank lines inside a code block`() {
        text("<pre>a\n\nb</pre>") shouldBe "a\n\nb"
    }

    @Test
    fun `sets a heading apart on its own line`() {
        val spans = NovelCommentMarkup.parse("<h2>Verdict</h2>Worth it")

        spans.map { it.text } shouldBe listOf("Verdict", "\nWorth it")
        spans[0].heading shouldBe true
        spans[1].heading shouldBe false
    }

    @Test
    fun `keeps an underline`() {
        NovelCommentMarkup.parse("<u>this</u>").single().underline shouldBe true
    }

    @Test
    fun `marks a spoiler set on the picture itself`() {
        listOf(
            """<img class="spoiler" src="https://example.com/twist.png">""",
            """<img data-spoiler src="https://example.com/twist.png">""",
            """<video class="spoiler" src="https://example.com/twist.mp4"></video>""",
        ).forEach { html ->
            NovelCommentMarkup.parse(html).single { it.image != null }.spoiler shouldBe true
        }
    }

    @Test
    fun `plays a video named by its source element, preferring its own address`() {
        NovelCommentMarkup.parse("""<video><source src="https://example.com/a.mp4" type="video/mp4"></video>""")
            .mapNotNull { it.image } shouldBe listOf("https://example.com/a.mp4")
        NovelCommentMarkup.parse(
            """<video src="https://example.com/own.mp4"><source src="https://example.com/a.mp4"></video>""",
        ).mapNotNull { it.image } shouldBe listOf("https://example.com/own.mp4")
        NovelCommentMarkup.parse("""<video><source src="javascript:alert(1)"></video>""")
            .mapNotNull { it.image } shouldBe emptyList()
    }

    @Test
    fun `survives an empty comment`() {
        NovelCommentMarkup.parse("") shouldBe emptyList()
    }
}
