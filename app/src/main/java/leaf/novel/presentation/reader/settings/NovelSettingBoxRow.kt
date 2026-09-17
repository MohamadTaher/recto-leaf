package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding

/** One box in a [SettingBoxRow]. Unspecified colours fall back to a filter chip's. */
class SettingBox(
    val label: String,
    val onClick: () -> Unit,
    val selected: Boolean = false,
    val textStyle: TextStyle = TextStyle.Default,
    val containerColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)

/**
 * Boxes sharing one row, each as wide as its label needs, with every label set at one size.
 *
 * The labels are measured at the base size, and the row is split in proportion to those widths, so
 * one scale fits every label to its box at once — a short label gets a short box rather than smaller
 * letters. The scale is capped both ways: short rows stop growing before the text outgrows the box
 * height, and long rows ellipsize rather than shrinking past legibility.
 */
@Composable
fun SettingBoxRow(boxes: List<SettingBox>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = MaterialTheme.typography.labelLarge
    val gap = MaterialTheme.padding.small
    val inset = MaterialTheme.padding.small

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical / 2,
            ),
    ) {
        val natural = boxes.map { box ->
            measurer.measure(box.label, baseStyle.merge(box.textStyle), maxLines = 1).size.width.toFloat()
        }
        val insetPx = with(density) { inset.toPx() }
        val textPx = with(density) { (maxWidth - gap * (boxes.size - 1)).toPx() } - insetPx * 2 * boxes.size
        val scale = textPx / natural.sum().coerceAtLeast(1f) * FIT_MARGIN
        val fontSize = (baseStyle.fontSize.value * scale).coerceIn(MIN_FONT_SP, MAX_FONT_SP).sp

        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            boxes.forEachIndexed { index, box ->
                val shape = MaterialTheme.shapes.small
                val container = box.containerColor.takeOrElse {
                    if (box.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                }
                val content = box.contentColor.takeOrElse {
                    if (box.selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(natural[index] * textPx / natural.sum().coerceAtLeast(1f) + insetPx * 2)
                        .height(FilterChipDefaults.Height)
                        .clip(shape)
                        .background(container)
                        .border(
                            width = if (box.selected) 2.dp else 1.dp,
                            color = if (box.selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = shape,
                        )
                        .clickable(role = Role.Button, onClick = box.onClick)
                        .semantics { selected = box.selected }
                        .padding(horizontal = inset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = box.label,
                        color = content,
                        style = baseStyle.merge(box.textStyle).copy(fontSize = fontSize),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Rendering at a scaled size is not perfectly linear, so leave a little room to spare. */
private const val FIT_MARGIN = 0.92f
private const val MIN_FONT_SP = 11f
private const val MAX_FONT_SP = 18f
