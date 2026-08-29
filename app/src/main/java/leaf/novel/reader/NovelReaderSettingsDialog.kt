package leaf.novel.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

private val readerThemes = listOf(
    0 to MR.strings.white_background,
    2 to MR.strings.gray_background,
    1 to MR.strings.black_background,
    3 to MR.strings.automatic_background,
)

/**
 * Font size, plus the shared background-colour choice so the reader can be adjusted without leaving
 * it. Everything else — brightness, colour filter, grayscale — stays in the app's reader settings,
 * where the manga reader already reads the same keys (D4).
 */
@Composable
fun NovelReaderSettingsDialog(
    fontSizePreference: Preference<Int>,
    readerPreferences: ReaderPreferences,
    onDismissRequest: () -> Unit,
) {
    val fontSize by fontSizePreference.collectAsState()
    val readerTheme by readerPreferences.readerTheme.collectAsState()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_close))
            }
        },
        title = { Text(text = stringResource(MR.strings.action_settings)) },
        text = {
            Column {
                Text(
                    text = stringResource(MR.strings.leaf_novel_reader_font_size, fontSize),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val minSize = NovelReaderPreferences.MIN_FONT_SIZE
                val maxSize = NovelReaderPreferences.MAX_FONT_SIZE
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { fontSizePreference.set(it.roundToInt()) },
                    valueRange = minSize.toFloat()..maxSize.toFloat(),
                    steps = maxSize - minSize - 1,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(MR.strings.pref_reader_theme),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = MaterialTheme.padding.medium),
                )
                readerThemes.forEach { (value, labelRes) ->
                    TextButton(
                        onClick = { readerPreferences.readerTheme.set(value) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = if (value == readerTheme) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        },
    )
}
