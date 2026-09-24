package leaf.novel.presentation.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.source.Source
import leaf.novel.api.NovelCommentScope
import leaf.novel.presentation.reader.comments.NovelCommentsSheet
import leaf.novel.ui.reader.comments.NovelCommentCache
import leaf.novel.ui.reader.comments.NovelCommentMatcher
import leaf.novel.ui.reader.comments.NovelComments
import mihon.app.di.appGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.util.collectAsState

/**
 * The novel's comments from its own source and other installed sources, on its detail screen.
 *
 * A chapter's comments are about that chapter and belong in the reader. A novel's are about the
 * book, and a tab in the reader was the wrong home for them: it asked someone mid-chapter to leave
 * the chapter in order to read general discussion, and it put two different things behind one
 * button. So the reader now serves only [NovelCommentScope.CHAPTER] and this is the only entry
 * point for [NovelCommentScope.NOVEL] — one place each, chosen by where the reader already is.
 *
 * Written as a composable that both draws the sheet and *returns the action that opens it*, which
 * is unusual and deliberate: [NovelInfoLine], already under the novel's title, offers it with no
 * controller, visibility flag or dismiss callback threaded through
 * [eu.kanade.presentation.manga.MangaScreen] and both of its layouts — every line of which would be
 * an upstream file the fork has to merge for ever.
 *
 * Returns null — which is what withdraws the link — when the reader has turned comments off. A
 * novel whose own source has no novel feed can still have comments elsewhere.
 */
@Composable
fun novelCommentsAction(manga: Manga, source: Source): (() -> Unit)? {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val preferences = graph.novelReaderPreferences
    val enabled by preferences.commentsEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    // Bound once per novel. The scope is the title block's, so leaving the screen, or scrolling the
    // title away, cancels whatever is still being fetched; what arrived is in the cache.
    val comments = remember(manga.id, source.id) {
        NovelComments(
            scope = scope,
            preferences = preferences,
            commentScope = NovelCommentScope.NOVEL,
            matcher = NovelCommentMatcher.installed(graph.sourceManager, graph.sourcePreferences),
            allowCrossSourceOnly = true,
            cache = NovelCommentCache.shared,
        ).apply { bind(source, manga) }
    }
    var showing by rememberSaveable(manga.id) { mutableStateOf(false) }

    // Every `remember` above runs whatever the answer is, so the slots either side of this stay put.
    if (!enabled || !comments.supported) return null

    if (showing) {
        NovelCommentsSheet(
            comments = comments,
            preferences = preferences,
            onDismissRequest = { showing = false },
        )
    }

    return {
        comments.open()
        showing = true
    }
}
