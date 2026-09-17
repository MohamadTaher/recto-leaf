package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import leaf.novel.presentation.reader.appbars.NovelBarButtons
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
import leaf.novel.ui.reader.setting.NovelStatusBarTap
import leaf.novel.ui.reader.setting.NovelStatusItem
import leaf.novel.ui.reader.setting.NovelStatusPlacement
import leaf.novel.ui.reader.setting.NovelTapGrid
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ExpandMore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The four groups of settings the reader offers, in the order they appear.
 *
 * The bottom bar has a button per entry and opens the dialog on it, so the ordinal doubles as the
 * pager's initial page.
 */
enum class NovelReaderSettingsTab {
    VISUAL,
    CONTROL,
    MISCELLANEOUS,
    ADVANCED,
}

// Same values and same order as the image reader's general page, so the two dialogs read alike.
private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

/**
 * The reader's settings on Mihon's adaptive sheet and shared setting items.
 * Scrollable tabs keep all four group names readable at larger text sizes.
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
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tabTitles = listOf(
        stringResource(MR.strings.leaf_novel_reader_tab_visual),
        stringResource(MR.strings.leaf_novel_reader_tab_control),
        stringResource(MR.strings.leaf_novel_reader_tab_misc),
        stringResource(MR.strings.leaf_novel_reader_tab_advanced),
    )
    val pagerState = rememberPagerState(initialPage = initialTab.ordinal) { tabTitles.size }

    val scope = rememberCoroutineScope()
    BoxWithConstraints {
        AdaptiveSheet(
            modifier = Modifier.heightIn(max = maxHeight * 0.75f),
            onDismissRequest = onDismissRequest,
        ) {
            Column {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    edgePadding = 8.dp,
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { TabText(text = title) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = TabbedDialogPaddings.Vertical),
                    ) {
                        when (NovelReaderSettingsTab.entries[page]) {
                            NovelReaderSettingsTab.VISUAL ->
                                VisualPage(novelReaderPreferences, readerPreferences, resolvedColors)
                            NovelReaderSettingsTab.CONTROL -> ControlPage(novelReaderPreferences)
                            NovelReaderSettingsTab.MISCELLANEOUS -> MiscellaneousPage(
                                novelReaderPreferences,
                                readerPreferences,
                            )
                            NovelReaderSettingsTab.ADVANCED -> AdvancedPage(
                                novelReaderPreferences,
                                readerPreferences,
                                onExportSettings,
                                onImportSettings,
                            )
                        }
                    }
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
    SectionHeading(MR.strings.leaf_novel_reader_heading_text_styling, showDivider = false)

    val fontSize by novelReaderPreferences.fontSize.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_font_size),
        value = fontSize,
        valueRange = NovelReaderPreferences.MIN_FONT_SIZE..NovelReaderPreferences.MAX_FONT_SIZE,
        onChange = { novelReaderPreferences.fontSize.set(it) },
    )

    val font by novelReaderPreferences.font.collectAsState()
    ChipSettingRow(stringResource(MR.strings.leaf_novel_reader_font)) {
        NovelReaderFont.entries.map { candidate ->
            FilterChip(
                selected = font == candidate,
                onClick = { novelReaderPreferences.font.set(candidate) },
                label = { Text(stringResource(candidate.titleRes)) },
            )
        }
    }

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

    SectionHeading(MR.strings.leaf_novel_reader_heading_spacing)

    val paragraphSpacing by novelReaderPreferences.paragraphSpacing.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_paragraph_spacing),
        value = paragraphSpacing,
        valueRange = NovelReaderPreferences.PARAGRAPH_SPACING_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.paragraphSpacing.set(it) },
    )

    val paragraphIndent by novelReaderPreferences.paragraphIndent.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_indent_first_line),
        value = paragraphIndent,
        valueRange = NovelReaderPreferences.PARAGRAPH_INDENT_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.paragraphIndent.set(it) },
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

    SectionHeading(MR.strings.leaf_novel_reader_heading_margins)

    val marginLeft by novelReaderPreferences.marginLeft.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_left),
        value = marginLeft,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.marginLeft.set(it) },
    )

    val marginRight by novelReaderPreferences.marginRight.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_right),
        value = marginRight,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.marginRight.set(it) },
    )

    val marginTop by novelReaderPreferences.marginTop.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_top),
        value = marginTop,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.marginTop.set(it) },
    )

    val marginBottom by novelReaderPreferences.marginBottom.collectAsState()
    SliderItem(
        label = stringResource(MR.strings.leaf_novel_reader_margin_bottom),
        value = marginBottom,
        valueRange = NovelReaderPreferences.MARGIN_RANGE,
        steps = STEPPED_SLIDER_STOPS,
        onChange = { novelReaderPreferences.marginBottom.set(it) },
    )

    SectionHeading(MR.strings.leaf_novel_reader_heading_images)

    val imageSize by novelReaderPreferences.imageSize.collectAsState()
    ImageSizeRow(imageSize) { novelReaderPreferences.imageSize.set(it) }

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_center_images),
        pref = novelReaderPreferences.centerImages,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_tap_image_to_open),
        pref = novelReaderPreferences.tapImageToOpen,
    )

    SectionHeading(MR.strings.pref_category_theme)

    // The fork's own theme sets a background and a text colour together, which Mihon's key cannot
    // model. Follow Mihon defers to the row below, so that row stays where it is rather than being
    // hidden behind this one — it is what the default choice here means.
    val novelTheme by novelReaderPreferences.theme.collectAsState()
    val themeLabel: @Composable (NovelReaderTheme) -> String = { candidate ->
        val slot = candidate.slot?.let(novelReaderPreferences.customThemes::getOrNull)
        val named = slot?.name?.collectAsState()?.value.orEmpty()
        named.ifBlank { stringResource(candidate.titleRes) }
    }

    ChipSettingRow(stringResource(MR.strings.leaf_novel_reader_theme)) {
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

        SectionHeading(MR.strings.leaf_novel_reader_custom_background)
        ChannelSliders(custom.background)

        SectionHeading(MR.strings.leaf_novel_reader_custom_text)
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
    ChipSettingRow(stringResource(MR.strings.pref_reader_theme)) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { readerPreferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun ColumnScope.MiscellaneousPage(
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
) {
    SectionHeading(MR.strings.leaf_novel_reader_heading_screen, showDivider = false)

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

    SectionHeading(MR.strings.leaf_novel_reader_heading_status_bar)

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

    SectionHeading(MR.strings.leaf_novel_reader_heading_bar_buttons)

    // One row per position rather than a list that reorders: the order of the rows is the order of
    // the bar, duplicates collapse, and an empty bar falls back to the four it has always had — so
    // nothing has to be blocked while editing and there is always a way back.
    novelReaderPreferences.barButtons.forEachIndexed { slot, preference ->
        EnumSelectItem(
            label = stringResource(MR.strings.leaf_novel_reader_bar_button, slot + 1),
            preference = preference,
            options = NovelBarButtons.CANDIDATES,
            labelOf = { stringResource(it.titleRes) },
        )
    }

    SectionHeading(MR.strings.leaf_novel_reader_heading_paging)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_paged),
        pref = novelReaderPreferences.paged,
    )

    // These two are about turning a page, not about how the page is laid out, so they work — and
    // are offered — in either mode. Keeping a line is in fact the only one of the seven that does
    // *nothing* while paged: columns cannot overlap.
    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_keep_one_line),
        pref = novelReaderPreferences.keepOneLineWhenPaging,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_page_turn_sound),
        pref = novelReaderPreferences.pageTurnSound,
    )

    // The rest describe a layout that only exists while paged mode is on, and are gated at their
    // call sites as well as here. Showing one that cannot be reached to switch off again is how a
    // reader ends up stuck on a page they cannot scroll. Stage 17's "stored now, takes effect
    // later" subtitle is gone with all of them: it is no longer true of any.
    val paged by novelReaderPreferences.paged.collectAsState()
    if (!paged) return

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
}

@Composable
private fun ColumnScope.AdvancedPage(
    novelReaderPreferences: NovelReaderPreferences,
    readerPreferences: ReaderPreferences,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
) {
    SectionHeading(MR.strings.leaf_novel_reader_heading_typesetting, showDivider = false)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_trim_blank_lines),
        pref = novelReaderPreferences.trimBlankLines,
    )

    SectionHeading(MR.strings.leaf_novel_reader_heading_focused_reading)

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_highlight_first_word),
        pref = novelReaderPreferences.highlightFirstWord,
    )

    CheckboxItem(
        label = stringResource(MR.strings.leaf_novel_reader_highlight_initial_chars),
        pref = novelReaderPreferences.highlightInitialChars,
    )

    SectionHeading(MR.strings.leaf_novel_reader_heading_eye_care)

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
            steps = 19,
            onChange = { novelReaderPreferences.bluelightIntensity.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    SectionHeading(MR.strings.leaf_novel_reader_heading_link_colors)

    val linkColor by novelReaderPreferences.linkColor.collectAsState()
    ChipSettingRow(stringResource(MR.strings.leaf_novel_reader_link_color)) {
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

    SectionHeading(MR.strings.leaf_novel_reader_heading_format)

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
        ChipSettingRow(stringResource(MR.strings.leaf_novel_reader_note_color)) {
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

    SectionHeading(MR.strings.leaf_novel_reader_heading_settings_backup)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        OutlinedButton(
            onClick = onExportSettings,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(stringResource(MR.strings.leaf_novel_action_export_settings))
        }
        OutlinedButton(
            onClick = onImportSettings,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Text(stringResource(MR.strings.leaf_novel_action_import_settings))
        }
    }
}

@Composable
private fun ColumnScope.ControlPage(novelReaderPreferences: NovelReaderPreferences) {
    SectionHeading(MR.strings.leaf_novel_reader_heading_screen, showDivider = false)

    EnumSelectItem(
        label = stringResource(MR.strings.rotation_type),
        preference = novelReaderPreferences.orientation,
        options = NovelReaderPreferences.ORIENTATIONS,
        labelOf = { stringResource(it.stringRes) },
    )

    SectionHeading(MR.strings.leaf_novel_reader_heading_tap_zones)

    TapZoneGrid(novelReaderPreferences.tapZones)

    ActionSelectItem(
        label = stringResource(MR.strings.leaf_novel_reader_long_tap),
        preference = novelReaderPreferences.longTap,
    )

    SectionHeading(MR.strings.leaf_novel_reader_heading_keys)

    NovelReaderKey.entries.forEach { key ->
        ActionSelectItem(
            label = stringResource(key.titleRes),
            preference = novelReaderPreferences.keys.getValue(key),
        )
    }

    SectionHeading(MR.strings.leaf_novel_reader_heading_gestures)

    NovelReaderSwipe.entries.forEach { swipe ->
        ActionSelectItem(
            label = stringResource(swipe.titleRes),
            preference = novelReaderPreferences.swipes.getValue(swipe),
        )
    }

    SectionHeading(MR.strings.leaf_novel_reader_heading_status_bar)

    NovelStatusBarTap.entries.forEach { tap ->
        ActionSelectItem(
            label = stringResource(tap.titleRes),
            preference = novelReaderPreferences.statusTaps.getValue(tap),
        )
    }

    SectionHeading(MR.strings.leaf_novel_reader_heading_value_gestures)

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

@Composable
private fun ColumnScope.SectionHeading(label: StringResource, showDivider: Boolean = true) {
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = MaterialTheme.padding.small,
            ),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    HeadingItem(label)
}

@Composable
private fun ChipSettingRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        )
        FlowRow(
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            content = content,
        )
    }
}

@Composable
private fun ImageSizeRow(selected: NovelImageSize, onSelect: (NovelImageSize) -> Unit) {
    ChipSettingRow(stringResource(MR.strings.leaf_novel_reader_image_size)) {
        NovelImageSize.entries.forEach { candidate ->
            FilterChip(
                selected = selected == candidate,
                onClick = { onSelect(candidate) },
                label = { Text(stringResource(candidate.titleRes)) },
            )
        }
    }
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
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                repeat(NovelTapGrid.SIDE) { column ->
                    TapZoneCell(
                        preference = tapZones[row * NovelTapGrid.SIDE + column],
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
            modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 72.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.padding.extraSmall,
                vertical = MaterialTheme.padding.small,
            ),
        ) {
            Text(
                text = stringResource(action.titleRes),
                style = MaterialTheme.typography.labelMedium,
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
                .clickable(role = Role.Button) { expanded = true }
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = labelOf(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = MaterialSymbols.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 280.dp),
    ) {
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

/** Twenty equal intervals across the 0–200 paragraph and margin controls. */
private const val STEPPED_SLIDER_STOPS = 19
