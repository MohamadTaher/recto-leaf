package leaf.novel.ui.reader

import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelTextReplacement
import org.junit.jupiter.api.Test

/**
 * The rules are written by hand by someone who may not know regular expressions, so the cases that
 * matter are the ones where a pattern is not what it looks like: metacharacters in a literal, a
 * pattern that does not compile at all, and word boundaries around text that is not English.
 */
class NovelTextReplacementsTest {

    private fun rules(vararg rules: NovelTextReplacement) = NovelTextReplacements.encode(rules.toList())

    @Test
    fun `leaves the chapter alone when there are no rules`() {
        NovelTextReplacements.apply("<p>text</p>", "") shouldBe "<p>text</p>"
        NovelTextReplacements.apply("<p>text</p>", "[]") shouldBe "<p>text</p>"
    }

    @Test
    fun `replaces every occurrence of a literal`() {
        val json = rules(NovelTextReplacement(pattern = "foo", replacement = "bar"))

        NovelTextReplacements.apply("foo and foo", json) shouldBe "bar and bar"
    }

    /** The regex switch being off is a promise that the pattern means itself, dots included. */
    @Test
    fun `treats a literal pattern literally`() {
        val json = rules(NovelTextReplacement(pattern = "a.c", replacement = "X"))

        NovelTextReplacements.apply("abc a.c", json) shouldBe "abc X"
    }

    @Test
    fun `uses groups and backreferences when it is a regular expression`() {
        val json = rules(
            NovelTextReplacement(pattern = "(\\w+)@(\\w+)", replacement = "$2 of $1", isRegex = true),
        )

        NovelTextReplacements.apply("grey@leaf", json) shouldBe "leaf of grey"
    }

    @Test
    fun `ignores case unless asked not to`() {
        val insensitive = rules(NovelTextReplacement(pattern = "Foo", replacement = "X"))
        val sensitive = rules(NovelTextReplacement(pattern = "Foo", replacement = "X", caseSensitive = true))

        NovelTextReplacements.apply("foo FOO", insensitive) shouldBe "X X"
        NovelTextReplacements.apply("foo Foo", sensitive) shouldBe "foo X"
    }

    @Test
    fun `matches a whole word without catching it inside another`() {
        val json = rules(NovelTextReplacement(pattern = "cat", replacement = "dog", matchWholeWord = true))

        NovelTextReplacements.apply("cat concatenate cat.", json) shouldBe "dog concatenate dog."
    }

    /**
     * A `\b` boundary is defined against ASCII word characters, so it sees a boundary in the middle
     * of an accented word: `caf` would count as a whole word inside `café`, because `é` does not
     * look like a letter to it. The Unicode classes know better.
     */
    @Test
    fun `does not find a word boundary inside an accented one`() {
        val json = rules(NovelTextReplacement(pattern = "caf", replacement = "X", matchWholeWord = true))

        NovelTextReplacements.apply("café and caf", json) shouldBe "café and X"
    }

    /**
     * Whole-word cannot help in a language written without spaces: every neighbouring character is
     * a letter, so the boundary never holds. Worth pinning, because it is a limit of the idea
     * rather than of this implementation — the rule simply has to be left off for Japanese.
     */
    @Test
    fun `cannot bound a word in text written without spaces`() {
        val bounded = rules(NovelTextReplacement(pattern = "側", replacement = "X", matchWholeWord = true))
        val plain = rules(NovelTextReplacement(pattern = "側", replacement = "X"))

        NovelTextReplacements.apply("これは側です", bounded) shouldBe "これは側です"
        NovelTextReplacements.apply("これは側です", plain) shouldBe "これはXです"
    }

    @Test
    fun `keeps a disabled rule out of the way`() {
        val json = rules(NovelTextReplacement(pattern = "foo", replacement = "bar", enabled = false))

        NovelTextReplacements.apply("foo", json) shouldBe "foo"
    }

    @Test
    fun `skips a rule with nothing to look for`() {
        val json = rules(NovelTextReplacement(pattern = "", replacement = "bar"))

        NovelTextReplacements.apply("foo", json) shouldBe "foo"
    }

    /** One bad pattern is a typo, not a reason to stop applying the other five. */
    @Test
    fun `runs the rest when one pattern does not compile`() {
        val json = rules(
            NovelTextReplacement(pattern = "(unclosed", replacement = "X", isRegex = true),
            NovelTextReplacement(pattern = "foo", replacement = "bar"),
        )

        NovelTextReplacements.apply("foo", json) shouldBe "bar"
    }

    @Test
    fun `applies the rules in the order they were written`() {
        val json = rules(
            NovelTextReplacement(pattern = "a", replacement = "b"),
            NovelTextReplacement(pattern = "b", replacement = "c"),
        )

        NovelTextReplacements.apply("a", json) shouldBe "c"
    }

    @Test
    fun `runs app-wide rules before rules for this novel`() {
        val appWide = rules(NovelTextReplacement(pattern = "a", replacement = "b"))
        val novel = rules(NovelTextReplacement(pattern = "b", replacement = "c"))

        NovelTextReplacements.apply("a", NovelTextReplacements.combine(appWide, novel)) shouldBe "c"
    }

    @Test
    fun `survives a rules string that is not valid JSON`() {
        NovelTextReplacements.apply("foo", "{not json") shouldBe "foo"
        NovelTextReplacements.parse("{not json") shouldBe emptyList()
    }

    @Test
    fun `round-trips a rule through the stored form`() {
        val rule = NovelTextReplacement(
            title = "Strip the advert",
            pattern = "Read at .*",
            replacement = "",
            isRegex = true,
            caseSensitive = true,
        )

        NovelTextReplacements.parse(NovelTextReplacements.encode(listOf(rule))) shouldBe listOf(rule)
    }
}
