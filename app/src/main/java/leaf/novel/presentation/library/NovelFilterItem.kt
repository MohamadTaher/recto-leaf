package leaf.novel.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The Novels row of the library filter sheet.
 *
 * Not rendered at all until the library holds a novel, which is what keeps a novel-free library
 * pixel-identical to upstream.
 */
@Composable
fun NovelFilterItem() {
    val context = LocalContext.current
    val preferences = remember { context.appGraph.novelLibraryPreferences }
    val hasAnyNovel by preferences.hasAnyNovel.collectAsState()
    if (!hasAnyNovel) return

    val filterNovels by preferences.filterNovels.collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.leaf_action_filter_novels),
        state = filterNovels,
        onClick = preferences.filterNovels::set,
    )
}
