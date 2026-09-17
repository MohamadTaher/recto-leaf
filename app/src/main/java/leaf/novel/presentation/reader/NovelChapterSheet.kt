package leaf.novel.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.presentation.core.components.material.padding

/**
 * The book's chapters, over the list the reader is already holding.
 *
 * Deliberately a plain row rather than the app's own chapter list item. That one carries download
 * state, bookmarks, scanlator and selection, and takes a dozen parameters to say so — none of which
 * this sheet offers. It is the one place in the reader where not reusing the shared component is
 * the cheaper answer.
 *
 * The list comes from reader state rather than a fresh query, so it is sorted exactly as the
 * previous and next buttons walk it. Re-reading it here would risk the sheet and the buttons
 * disagreeing about what comes next.
 */
@Composable
fun NovelChapterSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    onSelectChapter: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        // A long book opens where the reader actually is, not back at the first chapter.
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

        LazyColumn(state = listState) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                val current = index == currentIndex

                Text(
                    text = chapter.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.Bold else null,
                    color = if (current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismissRequest()
                            // The same path the chapter buttons take, which is what flushes the
                            // position of the chapter being left.
                            onSelectChapter(index)
                        }
                        .padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        ),
                )
            }
        }
    }
}
