package leaf.novel.presentation.reader.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import leaf.novel.ui.reader.NovelStatusLine
import leaf.novel.ui.reader.setting.NovelReaderColors
import leaf.novel.ui.reader.setting.NovelStatusItem
import leaf.novel.ui.reader.setting.NovelStatusPlacement
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How tall the bar is, so the page above it can reserve the space.
 *
 * It is a constant rather than a measurement because the bar is one line of a fixed text style, and
 * a measured height would cost a frame of the last line sitting underneath it on every chapter.
 */
val NovelStatusBarHeight = 22.dp

/**
 * The strip at the foot of the page: battery, clock, where you are in the chapter and in the book.
 *
 * Which of those it shows and in which of its three sections is a placement per item rather than a
 * fixed layout — see [NovelStatusItem]. A section with nothing placed in it renders empty and stays
 * tappable, so the bindings are where the reader left them whatever the bar is showing.
 *
 * It takes the page's own colours rather than the app theme's, so a permanent strip does not sit
 * dark on a white page whenever the app is in dark mode, and a fork theme moves the two together.
 */
@Composable
fun NovelStatusBar(
    placements: Map<NovelStatusItem, NovelStatusPlacement>,
    chapterName: String,
    chapterNumber: Int,
    chapterCount: Int,
    chapterPercent: Int,
    screens: NovelStatusLine.Screens,
    minutesRemaining: Int,
    colors: NovelReaderColors,
    onTap: (NovelStatusPlacement) -> Unit,
    onLongTap: (NovelStatusPlacement) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only the two that cost a broadcast receiver are read conditionally. The rest are values the
    // screen already holds, so formatting one that turns out to be unplaced costs nothing.
    val battery = if (placements[NovelStatusItem.BATTERY] != NovelStatusPlacement.OFF) batteryPercent() else null
    val clock = if (placements[NovelStatusItem.CLOCK] != NovelStatusPlacement.OFF) clockText() else null

    val texts = mapOf(
        NovelStatusItem.BATTERY to battery?.let { "{$it}" },
        NovelStatusItem.CLOCK to clock,
        NovelStatusItem.REMAINING_TIME to stringResource(MR.strings.leaf_novel_reader_minutes_left, minutesRemaining),
        NovelStatusItem.CHAPTER_NAME to chapterName,
        NovelStatusItem.POSITION_IN_CHAPTER to "(${screens.current}/${screens.total})",
        NovelStatusItem.CHAPTER_NUMBER to "$chapterNumber/$chapterCount",
        NovelStatusItem.CHAPTER_PERCENT to "$chapterPercent%",
        NovelStatusItem.BOOK_PERCENT to
            "${NovelStatusLine.bookPercent(chapterNumber - 1, chapterCount, chapterPercent)}%",
    )

    val foreground = Color(colors.foreground)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(colors.background))
            .navigationBarsPadding(),
    ) {
        // Enough to separate the bar from the last line of text without reading as a rule.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DIVIDER_HEIGHT)
                .background(foreground.copy(alpha = DIVIDER_ALPHA)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NovelStatusBarHeight - DIVIDER_HEIGHT)
                .padding(horizontal = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelStatusBarSection(
                text = sectionText(NovelStatusPlacement.LEFT, placements, texts),
                placement = NovelStatusPlacement.LEFT,
                weight = SIDE_WEIGHT,
                arrangement = Arrangement.Start,
                color = foreground.copy(alpha = CONTENT_ALPHA),
                onTap = onTap,
                onLongTap = onLongTap,
            )
            NovelStatusBarSection(
                text = sectionText(NovelStatusPlacement.MIDDLE, placements, texts),
                placement = NovelStatusPlacement.MIDDLE,
                weight = MIDDLE_WEIGHT,
                arrangement = Arrangement.Center,
                color = foreground.copy(alpha = CONTENT_ALPHA),
                onTap = onTap,
                onLongTap = onLongTap,
            )
            NovelStatusBarSection(
                text = sectionText(NovelStatusPlacement.RIGHT, placements, texts),
                placement = NovelStatusPlacement.RIGHT,
                weight = SIDE_WEIGHT,
                arrangement = Arrangement.End,
                color = foreground.copy(alpha = CONTENT_ALPHA),
                onTap = onTap,
                onLongTap = onLongTap,
            )
        }
    }
}

/**
 * One section, which claims its own presses.
 *
 * The click modifier is what stops a tap on the bar also reaching the page's tap grid underneath —
 * the page is padded out from under the bar as well, so the two agree rather than one relying on
 * the other.
 */
@Composable
private fun RowScope.NovelStatusBarSection(
    text: String,
    placement: NovelStatusPlacement,
    weight: Float,
    arrangement: Arrangement.Horizontal,
    color: Color,
    onTap: (NovelStatusPlacement) -> Unit,
    onLongTap: (NovelStatusPlacement) -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .combinedClickable(
                onLongClick = { onLongTap(placement) },
                onClick = { onTap(placement) },
            ),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Everything placed in one section, in the order [NovelStatusItem] declares. */
private fun sectionText(
    placement: NovelStatusPlacement,
    placements: Map<NovelStatusItem, NovelStatusPlacement>,
    texts: Map<NovelStatusItem, String?>,
): String = NovelStatusItem.entries
    .filter { placements[it] == placement }
    .mapNotNull { texts[it]?.takeIf(String::isNotBlank) }
    .joinToString(" ")

/**
 * The charge level, from the sticky battery broadcast.
 *
 * Sticky is why the bar is never blank on its first frame: registering delivers the last value the
 * system sent rather than waiting for the next change. No permission is involved.
 */
@Composable
private fun batteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) percent = level * 100 / scale
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    return percent
}

/**
 * The time of day, in whichever of the two formats the phone is set to.
 *
 * Driven by the minute tick the system already broadcasts rather than a timer of our own, so the
 * bar changes at the same moment the system clock does and costs nothing in between.
 */
@Composable
private fun clockText(): String {
    val context = LocalContext.current
    val formatter = remember(context) {
        val pattern = if (DateFormat.is24HourFormat(context)) HOUR_24_PATTERN else HOUR_12_PATTERN
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }
    var text by remember(formatter) { mutableStateOf(LocalTime.now().format(formatter)) }

    DisposableEffect(context, formatter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                text = LocalTime.now().format(formatter)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    return text
}

private val DIVIDER_HEIGHT = 1.dp

/** Legible without competing with the page: the bar is furniture, not text. */
private const val CONTENT_ALPHA = 0.6f
private const val DIVIDER_ALPHA = 0.15f

/**
 * The middle gets half the bar because it carries the chapter name, which is the only item long
 * enough to need the room. The sections stay fixed fractions either way, so a section is where the
 * reader last saw it however little is placed in it.
 */
private const val SIDE_WEIGHT = 1f
private const val MIDDLE_WEIGHT = 2f

private const val HOUR_24_PATTERN = "HH:mm"
private const val HOUR_12_PATTERN = "h:mm a"
