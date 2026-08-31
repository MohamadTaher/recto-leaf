package leaf.novel.data.backup

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * What the file is for is surviving the fork's own churn, so the cases that matter are the ones
 * where the settings on the device and the settings in the file do not agree: a key the file has
 * never heard of, a key the reader no longer has, and a shared key that must never be in there at
 * all.
 */
class NovelSettingsTransferTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `takes only what the reader owns`() {
        val all = mapOf(
            "leaf_novel_font_size" to 22,
            "leaf_novel_bold" to true,
            "leaf_novel_text_replacements" to "[]",
            "pref_reader_theme" to 1,
            "custom_brightness" to true,
            "some_other_app_key" to "x",
        )

        val backup = NovelSettingsTransfer.capture(all)

        backup.ints shouldBe mapOf("leaf_novel_font_size" to 22)
        backup.booleans shouldBe mapOf("leaf_novel_bold" to true)
        backup.strings shouldBe mapOf("leaf_novel_text_replacements" to "[]")
    }

    /**
     * The shared keys are the standing check in file form: a novel settings file that could change
     * how manga reads would be a trap, and Mihon's own backup already carries them.
     */
    @Test
    fun `leaves every shared key behind`() {
        val shared = mapOf(
            "pref_reader_theme" to 1,
            "custom_brightness" to true,
            "custom_brightness_value" to 40,
            "fullscreen" to true,
            "keep_screen_on" to true,
        )

        val backup = NovelSettingsTransfer.capture(shared)

        backup shouldBe NovelSettingsBackup()
    }

    @Test
    fun `sorts a set so two exports of the same settings match`() {
        val all = mapOf("leaf_novel_things" to setOf("c", "a", "b"))

        NovelSettingsTransfer.capture(all).stringSets shouldBe
            mapOf("leaf_novel_things" to listOf("a", "b", "c"))
    }

    @Test
    fun `keeps every type through the file and back`() {
        val all = mapOf(
            "leaf_novel_bool" to true,
            "leaf_novel_int" to 7,
            "leaf_novel_long" to 8L,
            "leaf_novel_float" to 1.5f,
            "leaf_novel_string" to "hello",
            "leaf_novel_set" to setOf("a", "b"),
        )

        val captured = NovelSettingsTransfer.capture(all)
        val roundTripped = json.decodeFromString<NovelSettingsBackup>(json.encodeToString(captured))

        roundTripped shouldBe captured
        roundTripped.longs shouldBe mapOf("leaf_novel_long" to 8L)
        roundTripped.floats shouldBe mapOf("leaf_novel_float" to 1.5f)
    }

    /** An export written by a later stage has to restore what this one still understands. */
    @Test
    fun `reads a file carrying groups it does not know`() {
        val fromTheFuture = """
            { "ints": { "leaf_novel_font_size": 20 }, "colours": { "leaf_novel_thing": "#fff" } }
        """.trimIndent()

        val backup = json.decodeFromString<NovelSettingsBackup>(fromTheFuture)

        backup.ints shouldBe mapOf("leaf_novel_font_size" to 20)
    }

    @Test
    fun `reads a file that names only one group`() {
        val backup = json.decodeFromString<NovelSettingsBackup>("""{ "booleans": { "leaf_novel_bold": true } }""")

        backup.booleans shouldBe mapOf("leaf_novel_bold" to true)
        backup.ints shouldBe emptyMap()
    }

    @Test
    fun `has nothing to say about a device with no reader settings yet`() {
        NovelSettingsTransfer.capture(emptyMap<String, Any>()) shouldBe NovelSettingsBackup()
    }
}
