package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import leaf.novel.ui.reader.NovelTextReplacements
import leaf.novel.ui.reader.setting.NovelTextReplacement
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

private enum class ReplacementScope {
    NOVEL,
    APP_WIDE,
}

@Composable
fun ColumnScope.TextReplacements(
    appWidePreference: Preference<String>,
    novelRules: String,
    onNovelRulesChange: (String) -> Unit,
) {
    val appWideRules by appWidePreference.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    HeadingItem(MR.strings.leaf_novel_reader_heading_replacements)
    Text(
        text = stringResource(MR.strings.leaf_novel_reader_replacements_subtitle),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = SettingsItemsPaddings.Horizontal)
            .secondaryItemAlpha(),
    )
    Text(
        text = stringResource(MR.strings.leaf_novel_reader_edit_replacements),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { showEditor = true }
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    if (showEditor) {
        TextReplacementDialog(
            appWideRules = appWideRules,
            novelRules = novelRules,
            onDismissRequest = { showEditor = false },
            onSaveAppWide = appWidePreference::set,
            onSaveNovel = onNovelRulesChange,
        )
    }
}

@Composable
private fun TextReplacementDialog(
    appWideRules: String,
    novelRules: String,
    onDismissRequest: () -> Unit,
    onSaveAppWide: (String) -> Unit,
    onSaveNovel: (String) -> Unit,
) {
    var scope by remember { mutableStateOf(ReplacementScope.NOVEL) }
    var appWideDraft by remember(appWideRules) { mutableStateOf(rulesDraft(appWideRules)) }
    var novelDraft by remember(novelRules) { mutableStateOf(rulesDraft(novelRules)) }
    val rules = if (scope == ReplacementScope.NOVEL) novelDraft else appWideDraft

    fun updateRules(updated: List<NovelTextReplacement>) {
        if (scope == ReplacementScope.NOVEL) novelDraft = updated else appWideDraft = updated
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.leaf_novel_reader_heading_replacements)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    FilterChip(
                        selected = scope == ReplacementScope.NOVEL,
                        onClick = { scope = ReplacementScope.NOVEL },
                        label = { Text(stringResource(MR.strings.leaf_novel_reader_replacements_this_novel)) },
                    )
                    FilterChip(
                        selected = scope == ReplacementScope.APP_WIDE,
                        onClick = { scope = ReplacementScope.APP_WIDE },
                        label = { Text(stringResource(MR.strings.leaf_novel_reader_replacements_app_wide)) },
                    )
                }

                rules.forEachIndexed { index, rule ->
                    ReplacementRow(
                        rule = rule,
                        onChange = { updateRules(rules.replacing(index, it)) },
                        onDelete = {
                            updateRules(rules.removing(index).takeIf { it.isNotEmpty() } ?: emptyRule())
                        },
                    )
                }

                TextButton(onClick = { updateRules(rules + NovelTextReplacement()) }) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(MR.strings.leaf_novel_reader_add_replacement))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val encoded = NovelTextReplacements.encode(rules.simplePairs())
                    if (scope == ReplacementScope.NOVEL) onSaveNovel(encoded) else onSaveAppWide(encoded)
                    onDismissRequest()
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun ReplacementRow(
    rule: NovelTextReplacement,
    onChange: (NovelTextReplacement) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        ReplacementField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            placeholder = stringResource(MR.strings.leaf_novel_reader_replacement_find),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "→",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.secondaryItemAlpha(),
        )
        ReplacementField(
            value = rule.replacement,
            onValueChange = { onChange(rule.copy(replacement = it)) },
            placeholder = stringResource(MR.strings.leaf_novel_reader_replacement_replace),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = MaterialSymbols.Rounded.Delete,
            contentDescription = stringResource(MR.strings.action_delete),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onDelete)
                .padding(7.dp),
        )
    }
}

@Composable
private fun ReplacementField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun rulesDraft(rules: String): List<NovelTextReplacement> =
    NovelTextReplacements.parse(rules).ifEmpty(::emptyRule)

private fun emptyRule() = listOf(NovelTextReplacement())

private fun List<NovelTextReplacement>.simplePairs(): List<NovelTextReplacement> =
    filter { it.pattern.isNotBlank() }
        .map { NovelTextReplacement(pattern = it.pattern, replacement = it.replacement) }

private fun List<NovelTextReplacement>.replacing(index: Int, rule: NovelTextReplacement) =
    toMutableList().also { it[index] = rule }

private fun List<NovelTextReplacement>.removing(index: Int) =
    toMutableList().also { it.removeAt(index) }
