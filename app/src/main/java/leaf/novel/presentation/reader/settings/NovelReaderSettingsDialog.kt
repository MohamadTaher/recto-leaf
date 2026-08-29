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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The three groups of settings the reader offers, in the order they appear.
 *
 * The bottom bar has a button per entry and opens the dialog on it, so the ordinal doubles as the
 * pager's initial page.
 */
enum class NovelReaderSettingsTab {
    VISUAL,
    CONTROL,
    MISCELLANEOUS,
}

// Same values and same order as the image reader's general page, so the two dialogs read alike.
private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/**
 * The reader's settings, grouped the way Moon+ groups them, on the image reader's own
 * [TabbedDialog] and setting items so the two dialogs are the same dialog.
 *
 * Only the font size is a key of the reader's own; the background colour, brightness, page number,
 * fullscreen and keep-screen-on are all read from and written to [ReaderPreferences], so there is
 * no second settings system. Reading mode, orientation and crop borders have no text equivalent
 * and are not carried over.
 */
@Composable
fun NovelReaderSettingsDialog(
    initialTab: NovelReaderSettingsTab,
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.leaf_novel_reader_tab_visual),
        stringResource(MR.strings.leaf_novel_reader_tab_control),
        stringResource(MR.strings.leaf_novel_reader_tab_misc),
    )
    val pagerState = rememberPagerState(initialPage = initialTab.ordinal) { tabTitles.size }

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
                when (NovelReaderSettingsTab.entries[page]) {
                    NovelReaderSettingsTab.VISUAL -> VisualPage(novelReaderPreferences, readerPreferences)
                    // Empty until taps and keys arrive; padding it now would only be removed then.
                    NovelReaderSettingsTab.CONTROL -> Unit
                    NovelReaderSettingsTab.MISCELLANEOUS -> MiscellaneousPage(readerPreferences)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.VisualPage(
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
) {
    val fontSize by novelReaderPreferences.fontSize.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_size),
        value = fontSize,
        valueRange = NovelReaderPreferences.MIN_FONT_SIZE..NovelReaderPreferences.MAX_FONT_SIZE,
        onChange = { novelReaderPreferences.fontSize.set(it) },
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_text_styling)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_bold),
        pref = novelReaderPreferences.bold,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_italic),
        pref = novelReaderPreferences.italic,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_underline),
        pref = novelReaderPreferences.underline,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_shadow),
        pref = novelReaderPreferences.shadow,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_antialias),
        pref = novelReaderPreferences.antialias,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_justified),
        pref = novelReaderPreferences.justified,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_hyphenation),
        pref = novelReaderPreferences.hyphenation,
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_spacing)

    val paragraphSpacing by novelReaderPreferences.paragraphSpacing.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_paragraph_spacing),
        value = paragraphSpacing,
        valueRange = NovelReaderPreferences.PARAGRAPH_SPACING_RANGE,
        // Two hundred stops would draw two hundred ticks; this range reads as continuous.
        steps = 0,
        onChange = { novelReaderPreferences.paragraphSpacing.set(it) },
    )

    val lineSpacing by novelReaderPreferences.lineSpacing.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_line_spacing),
        value = lineSpacing,
        valueRange = NovelReaderPreferences.LINE_SPACING_RANGE,
        onChange = { novelReaderPreferences.lineSpacing.set(it) },
    )

    val fontSpacing by novelReaderPreferences.fontSpacing.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_spacing),
        value = fontSpacing,
        valueRange = NovelReaderPreferences.FONT_SPACING_RANGE,
        onChange = { novelReaderPreferences.fontSpacing.set(it) },
    )

    val fontScale by novelReaderPreferences.fontScale.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_scale),
        value = fontScale,
        valueRange = NovelReaderPreferences.FONT_SCALE_RANGE,
        onChange = { novelReaderPreferences.fontScale.set(it) },
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_margins)

    val marginLeft by novelReaderPreferences.marginLeft.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_left),
        value = marginLeft,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = 0,
        onChange = { novelReaderPreferences.marginLeft.set(it) },
    )

    val marginRight by novelReaderPreferences.marginRight.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_right),
        value = marginRight,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = 0,
        onChange = { novelReaderPreferences.marginRight.set(it) },
    )

    val marginTop by novelReaderPreferences.marginTop.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_top),
        value = marginTop,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = 0,
        onChange = { novelReaderPreferences.marginTop.set(it) },
    )

    val marginBottom by novelReaderPreferences.marginBottom.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_bottom),
        value = marginBottom,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = 0,
        onChange = { novelReaderPreferences.marginBottom.set(it) },
    )

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

    // Both keys are already honoured by NovelReaderActivity; what they have never had is a control.
    // Range and presentation are the image reader's: below zero the window holds minimum brightness
    // and the shared content overlay makes up the difference.
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = readerPreferences.customBrightness,
    )
    val customBrightness by readerPreferences.customBrightness.collectAsState()
    if (customBrightness) {
        val customBrightnessValue by readerPreferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { readerPreferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun ColumnScope.MiscellaneousPage(readerPreferences: ReaderPreferences) {
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
