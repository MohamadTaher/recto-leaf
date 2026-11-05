package leaf.novel.ui.reader.comments

import android.text.format.DateUtils
import leaf.novel.api.NovelComment
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN

/**
 * A chapter's best comments, written into the page under its last line the way Webnovel sets them.
 *
 * HTML rather than Compose because it has to scroll with the text, and the text is a WebView. It
 * is drawn the way the sheet draws a comment — the initial in a circle, the name and the date on
 * one line, four lines of text, the likes beneath — and the whole of it is one link that opens the
 * sheet, so nothing here has to be interactive. The colours are the page's, set by the reader's
 * stylesheet, so a theme change repaints it with the text.
 */
object NovelCommentPreview {

    /** Everything this says that the reader reads, resolved by the caller in its own locale. */
    data class Labels(
        val title: String,
        /** Formats the thread's size, as "12 comments". */
        val count: (Int) -> String,
        val viewAll: String,
        val load: String,
        val spoiler: String,
    )

    /**
     * The preview for what [state] holds, empty for a chapter with nothing worth showing, or null
     * while there is nothing yet to say.
     *
     * Still loading, or failed, says nothing rather than putting a spinner or an error under every
     * chapter; the sheet is where those belong. A thread no one has asked for — auto-load turned
     * off — gets the one line that asks for it.
     */
    fun html(state: NovelCommentsState, labels: Labels, spoilerGuard: Boolean): String? {
        val top = state.roots
            .asSequence()
            .filterNot { it.deleted }
            // Sorted first, so only the comments that might be shown are parsed for their text.
            .sortedByDescending { it.score ?: 0 }
            .map { it to text(it) }
            .filter { (_, text) -> text.isNotBlank() }
            .take(COUNT)
            .toList()
        val total = when (state.kind) {
            NovelCommentKind.ALL -> state.reviewCount + state.commentCount
            NovelCommentKind.REVIEWS -> state.reviewCount
            NovelCommentKind.COMMENTS -> state.commentCount
        }.takeIf { it > 0 } ?: state.roots.size
        val body = when {
            top.isNotEmpty() -> buildString {
                append(head(labels.count(total), labels.viewAll))
                val now = System.currentTimeMillis()
                top.forEach { (comment, text) ->
                    append(item(comment, if (spoilerGuard || hasSpoiler(comment)) null else text, labels, now))
                }
            }
            // Nothing worth a card: the chapter simply ends, and the bar's button is there to write one.
            state.loaded -> return ""
            !state.loading && state.error == null ->
                head(labels.title, labels.load)
            else -> return null
        }
        // The attribute is what the page's script finds it by, as it finds a chapter by its own.
        return """<a class="$ROOT" data-leaf-comments href="$LINK">$body</a>"""
    }

    /** The title, and on the other side what a tap on the preview does, as the sheet's header sets them. */
    private fun head(title: String, action: String? = null): String {
        val end = action?.let { """<span class="$ACTION">${escape(it)}$CHEVRON</span>""" }.orEmpty()
        return """<div class="$HEAD"><span>${escape(title)}</span>$end</div>"""
    }

    private fun item(comment: NovelComment, text: String?, labels: Labels, now: Long): String {
        val time = comment.postedAt.takeIf { it > 0 }
            ?.let {
                " · " + escape(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString())
            }
            .orEmpty()
        val likes = comment.score?.takeIf { it > 0 }
            ?.let { """<div class="$LIKES">$THUMB_UP$it</div>""" }
            .orEmpty()
        val shown = text?.let { """<div class="$TEXT">${escape(it)}</div>""" }
            ?: """<div class="$TEXT $SPOILER">${escape(labels.spoiler)}</div>"""
        return """<div class="$ITEM">""" +
            """<div class="$AVATAR">${escape(comment.author.take(1).uppercase())}</div>""" +
            """<div class="$MAIN"><div class="$META"><span class="$AUTHOR">${escape(
                comment.author,
            )}</span>$time</div>""" +
            "$shown$likes</div></div>"
    }

    /** The words alone, with a review's rating line taken off as the sheet takes it off. */
    private fun text(comment: NovelComment): String =
        NovelCommentMarkup.plainText(NovelCommentReview.parse(comment.body).body)

    private fun hasSpoiler(comment: NovelComment): Boolean =
        NovelCommentMarkup.parse(NovelCommentReview.parse(comment.body).body).any { it.spoiler }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** How many comments the preview shows. */
    private const val COUNT = 3

    /** The entry [LINK] reaches the reader's internal-link handler as. */
    const val ENTRY = "recto-leaf/comments"

    /** What the preview links to; the reader opens the sheet for it instead of following it. */
    const val LINK = "$VIRTUAL_ORIGIN$ENTRY"

    const val ROOT = "leaf-comments"
    const val HEAD = "leaf-comments-head"
    const val ITEM = "leaf-comment"
    const val AVATAR = "leaf-comment-avatar"
    const val MAIN = "leaf-comment-main"
    const val META = "leaf-comment-meta"
    const val AUTHOR = "leaf-comment-author"
    const val TEXT = "leaf-comment-text"
    const val SPOILER = "leaf-comment-spoiler"
    const val LIKES = "leaf-comment-likes"
    const val ACTION = "leaf-comments-action"

    /** A small chevron after "View all", stroked like the other glyphs. */
    private const val CHEVRON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6L15 12L9 18"/></svg>"""

    /** The sheet's own outlined like, `NovelCommentGlyphs.Like` path for path. */
    private const val THUMB_UP =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10L12 3Q15 3 14 7L13.5 9L19 9Q21 9 20.5 11""" +
            """L18.5 19Q18.2 20 17 20L7 20ZM3 10L7 10L7 20L3 20Z"/></svg>"""
}
