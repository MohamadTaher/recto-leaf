package leaf.novel.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

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
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))

        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.chapters),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(MR.strings.action_close))
                }
            }
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false).selectableGroup(),
                contentPadding = PaddingValues(vertical = MaterialTheme.padding.small),
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    val current = index == currentIndex

                    ListItem(
                        headlineContent = {
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (current) FontWeight.SemiBold else null,
                            )
                        },
                        leadingContent = {
                            Text(text = "${index + 1}", style = MaterialTheme.typography.labelMedium)
                        },
                        trailingContent = if (current) {
                            { Icon(MaterialSymbols.Rounded.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (current) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            headlineColor = if (current) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = current, role = Role.RadioButton) {
                                onDismissRequest()
                                // The same path the chapter buttons take, which is what flushes the
                                // position of the chapter being left.
                                onSelectChapter(index)
                            },
                    )
                }
            }
        }
    }
}
