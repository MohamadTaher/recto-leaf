package leaf.novel.presentation.reader

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import leaf.novel.ui.reader.setting.NovelReaderPreferences
import leaf.novel.ui.reader.setting.NovelSpeechDivision
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
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
    preferences: NovelReaderPreferences,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
                label = stringResource(MR.strings.leaf_novel_reader_speech_rate),
                preference = preferences.speechRate,
                range = NovelReaderPreferences.SPEECH_RATE_RANGE,
                valueText = { "${it / 10f}×" },
                onCommit = onSettingsChanged,
            )
            PreferenceSlider(
                label = stringResource(MR.strings.leaf_novel_reader_speech_pitch),
                preference = preferences.speechPitch,
                range = NovelReaderPreferences.SPEECH_PITCH_RANGE,
                valueText = { "${it / 10f}×" },
                onCommit = onSettingsChanged,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.leaf_novel_action_stop_speaking),
                    )
                }
                IconButton(onClick = onPrevious, enabled = speaking && index > 0) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.SkipPrevious,
                        contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_previous),
                    )
                }
                IconButton(onClick = onPlayPause) {
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
                    )
                }
                IconButton(onClick = onNext, enabled = speaking && index < count - 1) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.SkipNext,
                        contentDescription = stringResource(MR.strings.leaf_novel_reader_speech_next),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Settings,
                        contentDescription = stringResource(MR.strings.action_settings),
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
            .padding(vertical = MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(MR.strings.leaf_novel_reader_speech_options),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )

        val division by preferences.speechDivision.collectAsState()
        Text(
            text = stringResource(MR.strings.leaf_novel_reader_speech_divide_by),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
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
            modifier = Modifier.padding(MaterialTheme.padding.medium),
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

    LabeledSlider(
        label = stringResource(MR.strings.leaf_novel_reader_speech_volume),
        value = volume,
        valueRange = 0f..maximum.toFloat(),
        valueText = volume.roundToInt().toString(),
        onValueChange = { value ->
            volume = value
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt(), 0)
        },
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(SPEECH_SLIDER_LABEL_WIDTH),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SPEECH_SLIDER_VALUE_WIDTH),
        )
    }
}

private val SPEECH_SLIDER_LABEL_WIDTH = 72.dp
private val SPEECH_SLIDER_VALUE_WIDTH = 52.dp
