package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ContentCopy
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A colour as a saturation/brightness field, a hue strip, an opacity strip and its hex code.
 *
 * Hue, saturation and value are held here rather than derived from [color] on every draw: a grey
 * or a black has no hue of its own, so deriving it would snap the hue strip back to red the moment
 * the reader dragged into a corner.
 */
@Composable
fun NovelColorPicker(color: Int, onColorChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val initial = remember { hsvOf(color) }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var alpha by remember { mutableFloatStateOf(android.graphics.Color.alpha(color) / 255f) }
    val currentOnColorChange by rememberUpdatedState(onColorChange)

    // Only a colour from outside (another slot, a typed code) moves the handles. Our own writes
    // round-trip to the same value and are left alone, so the hue survives a drag through grey.
    LaunchedEffect(color) {
        if (color == Color.hsv(hue, saturation, value, alpha).toArgb()) return@LaunchedEffect
        val hsv = hsvOf(color)
        if (hsv[1] > 0f && hsv[2] > 0f) hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = android.graphics.Color.alpha(color) / 255f
    }

    fun commit() = currentOnColorChange(Color.hsv(hue, saturation, value, alpha).toArgb())

    val hueColor = Color.hsv(hue, 1f, 1f)
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pickOnDrag { x, y ->
                    saturation = x
                    value = 1f - y
                    commit()
                },
        ) {
            val radius = CornerRadius(12.dp.toPx())
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, hueColor)), cornerRadius = radius)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = radius)
            drawRoundRect(outline, cornerRadius = radius, style = Stroke(1.dp.toPx()))
            drawThumb(
                Offset(saturation * size.width, (1f - value) * size.height),
                Color.hsv(hue, saturation, value),
            )
        }

        Strip(
            fraction = hue / 360f,
            thumbColor = hueColor,
            onPick = {
                hue = it * 360f
                commit()
            },
        ) { _, _ ->
            Brush.horizontalGradient(HUE_STOPS)
        }

        Strip(
            fraction = alpha,
            thumbColor = Color.hsv(hue, saturation, value, alpha),
            onPick = {
                alpha = it
                commit()
            },
        ) { left, right ->
            val opaque = Color.hsv(hue, saturation, value)
            Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque), startX = left, endX = right)
        }

        HexRow(color = color, onColorChange = onColorChange)
    }
}

/** A pill-shaped track the thumb slides along, inset by the thumb so it never hangs off the end. */
@Composable
private fun Strip(
    fraction: Float,
    thumbColor: Color,
    onPick: (Float) -> Unit,
    brush: (left: Float, right: Float) -> Brush,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(THUMB_RADIUS * 2)
            .pickOnDrag(inset = THUMB_RADIUS) { x, _ -> onPick(x) },
    ) {
        val inset = THUMB_RADIUS.toPx()
        val trackHeight = 14.dp.toPx()
        val track = RoundRect(
            left = 0f,
            top = (size.height - trackHeight) / 2,
            right = size.width,
            bottom = (size.height + trackHeight) / 2,
            cornerRadius = CornerRadius(trackHeight / 2),
        )
        val path = Path().apply { addRoundRect(track) }
        clipPath(path) {
            drawCheckerboard()
            drawRect(brush(inset, size.width - inset))
        }
        drawPath(path, outline, style = Stroke(1.dp.toPx()))
        drawThumb(Offset(inset + fraction * (size.width - 2 * inset), size.height / 2), thumbColor)
    }
}

@Composable
private fun HexRow(color: Int, onColorChange: (Int) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(hexOf(color)) }
    var focused by remember { mutableStateOf(false) }
    // Typing is left alone until the field is let go, so a half-typed code is not rewritten under
    // the reader's fingers as each prefix happens to parse.
    LaunchedEffect(color, focused) { if (!focused) text = hexOf(color) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        ) {
            clipPath(Path().apply { addOval(size.toRect()) }) {
                drawCheckerboard()
                drawRect(Color(color))
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input.filter { it.isLetterOrDigit() }.uppercase().take(8)
                parseHex(text, color)?.let(onColorChange)
            },
            prefix = { Text("#") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
        )
        IconButton(onClick = { context.copyToClipboard("#${hexOf(color)}", "#${hexOf(color)}") }) {
            Icon(
                MaterialSymbols.Rounded.ContentCopy,
                contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
            )
        }
    }
}

/** Reports every touch and drag on the element as fractions of its width and height, clamped. */
private fun Modifier.pickOnDrag(
    inset: androidx.compose.ui.unit.Dp = 0.dp,
    onPick: (x: Float, y: Float) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val insetPx = inset.toPx()
        fun report(position: Offset) = onPick(
            ((position.x - insetPx) / (size.width - 2 * insetPx)).coerceIn(0f, 1f),
            (position.y / size.height).coerceIn(0f, 1f),
        )
        awaitEachGesture {
            // Consumed throughout, so the settings sheet scrolling around this does not take the drag.
            val down = awaitFirstDown()
            down.consume()
            report(down.position)
            drag(down.id) { change ->
                change.consume()
                report(change.position)
            }
        }
    },
)

private fun DrawScope.drawThumb(center: Offset, color: Color) {
    val radius = THUMB_RADIUS.toPx()
    drawCircle(Color.Black.copy(alpha = 0.2f), radius + 1.dp.toPx(), center)
    drawCircle(Color.White, radius, center)
    drawCircle(color.copy(alpha = 1f), radius - 3.dp.toPx(), center)
}

/** What shows through a colour that is not fully opaque. */
private fun DrawScope.drawCheckerboard() {
    val cell = 6.dp.toPx()
    drawRect(Color.White)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(Color.LightGray, Offset(x, y), Size(cell, cell))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)

private fun hsvOf(color: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }

private fun hexOf(color: Int): String = "%08X".format(color)

/** Six digits keep the current opacity; eight carry their own. Anything else is still being typed. */
private fun parseHex(text: String, current: Int): Int? = when (text.length) {
    6 -> text.toLongOrNull(16)?.let { (current and 0xFF000000.toInt()) or it.toInt() }
    8 -> text.toLongOrNull(16)?.toInt()
    else -> null
}

private val THUMB_RADIUS = 14.dp

private val HUE_STOPS = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }
