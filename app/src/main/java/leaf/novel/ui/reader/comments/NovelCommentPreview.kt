package leaf.novel.ui.reader.comments

import android.net.Uri
import android.text.format.DateUtils
import leaf.novel.api.NovelComment
import leaf.novel.ui.reader.loader.VIRTUAL_ORIGIN

/**
 * A chapter's best comments, written into the page under its last line the way Webnovel sets them.
 *
 * HTML rather than Compose because it has to scroll with the text, and the text is a WebView. It
 * is drawn the way the sheet draws a comment — the avatar, the name and the date on one line, four
 * lines of text, the pictures, the likes beneath — and the whole of it is one link that opens the
 * sheet, so nothing here has to be interactive. Pictures are asked for at [MEDIA], which the reader
 * answers from the same image cache the sheet draws from, so a picture is fetched once for both. The colours are the page's, set by the reader's
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
    fun html(
        state: NovelCommentsState,
        labels: Labels,
        spoilerGuard: Boolean,
        avatars: Boolean,
        referer: (NovelComment) -> String?,
    ): String? {
        val top = state.roots
            .asSequence()
            .filterNot { it.deleted }
            // Sorted first, so only the comments that might be shown are parsed for their text.
            .sortedByDescending { it.score ?: 0 }
            .map { it to spans(it) }
            .filter { (_, spans) -> spans.any { it.image != null || it.text.isNotBlank() } }
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
                top.forEach { (comment, spans) ->
                    val hidden = spoilerGuard || spans.any { it.spoiler }
                    append(item(comment, if (hidden) null else spans, labels, now, avatars, referer(comment)))
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

    private fun item(
        comment: NovelComment,
        spans: List<NovelCommentSpan>?,
        labels: Labels,
        now: Long,
        avatars: Boolean,
        referer: String?,
    ): String {
        val time = comment.postedAt.takeIf { it > 0 }
            ?.let {
                " · " + escape(DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString())
            }
            .orEmpty()
        val likes = comment.score?.takeIf { it > 0 }
            ?.let { """<div class="$LIKES">$THUMB_UP$it</div>""" }
            .orEmpty()
        val shown = if (spans == null) {
            """<div class="$TEXT $SPOILER">${escape(labels.spoiler)}</div>"""
        } else {
            val text = spans.joinToString("") { it.text }.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { """<div class="$TEXT">${escape(it)}</div>""" }
                .orEmpty()
            // A video GIF is left to the sheet: only pictures come out of the image cache.
            val pictures = spans.mapNotNull { it.image }
                .filterNot { VIDEO.containsMatchIn(it.substringBefore('?')) }
                .joinToString("") { """<img class="$IMAGE" src="${src(it, referer)}" alt="">""" }
            text + pictures.takeIf { it.isNotEmpty() }?.let { """<div class="$IMAGES">$it</div>""" }.orEmpty()
        }
        // The initial sits under the picture, as in the sheet, so one that never arrives leaves a letter.
        val portrait = comment.avatarUrl?.takeIf { avatars }
            ?.let { """<img src="${src(it, referer)}" alt="">""" }
            .orEmpty()
        return """<div class="$ITEM">""" +
            """<div class="$AVATAR">${escape(comment.author.take(1).uppercase())}$portrait</div>""" +
            """<div class="$MAIN"><div class="$META"><span class="$AUTHOR">${escape(
                comment.author,
            )}</span>$time</div>""" +
            "$shown$likes</div></div>"
    }

    /** The comment, with a review's rating line taken off as the sheet takes it off. */
    private fun spans(comment: NovelComment): List<NovelCommentSpan> =
        NovelCommentMarkup.parse(NovelCommentReview.parse(comment.body).body)

    /** Where the page asks for [url]: the reader's own origin, carrying the picture and its Referer. */
    private fun src(url: String, referer: String?): String =
        escape("$VIRTUAL_ORIGIN$MEDIA${Uri.encode(url)}" + referer?.let { "?$REFERER=${Uri.encode(it)}" }.orEmpty())

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

    /** The path a comment's picture is asked for under, its own URL encoded after it. */
    const val MEDIA = "recto-leaf/comment-media/"

    /** The query parameter carrying the Referer the picture is asked for with. */
    const val REFERER = "r"

    val VIDEO = Regex("""\.(mp4|webm)$""", RegexOption.IGNORE_CASE)

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
    const val IMAGES = "leaf-comment-images"
    const val IMAGE = "leaf-comment-image"
    const val ACTION = "leaf-comments-action"

    /** A small chevron after "View all", stroked like the other glyphs. */
    private const val CHEVRON = """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6L15 12L9 18"/></svg>"""

    /** The sheet's own outlined like, `NovelCommentGlyphs.Like` path for path. */
    private const val THUMB_UP =
        """<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10L12 3Q15 3 14 7L13.5 9L19 9Q21 9 20.5 11""" +
            """L18.5 19Q18.2 20 17 20L7 20ZM3 10L7 10L7 20L3 20Z"/></svg>"""
}
