package leaf.novel.presentation.reader

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.PlayArrow
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.RadioItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/** A permanent bottom deck for read-aloud, measured as part of the reader rather than a dialog. */
@Composable
fun NovelSpeechPanel(
    speaking: Boolean,
    paused: Boolean,
    index: Int,
    count: Int,
    previousPageEnabled: Boolean,
    nextPageEnabled: Boolean,
    preferences: NovelReaderPreferences,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onSettingsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            VolumeSlider()
            PreferenceSlider(
                label = stringResource(MR.strings.leaf_novel_reader_speech_pitch),
                preference = preferences.speechPitch,
                range = NovelReaderPreferences.SPEECH_PITCH_RANGE,
                valueText = { it.toString() },
                onCommit = onSettingsChanged,
            )
            PreferenceSlider(
                label = stringResource(MR.strings.leaf_novel_reader_speech_rate),
                preference = preferences.speechRate,
                range = NovelReaderPreferences.SPEECH_RATE_RANGE,
                valueText = { it.toString() },
                onCommit = onSettingsChanged,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onStop, modifier = Modifier.size(SPEECH_BUTTON_SIZE)) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.leaf_novel_action_stop_speaking),
                        modifier = Modifier.size(SPEECH_ICON_SIZE),
                    )
                }
                IconButton(
                    onClick = onPreviousPage,
                    enabled = previousPageEnabled,
                    modifier = Modifier.size(SPEECH_BUTTON_SIZE),
                ) {
                    Row {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.KeyboardArrowLeft,
                            contentDescription = null,
                            modifier = Modifier.size(SPEECH_PAGE_ICON_SIZE),
                        )
                        Icon(
                            imageVector = MaterialSymbols.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_previous_page),
                            modifier = Modifier.size(SPEECH_PAGE_ICON_SIZE),
                        )
                    }
                }
                IconButton(
                    onClick = onPrevious,
                    enabled = speaking && index > 0,
                    modifier = Modifier.size(SPEECH_BUTTON_SIZE),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.SkipPrevious,
                        contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_previous),
                        modifier = Modifier.size(SPEECH_ICON_SIZE),
                    )
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(SPEECH_BUTTON_SIZE)) {
                    Icon(
                        imageVector = if (speaking && !paused) {
                            MaterialSymbols.Rounded.Pause
                        } else {
                            MaterialSymbols.Rounded.PlayArrow
                        },
                        contentDescription = stringResource(
                            when {
                                speaking && !paused -> MR.strings.action_pause
                                speaking -> MR.strings.action_resume
                                else -> MR.strings.leaf_novel_action_speak
                            },
                        ),
                        modifier = Modifier.size(SPEECH_ICON_SIZE),
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = speaking && index < count - 1,
                    modifier = Modifier.size(SPEECH_BUTTON_SIZE),
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.SkipNext,
                        contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_next),
                        modifier = Modifier.size(SPEECH_ICON_SIZE),
                    )
                }
                IconButton(
                    onClick = onNextPage,
                    enabled = nextPageEnabled,
                    modifier = Modifier.size(SPEECH_BUTTON_SIZE),
                ) {
                    Row {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_next_page),
                            modifier = Modifier.size(SPEECH_PAGE_ICON_SIZE),
                        )
                        Icon(
                            imageVector = MaterialSymbols.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(SPEECH_PAGE_ICON_SIZE),
                        )
                    }
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(SPEECH_BUTTON_SIZE)) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Settings,
                        contentDescription = stringResource(MR.strings.action_settings),
                        modifier = Modifier.size(SPEECH_ICON_SIZE),
                    )
                }
            }
        }
    }
}

/** Mihon's adaptive settings surface, opened without unmounting the playback panel behind it. */
@Composable
fun NovelSpeechOptionsDialog(
    preferences: NovelReaderPreferences,
    onSettingsChanged: () -> Unit,
    onTimerChanged: () -> Unit,
    onDivisionChanged: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        SpeechOptions(
            preferences = preferences,
            onSettingsChanged = onSettingsChanged,
            onTimerChanged = onTimerChanged,
            onDivisionChanged = onDivisionChanged,
            onDismissRequest = onDismissRequest,
        )
    }
}

