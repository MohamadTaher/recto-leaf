package leaf.novel.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelReaderActionTest {

    @Test
    fun `only three speech commands are assignable`() {
        NovelReaderAction.assignable.filter { "SPEAK" in it.name || "SPEECH" in it.name } shouldBe listOf(
            NovelReaderAction.START_SPEAKING,
            NovelReaderAction.TOGGLE_SPEECH,
            NovelReaderAction.STOP_SPEAKING,
        )
    }

    @Test
    fun `old read aloud and pause assignments migrate without losing the binding`() {
        NovelReaderAction.fromPreference("SPEAK", NovelReaderAction.NONE) shouldBe NovelReaderAction.START_SPEAKING
        NovelReaderAction.fromPreference("PAUSE_SPEAKING", NovelReaderAction.NONE) shouldBe
            NovelReaderAction.TOGGLE_SPEECH
    }

    @Test
    fun `legacy headset read aloud still toggles while dedicated pause stays directional`() {
        val legacy = NovelReaderAction.fromPreference("SPEAK", NovelReaderAction.NONE, NovelReaderAction.TOGGLE_SPEECH)
        NovelReaderKey.HEADSET_PLAY.resolve(legacy) shouldBe NovelReaderAction.TOGGLE_SPEECH
        NovelReaderKey.MEDIA_PAUSE.resolve(legacy) shouldBe NovelReaderAction.PAUSE_SPEAKING
    }

    @Test
    fun `existing custom commands and disabled bindings survive migration`() {
        for (action in NovelReaderAction.assignable) {
            NovelReaderAction.fromPreference(action.name, NovelReaderAction.START_SPEAKING) shouldBe action
        }
        NovelReaderAction.fromPreference("UNKNOWN", NovelReaderAction.PAGE_DOWN) shouldBe NovelReaderAction.PAGE_DOWN
    }
}
