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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import leaf.novel.ui.reader.setting.NovelCustomTheme
import leaf.novel.ui.reader.setting.NovelImageSize
import leaf.novel.ui.reader.setting.NovelLinkColor
import leaf.novel.ui.reader.setting.NovelReaderAction
import leaf.novel.ui.reader.setting.NovelReaderColors
import leaf.novel.ui.reader.setting.NovelReaderFont
import leaf.novel.ui.reader.setting.NovelReaderKey
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelReaderSwipe
import leaf.novel.ui.reader.setting.NovelReaderTheme
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import leaf.novel.ui.reader.setting.NovelStatusBarTap
import leaf.novel.ui.reader.setting.NovelStatusItem
import leaf.novel.ui.reader.setting.NovelStatusPlacement
import leaf.novel.ui.reader.setting.NovelTapGrid
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

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
    resolvedColors: NovelReaderColors,
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
                    NovelReaderSettingsTab.VISUAL ->
                        VisualPage(novelReaderPreferences, readerPreferences, resolvedColors)
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
    resolvedColors: NovelReaderColors,
) {
    val fontSize by novelReaderPreferences.fontSize.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_size),
        value = fontSize,
        valueRange = NovelReaderPreferences.MIN_FONT_SIZE..NovelReaderPreferences.MAX_FONT_SIZE,
        onChange = { novelReaderPreferences.fontSize.set(it) },
    )

    val font by novelReaderPreferences.font.collectAsState()
    SettingsChipRow(MR.strings.leaf_novel_reader_font) {
        NovelReaderFont.entries.map { candidate ->
            FilterChip(
                selected = font == candidate,
                onClick = { novelReaderPreferences.font.set(candidate) },
                label = { Text(stringResource(candidate.titleRes)) },
            )
        }
    }

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

    HeadingItem(MR.strings.leaf_novel_reader_heading_images)

    val imageSize by novelReaderPreferences.imageSize.collectAsState()
    SettingsChipRow(MR.strings.leaf_novel_reader_image_size) {
        NovelImageSize.entries.map { candidate ->
            FilterChip(
                selected = imageSize == candidate,
                onClick = { novelReaderPreferences.imageSize.set(candidate) },
                label = { Text(stringResource(candidate.titleRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_center_images),
        pref = novelReaderPreferences.centerImages,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_tap_image_to_open),
        pref = novelReaderPreferences.tapImageToOpen,
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

    // The fork's own theme sets a background and a text colour together, which Mihon's key cannot
    // model. Follow Mihon defers to the row below, so that row stays where it is rather than being
    // hidden behind this one — it is what the default choice here means.
    val novelTheme by novelReaderPreferences.theme.collectAsState()
    val themeLabel: @Composable (NovelReaderTheme) -> String = { candidate ->
        val slot = candidate.slot?.let(novelReaderPreferences.customThemes::getOrNull)
        val named = slot?.name?.collectAsState()?.value.orEmpty()
        named.ifBlank { stringResource(candidate.titleRes) }
    }

    SettingsChipRow(MR.strings.leaf_novel_reader_theme) {
        NovelReaderTheme.entries.map { candidate ->
            FilterChip(
                selected = novelTheme == candidate,
                // An empty slot is seeded from what is on screen, so picking one starts from a page
                // the reader can still read rather than from transparent on transparent.
                onClick = {
                    candidate.slot
                        ?.let(novelReaderPreferences.customThemes::getOrNull)
                        ?.seedFrom(resolvedColors)
                    novelReaderPreferences.theme.set(candidate)
                },
                label = { Text(themeLabel(candidate)) },
            )
        }
    }

    // Only for the slot being used, and inline rather than in a sheet of its own: the page behind
    // this dialog is already drawn in the theme being edited, so it is the preview.
    novelTheme.slot?.let(novelReaderPreferences.customThemes::getOrNull)?.let { custom ->
        val customName by custom.name.collectAsState()
        TextItem(
            label = stringResource(MR.strings.leaf_novel_reader_custom_theme_name),
            value = customName,
            onChange = { custom.name.set(it) },
        )

        HeadingItem(MR.strings.leaf_novel_reader_custom_background)
        ChannelSliders(custom.background)

        HeadingItem(MR.strings.leaf_novel_reader_custom_text)
        ChannelSliders(custom.foreground)
    }

    // Day/night flips between these two. Left both at Follow Mihon they are the same value and
    // nothing to flip, which is exactly when the action falls back to Mihon's own key.
    EnumSelectItem(
        label = stringResource(MR.strings.leaf_novel_reader_day_theme),
        preference = novelReaderPreferences.dayTheme,
        options = NovelReaderTheme.entries,
        labelOf = themeLabel,
    )

    EnumSelectItem(
        label = stringResource(MR.strings.leaf_novel_reader_night_theme),
        preference = novelReaderPreferences.nightTheme,
        options = NovelReaderTheme.entries,
        labelOf = themeLabel,
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

    // Beside the theme row because both are colour. Each chip wears the colour it selects, so the
    // row shows what it does without a swatch component of its own; Default has none to wear and
    // takes the label colour, which is exactly the theme-derived ink it stands for.
    val linkColor by novelReaderPreferences.linkColor.collectAsState()
    SettingsChipRow(MR.strings.leaf_novel_reader_link_color) {
        NovelLinkColor.entries.map { candidate ->
            FilterChip(
                selected = linkColor == candidate,
                onClick = { novelReaderPreferences.linkColor.set(candidate) },
                label = {
                    Text(
                        text = stringResource(candidate.titleRes),
                        color = candidate.argb?.let(::Color) ?: Color.Unspecified,
                    )
                },
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
            valueRange = NovelReaderPreferences.BRIGHTNESS_RANGE,
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
        label = stringResource(MR.strings.leaf_novel_reader_disable_touch_edge),
        pref = novelReaderPreferences.disableTouchEdge,
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_status_bar)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_show_status_bar),
        pref = novelReaderPreferences.showStatusBar,
    )

    // Only while there is a bar to place them in. Eight rows configuring something switched off is
    // the same silent-no-op the paging heading has to carry a subtitle to excuse.
    val showStatusBar by novelReaderPreferences.showStatusBar.collectAsState()
    if (showStatusBar) {
        NovelStatusItem.entries.forEach { item ->
            EnumSelectItem(
                label = stringResource(item.titleRes),
                preference = novelReaderPreferences.statusSlots.getValue(item),
                options = NovelStatusPlacement.entries,
                labelOf = { stringResource(it.titleRes) },
            )
        }
    }

    HeadingItem(MR.strings.leaf_novel_reader_heading_typesetting)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_indent_first_line),
        pref = novelReaderPreferences.indentFirstLine,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_trim_blank_lines),
        pref = novelReaderPreferences.trimBlankLines,
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_format)

    // Print page numbers only exist in a book that carries them, and there is no way to know that
    // from here without opening it — so the heading says so rather than the setting doing nothing.
    Text(
        text = stringResource(MR.strings.leaf_novel_reader_format_subtitle),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = SettingsItemsPaddings.Horizontal)
            .secondaryItemAlpha(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_disable_book_css),
        pref = novelReaderPreferences.disableBookCss,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_use_book_fonts),
        pref = novelReaderPreferences.useBookFonts,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_inline_footnotes),
        pref = novelReaderPreferences.inlineFootnotes,
    )

    val inlineFootnotes by novelReaderPreferences.inlineFootnotes.collectAsState()
    if (inlineFootnotes) {
        val noteColor by novelReaderPreferences.noteColor.collectAsState()
        SettingsChipRow(MR.strings.leaf_novel_reader_note_color) {
            NovelLinkColor.entries.map { candidate ->
                FilterChip(
                    selected = noteColor == candidate,
                    onClick = { novelReaderPreferences.noteColor.set(candidate) },
                    label = {
                        Text(
                            text = stringResource(candidate.titleRes),
                            color = candidate.argb?.let(::Color) ?: Color.Unspecified,
                        )
                    },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_print_page_numbers),
        pref = novelReaderPreferences.printPageNumbers,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_publisher_preview),
        pref = novelReaderPreferences.publisherPreview,
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_eye_care)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_bluelight),
        pref = novelReaderPreferences.bluelight,
    )

    val bluelight by novelReaderPreferences.bluelight.collectAsState()
    if (bluelight) {
        val intensity by novelReaderPreferences.bluelightIntensity.collectAsState()
        SliderItem(
            label = stringResource(MR.strings.leaf_novel_reader_bluelight_intensity),
            value = intensity,
            valueRange = NovelReaderPreferences.BLUELIGHT_INTENSITY_RANGE,
            steps = 0,
            onChange = { novelReaderPreferences.bluelightIntensity.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val reminderMinutes by novelReaderPreferences.reminderMinutes.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_reminder_minutes),
        value = reminderMinutes,
        valueRange = NovelReaderPreferences.REMINDER_MINUTES_RANGE,
        steps = 0,
        valueString = if (reminderMinutes == 0) stringResource(MR.strings.off) else "$reminderMinutes",
        onChange = { novelReaderPreferences.reminderMinutes.set(it) },
    )

    val reminderAt by novelReaderPreferences.reminderAt.collectAsState()
    TextItem(
        label = stringResource(MR.strings.leaf_novel_reader_reminder_at),
        value = reminderAt,
        onChange = { novelReaderPreferences.reminderAt.set(it) },
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_paging)

    // Saying so is not optional: a setting that silently does nothing is a bug report waiting.
    Text(
        text = stringResource(MR.strings.leaf_novel_reader_paging_subtitle),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = SettingsItemsPaddings.Horizontal)
            .secondaryItemAlpha(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_keep_one_line),
        pref = novelReaderPreferences.keepOneLineWhenPaging,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_trim_top_blank_lines),
        pref = novelReaderPreferences.trimTopBlankLines,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_tilt_to_turn),
        pref = novelReaderPreferences.tiltToTurnPage,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_fling_to_turn),
        pref = novelReaderPreferences.flingHorizontallyToTurnPage,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_disable_vertical_scroll),
        pref = novelReaderPreferences.disableVerticalScroll,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_dual_page),
        pref = novelReaderPreferences.dualPageLayout,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_page_turn_sound),
        pref = novelReaderPreferences.pageTurnSound,
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

    HeadingItem(MR.strings.leaf_novel_reader_heading_status_bar)

    NovelStatusBarTap.entries.forEach { tap ->
        ActionSelectItem(
            label = stringResource(tap.titleRes),
            preference = novelReaderPreferences.statusTaps.getValue(tap),
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

    HeadingItem(MR.strings.leaf_novel_action_speak)

    EnumSelectItem(
        label = stringResource(MR.strings.leaf_novel_reader_speech_divide_by),
        preference = novelReaderPreferences.speechDivision,
        options = NovelSpeechDivision.entries,
        labelOf = { stringResource(it.titleRes) },
    )

    val speechRate by novelReaderPreferences.speechRate.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_speech_rate),
        value = speechRate,
        valueRange = NovelReaderPreferences.SPEECH_RATE_RANGE,
        steps = 0,
        onChange = { novelReaderPreferences.speechRate.set(it) },
    )

    HeadingItem(MR.strings.leaf_novel_reader_heading_value_gestures)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_edge_swipe_brightness),
        pref = novelReaderPreferences.edgeSwipeBrightness,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_edge_swipe_font_size),
        pref = novelReaderPreferences.edgeSwipeFontSize,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_pinch_font_size),
        pref = novelReaderPreferences.pinchFontSize,
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

        EnumPicker(
            expanded = expanded,
            selected = action,
            options = NovelReaderAction.entries,
            labelOf = { stringResource(it.titleRes) },
            onDismissRequest = { expanded = false },
            onSelect = preference::set,
        )
    }
}

