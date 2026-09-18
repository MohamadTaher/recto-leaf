package leaf.novel.presentation.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.source.Source
import leaf.novel.api.NovelCommentScope
import leaf.novel.presentation.reader.comments.NovelCommentsSheet
import leaf.novel.ui.reader.comments.NovelComments
import mihon.app.di.appGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.util.collectAsState

/**
 * The novel's own comments, on the screen that describes the novel.
 *
 * A chapter's comments are about that chapter and belong in the reader. A novel's are about the
 * book, and a tab in the reader was the wrong home for them: it asked someone mid-chapter to leave
 * the chapter in order to read general discussion, and it put two different things behind one
 * button. So the reader now serves only [NovelCommentScope.CHAPTER] and this is the only entry
 * point for [NovelCommentScope.NOVEL] — one place each, chosen by where the reader already is.
 *
 * Written as a composable that both draws the sheet and *returns the action that opens it*, which
 * is unusual and deliberate. The alternative was threading a controller, a visibility flag and a
 * dismiss callback from the view model through [eu.kanade.presentation.manga.MangaScreen] and both
 * of its layout implementations, and every one of those lines is an upstream file the fork has to
 * merge for ever. This way the seam is a nullable lambda: upstream gains one parameter with a
 * default and one guarded button, and everything else lives here.
 *
 * Returns null — which is what withdraws the button — when the reader has turned comments off, when
 * the source serves none, or when it serves only a chapter's.
 */
@Composable
fun novelCommentsAction(manga: Manga, source: Source): (() -> Unit)? {
    val context = LocalContext.current
    val preferences = remember { context.appGraph.novelReaderPreferences }
    val enabled by preferences.commentsEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    // Bound once per novel. The scope is the composition's, so leaving the screen cancels whatever
    // is still being fetched rather than draining a thread nobody is going to read.
    val comments = remember(manga.id, source.id) {
        NovelComments(scope, preferences, NovelCommentScope.NOVEL).apply { bind(source, manga) }
    }
    var showing by remember(manga.id) { mutableStateOf(false) }

    // Every `remember` above runs whatever the answer is, so the slots either side of this stay put.
    if (!enabled || !comments.supported) return null

    if (showing) {
        NovelCommentsSheet(
            comments = comments,
            preferences = preferences,
            onDismissRequest = {
                comments.close()
                showing = false
            },
        )
    }

    return {
        comments.open()
        showing = true
    }
}
