package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderKey
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderSwipe
import leaf.novel.ui.reader.setting.NovelTapGrid
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.components.material.padding
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
 * Typography and the control bindings are the reader's own keys; the background colour, brightness,
 * page number, fullscreen and keep-screen-on are all read from and written to [ReaderPreferences],
 * so there is no second settings system. Reading mode and crop borders have no text equivalent
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
                    NovelReaderSettingsTab.CONTROL -> ControlPage(novelReaderPreferences)
                    NovelReaderSettingsTab.MISCELLANEOUS -> MiscellaneousPage(novelReaderPreferences, readerPreferences)
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

    HeadingItem(MR.strings.leaf_novel_reader_heading_focused_reading)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_highlight_first_word),
        pref = novelReaderPreferences.highlightFirstWord,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_highlight_initial_chars),
        pref = novelReaderPreferences.highlightInitialChars,
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
private fun ColumnScope.MiscellaneousPage(
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
) {
    HeadingItem(MR.strings.leaf_novel_reader_heading_screen)

    // The inverse of the fullscreen key rather than a second key meaning the same thing backwards.
    val fullscreen by readerPreferences.fullscreen.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_show_notification_bar),
        checked = !fullscreen,
        onClick = { readerPreferences.fullscreen.toggle() },
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = readerPreferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = readerPreferences.showPageNumber,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_show_remaining_time),
        pref = novelReaderPreferences.showRemainingTime,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_disable_touch_edge),
        pref = novelReaderPreferences.disableTouchEdge,
    )
}

@Composable
private fun ColumnScope.ControlPage(novelReaderPreferences: NovelReaderPreferences) {
    HeadingItem(MR.strings.rotation_type)

    val orientation by novelReaderPreferences.orientation.collectAsState()
    OrientationGrid(selected = orientation) { novelReaderPreferences.orientation.set(it) }

    HeadingItem(MR.strings.leaf_novel_reader_heading_tap_zones)

    TapZoneGrid(novelReaderPreferences.tapZones)

    ActionSelectItem(
        label = stringResource(MR.strings.leaf_novel_reader_long_tap),
        preference = novelReaderPreferences.longTap,
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_keys)

    NovelReaderKey.entries.forEach { key ->
        ActionSelectItem(
            label = stringResource(key.titleRes),
            preference = novelReaderPreferences.keys.getValue(key),
        )
    }

    HeadingItem(MR.strings.leaf_novel_reader_heading_gestures)

    NovelReaderSwipe.entries.forEach { swipe ->
        ActionSelectItem(
            label = stringResource(swipe.titleRes),
            preference = novelReaderPreferences.swipes.getValue(swipe),
        )
    }

    HeadingItem(MR.strings.leaf_novel_action_auto_scroll)

    val autoScrollSpeed by novelReaderPreferences.autoScrollSpeed.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_autoscroll_speed),
        value = autoScrollSpeed,
        valueRange = NovelReaderPreferences.AUTO_SCROLL_SPEED_RANGE,
        onChange = { novelReaderPreferences.autoScrollSpeed.set(it) },
    )
}

/**
 * The nine bindings laid out the way they sit on the page, so the setting looks like the thing it
 * controls. Each cell anchors its own picker rather than opening a dialog on top of this one.
 */
@Composable
private fun TapZoneGrid(tapZones: List<Preference<NovelReaderAction>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        repeat(NovelTapGrid.SIDE) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                repeat(NovelTapGrid.SIDE) { column ->
                    TapZoneCell(
                        preference = tapZones[row * NovelTapGrid.SIDE + column],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TapZoneCell(preference: Preference<NovelReaderAction>, modifier: Modifier = Modifier) {
    val action by preference.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.padding.extraSmall,
                vertical = MaterialTheme.padding.small,
            ),
        ) {
            Text(
                text = stringResource(action.titleRes),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }

        ActionPicker(
            expanded = expanded,
            selected = action,
            onDismissRequest = { expanded = false },
            onSelect = preference::set,
        )
    }
}

/**
 * A row naming what is bound, which opens the picker anchored to itself.
 *
 * Deliberately not the shared select row. That one is an `ExposedDropdownMenuBox`, which measures
 * the space it has against the window and throws outright when there is less of it than the menu
 * needs — and a row low in this dialog has exactly that little. The plain dropdown below is a popup
 * that does no such arithmetic, and it is what the grid above already uses.
 */
@Composable
private fun ActionSelectItem(label: String, preference: Preference<NovelReaderAction>) {
    val action by preference.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .fillMaxWidth()
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(action.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        ActionPicker(
            expanded = expanded,
            selected = action,
            onDismissRequest = { expanded = false },
            onSelect = preference::set,
        )
    }
}

/** The one list of actions, shared by the grid and the rows so the two can never drift apart. */
@Composable
private fun ActionPicker(
    expanded: Boolean,
    selected: NovelReaderAction,
    onDismissRequest: () -> Unit,
    onSelect: (NovelReaderAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        NovelReaderAction.entries.forEach { candidate ->
            RadioMenuItem(
                text = { Text(stringResource(candidate.titleRes)) },
                isChecked = candidate == selected,
                onClick = {
                    onDismissRequest()
                    onSelect(candidate)
                },
            )
        }
    }
}

/**
 * The orientations, laid out without a lazy grid.
 *
 * The shared icon grid is a LazyVerticalGrid and this page is already inside a vertical scroll, so
 * a lazy grid here would be measured with an unbounded height and throw. The buttons themselves
 * are still the shared ones, so it reads as the same picker the image reader shows.
 */
@Composable
private fun OrientationGrid(selected: ReaderOrientation, onSelect: (ReaderOrientation) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        NovelReaderPreferences.ORIENTATIONS.chunked(ORIENTATIONS_PER_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                row.forEach { orientation ->
                    IconToggleButton(
                        checked = orientation == selected,
                        onCheckedChange = { onSelect(orientation) },
                        imageVector = orientation.icon,
                        title = stringResource(orientation.stringRes),
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row the same width as a full one.
                repeat(ORIENTATIONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val ORIENTATIONS_PER_ROW = 3