@Composable
private fun SpeechOptions(
    preferences: NovelReaderPreferences,
    onSettingsChanged: () -> Unit,
    onTimerChanged: () -> Unit,
    onDivisionChanged: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
    ) {
        Text(
            text = stringResource(MR.strings.leaf_novel_reader_speech_options),
            style = MaterialTheme.typography.titleLarge,
        )

        val division by preferences.speechDivision.collectAsState()
        Text(
            text = stringResource(MR.strings.leaf_novel_reader_speech_divide_by),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = MaterialTheme.padding.small),
        )
        NovelSpeechDivision.entries.forEach { option ->
            RadioItem(
                label = stringResource(option.titleRes),
                selected = division == option,
                onClick = {
                    if (division != option) {
                        preferences.speechDivision.set(option)
                        onDivisionChanged()
                    }
                },
            )
        }

        PreferenceSlider(
            label = stringResource(MR.strings.leaf_novel_reader_speech_interval),
            preference = preferences.speechIntervalMs,
            range = NovelReaderPreferences.SPEECH_INTERVAL_RANGE,
            valueText = { "$it ms" },
            onCommit = onSettingsChanged,
        )
        PreferenceSlider(
            label = stringResource(MR.strings.leaf_novel_reader_speech_stop_after),
            preference = preferences.speechStopAfterMinutes,
            range = NovelReaderPreferences.SPEECH_STOP_AFTER_RANGE,
            valueText = {
                if (it == 0) {
                    stringResource(MR.strings.leaf_novel_reader_speech_off)
                } else {
                    stringResource(MR.strings.leaf_novel_reader_speech_minutes, it)
                }
            },
            onCommit = onTimerChanged,
        )

        CheckboxItem(
            label = stringResource(MR.strings.leaf_novel_reader_speech_confirm),
            pref = preferences.speechConfirmBeforeSpeak,
        )

        val mixAudio by preferences.speechMixAudio.collectAsState()
        CheckboxItem(
            label = stringResource(MR.strings.leaf_novel_reader_speech_mix_audio),
            checked = mixAudio,
            onClick = {
                preferences.speechMixAudio.set(!mixAudio)
                onSettingsChanged()
            },
        )

        Text(
            text = stringResource(MR.strings.leaf_novel_reader_speech_filters_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = MaterialTheme.padding.medium),
        )

        TextButton(
            onClick = onDismissRequest,
            modifier = Modifier.align(Alignment.End).padding(horizontal = MaterialTheme.padding.small),
        ) {
            Text(stringResource(MR.strings.action_ok))
        }
    }
}

@Composable
private fun VolumeSlider() {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maximum = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember(audioManager) {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, maximum.toFloat())
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume.roundToInt(), 0)
    }

    LabeledSlider(
        label = stringResource(MR.strings.leaf_novel_reader_speech_volume),
        value = volume,
        valueRange = 0f..maximum.toFloat(),
        valueText = volume.roundToInt().toString(),
        onValueChange = ::setVolume,
        onDecrease = { setVolume(volume - 1f) },
        onIncrease = { setVolume(volume + 1f) },
    )
}

@Composable
private fun PreferenceSlider(
    label: String,
    preference: Preference<Int>,
    range: IntRange,
    valueText: @Composable (Int) -> String,
    onCommit: () -> Unit,
) {
    val stored by preference.collectAsState()
    var value by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val rounded = value.roundToInt()

    fun commit(newValue: Float) {
        value = newValue.coerceIn(range.first.toFloat(), range.last.toFloat())
        preference.set(value.roundToInt())
        onCommit()
    }

    LabeledSlider(
        label = label,
        value = value,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        valueText = valueText(rounded),
        onValueChange = { value = it },
        onValueChangeFinished = {
            preference.set(rounded)
            onCommit()
        },
        onDecrease = { commit(value - 1f) },
        onIncrease = { commit(value + 1f) },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(SPEECH_SLIDER_LABEL_WIDTH),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SPEECH_SLIDER_VALUE_WIDTH),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    thumbSize = SPEECH_SLIDER_THUMB_SIZE,
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(SPEECH_SLIDER_TRACK_HEIGHT),
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                )
            },
            modifier = Modifier
                .weight(1f)
                .height(SPEECH_SLIDER_HEIGHT),
        )
        IconButton(
            onClick = onDecrease,
            enabled = value > valueRange.start,
            modifier = Modifier.size(SPEECH_STEP_BUTTON_SIZE),
        ) {
            Text(text = "−", style = MaterialTheme.typography.titleMedium)
        }
        IconButton(
            onClick = onIncrease,
            enabled = value < valueRange.endInclusive,
            modifier = Modifier.size(SPEECH_STEP_BUTTON_SIZE),
        ) {
            Text(text = "+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val SPEECH_SLIDER_LABEL_WIDTH = 64.dp
private val SPEECH_SLIDER_VALUE_WIDTH = 32.dp
private val SPEECH_SLIDER_HEIGHT = 32.dp
private val SPEECH_SLIDER_TRACK_HEIGHT = 2.dp
private val SPEECH_SLIDER_THUMB_SIZE = DpSize(14.dp, 14.dp)
private val SPEECH_STEP_BUTTON_SIZE = 32.dp
private val SPEECH_BUTTON_SIZE = 32.dp
private val SPEECH_ICON_SIZE = 20.dp
private val SPEECH_PAGE_ICON_SIZE = 14.dp