/**
 * One opaque colour as three sliders, the way the image reader's colour filter page edits its own.
 *
 * Alpha is not offered: a page you can see through is not a theme, and the fully transparent value
 * is what [NovelCustomTheme] reads as an empty slot.
 */
@Composable
private fun ChannelSliders(preference: Preference<Int>) {
    val color by preference.collectAsState()

    CHANNELS.forEach { (labelRes, shift) ->
        SliderItem(
            label = stringResource(labelRes),
            value = (color shr shift) and CHANNEL_MAX,
            valueRange = 0..CHANNEL_MAX,
            steps = 0,
            onChange = { value ->
                preference.set(
                    (color and (CHANNEL_MAX shl shift).inv()) or
                        (value shl shift) or
                        OPAQUE,
                )
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

private val CHANNELS = listOf(
    MR.strings.color_filter_r_value to 16,
    MR.strings.color_filter_g_value to 8,
    MR.strings.color_filter_b_value to 0,
)

private const val CHANNEL_MAX = 0xFF

/** Every colour the editor writes is opaque, which is also what marks the slot as used. */
private const val OPAQUE = 0xFF shl 24

/** An action binding, which is what most of these rows are. */
@Composable
private fun ActionSelectItem(label: String, preference: Preference<NovelReaderAction>) {
    EnumSelectItem(label, preference, NovelReaderAction.entries) { stringResource(it.titleRes) }
}

/**
 * A row naming what is chosen, which opens the picker anchored to itself.
 *
 * Deliberately not the shared select row. That one is an `ExposedDropdownMenuBox`, which measures
 * the space it has against the window and throws outright when there is less of it than the menu
 * needs — and a row low in this dialog has exactly that little. The plain dropdown below is a popup
 * that does no such arithmetic, and it is what the grid above already uses.
 *
 * Generic over the enum rather than over actions alone: the status bar chooses a placement per item
 * from the same shape of row, and one row that takes its options is smaller than two that differ
 * only in their type.
 */
@Composable
private fun <T : Enum<T>> EnumSelectItem(
    label: String,
    preference: Preference<T>,
    options: List<T>,
    labelOf: @Composable (T) -> String,
) {
    val selected by preference.collectAsState()
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
                text = labelOf(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        EnumPicker(
            expanded = expanded,
            selected = selected,
            options = options,
            labelOf = labelOf,
            onDismissRequest = { expanded = false },
            onSelect = preference::set,
        )
    }
}

/** The one picker, shared by the grid and the rows so the two can never drift apart. */
@Composable
private fun <T : Enum<T>> EnumPicker(
    expanded: Boolean,
    selected: T,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    onDismissRequest: () -> Unit,
    onSelect: (T) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        options.forEach { candidate ->
            RadioMenuItem(
                text = { Text(labelOf(candidate)) },
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
