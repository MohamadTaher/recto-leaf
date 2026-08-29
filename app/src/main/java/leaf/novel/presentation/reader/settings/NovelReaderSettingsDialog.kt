package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

// Same values and same order as the image reader's general page, so the two dialogs read alike.
private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/**
 * Font size, plus the shared settings that mean something to text. Built on the image reader's
 * [TabbedDialog] and setting items so it is the same dialog, with the image-only tabs left out.
 *
 * Everything but the font size is a key [ReaderPreferences] already owns, so there is no second
 * settings system. Reading mode, orientation and crop borders have no text equivalent;
 * brightness and the colour filter apply but are still reached from the app's reader settings.
 */
@Composable
fun NovelReaderSettingsDialog(
    fontSizePreference: Preference<Int>,
    readerPreferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.leaf_novel_reader_tab_text),
        stringResource(MR.strings.pref_category_general),
    )
    val pagerState = rememberPagerState { tabTitles.size }

    BoxWithConstraints {
        TabbedDialog(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = onDismissRequest,
            tabTitles = tabTitles,
            pagerState = pagerState,
        ) { page ->
            Column(
                modifier = Modifier
                    .padding(vertical = TabbedDialogPaddings.Vertical)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (page) {
                    0 -> TextPage(fontSizePreference)
                    1 -> GeneralPage(readerPreferences)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TextPage(fontSizePreference: Preference<Int>) {
    val fontSize by fontSizePreference.collectAsState()

    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_size),
        value = fontSize,
        valueRange = NovelReaderPreferences.MIN_FONT_SIZE..NovelReaderPreferences.MAX_FONT_SIZE,
        onChange = { fontSizePreference.set(it) },
    )
}

@Composable
private fun ColumnScope.GeneralPage(readerPreferences: ReaderPreferences) {
    val readerTheme by readerPreferences.readerTheme.collectAsState()

    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { readerPreferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = readerPreferences.showPageNumber,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = readerPreferences.fullscreen,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = readerPreferences.keepScreenOn,
    )
}
