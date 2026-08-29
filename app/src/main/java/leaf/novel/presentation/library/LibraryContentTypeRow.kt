package leaf.novel.presentation.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import leaf.novel.library.LibraryContentType
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val options = listOf(
    LibraryContentType.ALL to MR.strings.leaf_label_content_type_all,
    LibraryContentType.MANGA to MR.strings.leaf_label_content_type_manga,
    LibraryContentType.NOVELS to MR.strings.leaf_label_content_type_novels,
)

/**
 * All / Manga / Novels, rendered between the library toolbar and the category tabs.
 *
 * Not rendered at all until the library holds a novel, which is what keeps a novel-free library
 * pixel-identical to upstream. Hidden during selection mode too, matching the toolbar swap.
 */
@Composable
fun LibraryContentTypeRow(selectionMode: Boolean) {
    val context = LocalContext.current
    val preferences = remember { context.appGraph.novelLibraryPreferences }
    val hasAnyNovel by preferences.hasAnyNovel.collectAsState()

    // Hiding during selection is not only cosmetic: `State.selectedManga` resolves the selection
    // through the *filtered* favourites, so letting the type change mid-selection would silently
    // drop entries from it — including the novels the bulk-download gate is checking for.
    if (!hasAnyNovel || selectionMode) return

    val selected by preferences.libraryContentType.collectAsState()

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            // The Scaffold paints its own background behind topBar content, so without this the
            // row shows a colour seam against the toolbar above it.
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        options.forEachIndexed { index, (contentType, labelRes) ->
            SegmentedButton(
                selected = contentType == selected,
                onClick = { preferences.libraryContentType.set(contentType) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(text = stringResource(labelRes))
            }
        }
    }
}
