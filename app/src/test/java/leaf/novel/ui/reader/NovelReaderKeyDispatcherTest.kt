package leaf.novel.ui.reader

import android.view.KeyEvent
import io.kotest.matchers.shouldBe
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderKey
import org.junit.jupiter.api.Test

class NovelReaderKeyDispatcherTest {

    @Test
    fun `wired and Bluetooth play pause use the same saved binding`() {
        val actions = mutableListOf<NovelReaderAction>()
        for (code in listOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            dispatch(code, perform = actions::add) shouldBe true
        }
        actions shouldBe listOf(NovelReaderAction.TOGGLE_SPEECH, NovelReaderAction.TOGGLE_SPEECH)
    }

    @Test
    fun `dedicated pause never toggles back to playing`() {
        val actions = mutableListOf<NovelReaderAction>()
        repeat(2) { dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE, perform = actions::add) }
        actions shouldBe listOf(NovelReaderAction.PAUSE_SPEAKING, NovelReaderAction.PAUSE_SPEAKING)
    }

    @Test
    fun `all keys dispatch each assigned command unchanged`() {
        val commands = NovelReaderAction.assignable - setOf(
            NovelReaderAction.NONE,
            NovelReaderAction.TEXT_SELECTION,
        )
        for (key in NovelReaderKey.entries) {
            for (command in commands) {
                val actions = mutableListOf<NovelReaderAction>()
                dispatch(key.keyCode, binding = { command }, perform = actions::add) shouldBe true
                val expected = if (key == NovelReaderKey.MEDIA_PAUSE && command == NovelReaderAction.TOGGLE_SPEECH) {
                    NovelReaderAction.PAUSE_SPEAKING
                } else {
                    command
                }
                actions shouldBe listOf(expected)
            }
        }
    }

    @Test
    fun `release and held media keys do not toggle twice`() {
        val actions = mutableListOf<NovelReaderAction>()
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, perform = actions::add)
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeats = 1, perform = actions::add) shouldBe true
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, event = KeyEvent.ACTION_UP, perform = actions::add) shouldBe true
        actions shouldBe listOf(NovelReaderAction.TOGGLE_SPEECH)
    }

    @Test
    fun `page commands still repeat when held`() {
        val actions = mutableListOf<NovelReaderAction>()
        dispatch(
            KeyEvent.KEYCODE_VOLUME_DOWN,
            repeats = 1,
            binding = { NovelReaderAction.PAGE_DOWN },
            perform = actions::add,
        ) shouldBe true
        actions shouldBe listOf(NovelReaderAction.PAGE_DOWN)
    }

    @Test
    fun `unbound volume back and unknown keys retain system behavior`() {
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_BACK, -1)) {
            dispatch(code) { error("Unbound key dispatched") } shouldBe false
        }
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, binding = { NovelReaderAction.NONE }) {
            error("Disabled headset binding dispatched")
        } shouldBe false
    }

    @Test
    fun `play and stop keys have directional defaults`() {
        val actions = mutableListOf<NovelReaderAction>()
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY, perform = actions::add)
        dispatch(KeyEvent.KEYCODE_MEDIA_STOP, perform = actions::add)
        actions shouldBe listOf(NovelReaderAction.START_SPEAKING, NovelReaderAction.STOP_SPEAKING)
    }

    @Test
    fun `earbud next and previous default to starting and stopping speech`() {
        val actions = mutableListOf<NovelReaderAction>()
        dispatch(KeyEvent.KEYCODE_MEDIA_NEXT, perform = actions::add)
        dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS, perform = actions::add)
        actions shouldBe listOf(NovelReaderAction.START_SPEAKING, NovelReaderAction.STOP_SPEAKING)
    }

    @Test
    fun `camera and search keys are no longer intercepted`() {
        for (code in listOf(KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_SEARCH)) {
            NovelReaderKey.of(code) shouldBe null
            dispatch(code) { error("Removed key dispatched") } shouldBe false
        }
    }

    private fun dispatch(
        code: Int,
        event: Int = KeyEvent.ACTION_DOWN,
        repeats: Int = 0,
        binding: (NovelReaderKey) -> NovelReaderAction = { it.default },
        perform: (NovelReaderAction) -> Unit,
    ) = dispatchNovelReaderKey(code, event, repeats, binding, perform)
}
