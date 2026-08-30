package leaf.novel.ui.reader.setting

import android.view.KeyEvent
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * The keys a reader can bind an action to, with what each starts out doing.
 *
 * The defaults are the imported Moon+ configuration, with anything bound to a feature that does not
 * exist yet demoted to [NovelReaderAction.NONE] — the three speech bindings, until there is speech.
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
    SEARCH(KeyEvent.KEYCODE_SEARCH, MR.strings.leaf_novel_key_search, NovelReaderAction.SEARCH),
    CAMERA(KeyEvent.KEYCODE_CAMERA, MR.strings.leaf_novel_key_camera, NovelReaderAction.PAGE_UP),
    DPAD_UP(KeyEvent.KEYCODE_DPAD_UP, MR.strings.leaf_novel_key_dpad_up, NovelReaderAction.NONE),
    DPAD_DOWN(KeyEvent.KEYCODE_DPAD_DOWN, MR.strings.leaf_novel_key_dpad_down, NovelReaderAction.NONE),
    DPAD_LEFT(KeyEvent.KEYCODE_DPAD_LEFT, MR.strings.leaf_novel_key_dpad_left, NovelReaderAction.NONE),
    DPAD_RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT, MR.strings.leaf_novel_key_dpad_right, NovelReaderAction.NONE),
    DPAD_CENTER(
        KeyEvent.KEYCODE_DPAD_CENTER,
        MR.strings.leaf_novel_key_dpad_center,
        NovelReaderAction.OPTIONS_MENU,
    ),
    HEADSET_PLAY(KeyEvent.KEYCODE_HEADSETHOOK, MR.strings.leaf_novel_key_headset_play, NovelReaderAction.NONE),
    MEDIA_NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, MR.strings.leaf_novel_key_media_next, NovelReaderAction.NONE),
    MEDIA_PREVIOUS(
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        MR.strings.leaf_novel_key_media_previous,
        NovelReaderAction.PAGE_UP,
    ),
    MEDIA_PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, MR.strings.leaf_novel_key_media_pause, NovelReaderAction.NONE),
    ;

    companion object {
        private val byKeyCode = entries.associateBy { it.keyCode }

        fun of(keyCode: Int): NovelReaderKey? = byKeyCode[keyCode]
    }
}
