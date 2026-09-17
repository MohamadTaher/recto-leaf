package leaf.novel.ui.reader.setting

import android.view.KeyEvent
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * The keys a reader can bind an action to, with what each starts out doing.
 *
 * Existing constant names are persisted in the preference keys and must remain stable.
 *
 * The image reader has its own volume-key preferences and this deliberately does not read them.
 * Per-key bindings are a superset of that pair, and writing them would change how manga reads.
 */
enum class NovelReaderKey(
    val keyCode: Int,
    val titleRes: StringResource,
    val default: NovelReaderAction,
) {
    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP, MR.strings.leaf_novel_key_volume_up, NovelReaderAction.NONE),
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN, MR.strings.leaf_novel_key_volume_down, NovelReaderAction.NONE),
    BACK(KeyEvent.KEYCODE_BACK, MR.strings.leaf_novel_key_back, NovelReaderAction.NONE),
    MENU(KeyEvent.KEYCODE_MENU, MR.strings.leaf_novel_key_menu, NovelReaderAction.NONE),
    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, MR.strings.leaf_novel_key_dpad_up, NovelReaderAction.NONE),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, MR.strings.leaf_novel_key_dpad_down, NovelReaderAction.NONE),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, MR.strings.leaf_novel_key_dpad_left, NovelReaderAction.NONE),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, MR.strings.leaf_novel_key_dpad_right, NovelReaderAction.NONE),
    DPAD_CENTER(
        KeyEvent.KEYCODE_DPAD_CENTER,
        MR.strings.leaf_novel_key_dpad_center,
        NovelReaderAction.OPTIONS_MENU,
    ),
    HEADSET_PLAY(KeyEvent.KEYCODE_HEADSETHOOK, MR.strings.leaf_novel_key_headset_play, NovelReaderAction.TOGGLE_SPEECH),
    MEDIA_NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, MR.strings.leaf_novel_key_media_next, NovelReaderAction.START_SPEAKING),
    MEDIA_PREVIOUS(
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        MR.strings.leaf_novel_key_media_previous,
        NovelReaderAction.STOP_SPEAKING,
    ),
    MEDIA_PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, MR.strings.leaf_novel_key_media_pause, NovelReaderAction.TOGGLE_SPEECH),
    MEDIA_PLAY(KeyEvent.KEYCODE_MEDIA_PLAY, MR.strings.leaf_novel_key_media_play, NovelReaderAction.START_SPEAKING),
    MEDIA_STOP(KeyEvent.KEYCODE_MEDIA_STOP, MR.strings.leaf_novel_key_media_stop, NovelReaderAction.STOP_SPEAKING),
    ;

    companion object {
        private val byKeyCode = entries.associateBy { it.keyCode }

        fun of(keyCode: Int): NovelReaderKey? = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> HEADSET_PLAY
            else -> byKeyCode[keyCode]
        }
    }

    /** Respect the direction sent by Bluetooth devices, including Galaxy Buds. */
    fun resolve(action: NovelReaderAction): NovelReaderAction =
        if (this == MEDIA_PAUSE && action == NovelReaderAction.TOGGLE_SPEECH) {
            NovelReaderAction.PAUSE_SPEAKING
        } else {
            action
        }
}
