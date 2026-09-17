package leaf.novel.ui.reader

import android.view.KeyEvent
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderKey

/** Shared by Activity key events and Bluetooth events delivered directly to the media session. */
internal fun dispatchNovelReaderKey(
    keyCode: Int,
    eventAction: Int,
    repeatCount: Int,
    binding: (NovelReaderKey) -> NovelReaderAction,
    perform: (NovelReaderAction) -> Unit,
): Boolean {
    val key = NovelReaderKey.of(keyCode) ?: return false
    val action = key.resolve(binding(key))
    if (action == NovelReaderAction.NONE || action == NovelReaderAction.TEXT_SELECTION) return false
    val repeatable = action == NovelReaderAction.PAGE_UP || action == NovelReaderAction.PAGE_DOWN
    if (eventAction == KeyEvent.ACTION_DOWN && (repeatCount == 0 || repeatable)) perform(action)
    return true
}
