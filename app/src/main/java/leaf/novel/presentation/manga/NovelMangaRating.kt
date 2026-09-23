package leaf.novel.presentation.manga

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.manga.components.DotSeparatorText
import leaf.novel.api.NovelCommentRating
import leaf.novel.api.NovelRating
import leaf.novel.presentation.reader.comments.NovelCommentGlyphs
import leaf.novel.presentation.reader.comments.NovelCommentRatingRow
import leaf.novel.ui.manga.NovelRatings
import leaf.novel.ui.manga.acrossSites
import leaf.novel.ui.manga.outOfFive
import leaf.novel.ui.reader.comments.NovelCommentMatcher
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Public
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.text.NumberFormat
import java.util.Locale

/**
 * A novel's rating, under its title: its own site's, and the one across every site that has it.
 *
 * Drawn from the title block upstream owns, so everything it needs it finds for itself from [manga]
 * — the source, the search, the fetches — and the seam there is one call. Draws nothing on a manga,
 * and nothing on a novel until some site has a rating for it.
 *
 * Tapping it lists every site, with how much each counts towards the rating across them.
 */
@Composable
fun NovelRatingLine(manga: Manga, modifier: Modifier = Modifier) {
    if (!manga.isNovel) return
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val scope = rememberCoroutineScope()
    val saved = remember(manga.memo) { NovelRating.read(manga.memo) }
    val ratings = remember(manga.id) {
        NovelRatings(scope, NovelCommentMatcher.installed(graph.sourceManager, graph.sourcePreferences))
    }
    var sourceName by remember(manga.id) { mutableStateOf("") }
    LaunchedEffect(manga.id) {
        val source = graph.sourceManager.get(manga.source)
        sourceName = source?.name.orEmpty()
        ratings.bind(source, manga.toSManga(), saved)
    }
    val state by ratings.state.collectAsState()
    var showing by rememberSaveable(manga.id) { mutableStateOf(false) }

    // The saved rating wins over one asked for, since it is the one a refresh keeps current.
    val own = saved?.let { NovelRatings.Site(-1, sourceName, it, own = true) } ?: state.own
    val sites = listOfNotNull(own) + state.others
    val global = acrossSites(sites.map { it.rating })
        // Across sites means more than one: the own site's figure alone is already drawn beside it.
        ?.takeIf { sites.any { !it.own && (it.rating.count ?: 0) > 0 } }
    if (own == null && global == null) return

    val description = listOfNotNull(
        own?.let {
            stringResource(
                MR.strings.leaf_novel_rating_description,
                it.rating.outOfFive().oneDecimal(),
                it.rating.count?.let(::grouped) ?: "?",
                it.name,
            )
        },
        global?.let {
            stringResource(
                MR.strings.leaf_novel_rating_description_all,
                it.value.oneDecimal(),
                grouped(it.count ?: 0),
            )
        },
    ).joinToString(". ")

    Row(
        modifier = modifier
            .padding(top = 2.dp)
            .clickableNoIndication { showing = true }
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            if (own != null) {
                RatingFigure(NovelCommentGlyphs.FilledStar, MaterialTheme.colorScheme.primary, own.rating)
            }
            if (own != null && global != null) DotSeparatorText()
            if (global != null) {
                RatingFigure(MaterialSymbols.Rounded.Public, LocalContentColor.current, global)
            }
            if (state.searching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(12.dp),
                    strokeWidth = 1.5.dp,
                )
            }
        }
    }

    if (showing) {
        NovelRatingsDialog(
            sites = sites,
            global = global,
            searching = state.searching,
            onDismissRequest = { showing = false },
        )
    }
}

/** The glyph, the rating on five stars, and — quieter — how many gave it. */
@Composable
private fun RatingFigure(icon: ImageVector, tint: Color, rating: NovelRating) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .padding(end = 4.dp)
            .size(16.dp),
    )
    Text(
        text = rating.outOfFive().oneDecimal(),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
    rating.count?.let {
        Text(
            text = " (${compact(it)})",
            modifier = Modifier.secondaryItemAlpha(),
            maxLines = 1,
        )
    }
}

@Composable
private fun NovelRatingsDialog(
    sites: List<NovelRatings.Site>,
    global: NovelRating?,
    searching: Boolean,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_close)) }
        },
        title = { Text(stringResource(MR.strings.leaf_novel_rating_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (global != null) {
                    SiteRating(
                        name = stringResource(MR.strings.leaf_novel_rating_all_sites),
                        rating = global,
                        emphasised = true,
                    )
                    HorizontalDivider()
                }
                sites.forEach { site ->
                    val weight = site.rating.count?.takeIf { it > 0 }
                    SiteRating(
                        name = site.name,
                        rating = site.rating,
                        label = if (site.own) stringResource(MR.strings.leaf_novel_rating_this_site) else null,
                        share = global?.count?.takeIf { weight != null && it > 0 }?.let { weight!!.toDouble() / it },
                    )
                }
                if (searching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(MR.strings.leaf_novel_rating_searching),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (global != null) {
                    Text(
                        stringResource(MR.strings.leaf_novel_rating_weighting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * One site, or all of them: the name and its share of the rating across sites on the first line,
 * the stars and how many gave them on the second.
 */
@Composable
private fun SiteRating(
    name: String,
    rating: NovelRating,
    label: String? = null,
    share: Double? = null,
    emphasised: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The name and its label take what the share leaves, so every share lines up at the end.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (emphasised) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (share != null) {
                Text(
                    text = percent(share),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelCommentRatingRow(NovelCommentRating(rating.value, rating.maximum))
            Text(
                text = rating.count?.let { stringResource(MR.strings.leaf_novel_rating_rated, grouped(it)) }
                    ?: stringResource(MR.strings.leaf_novel_rating_no_count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Double.oneDecimal(): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(this)

/** A share too small to round to 1% still counts, so it reads as under one rather than as none. */
private fun percent(share: Double): String {
    val format = NumberFormat.getPercentInstance()
    return if (share > 0 && share < 0.005) "<${format.format(0.01)}" else format.format(share)
}

private fun grouped(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/** 1.2K, in the reader's own language, for the title block where there is no room for 1,234. */
private fun compact(count: Int): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(count)
