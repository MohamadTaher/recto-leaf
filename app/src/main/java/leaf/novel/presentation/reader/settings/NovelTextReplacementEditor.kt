package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            .heightIn(min = 48.dp)
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    if (showEditor) {
        NovelTextReplacementDialog(
            appWideRules = appWideRules,
            novelRules = novelRules,
            onDismissRequest = { showEditor = false },
            onSaveAppWide = appWidePreference::set,
            onSaveNovel = onNovelRulesChange,
        )
    }
}

@Composable
fun NovelTextReplacementDialog(
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
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReplacementScope.entries.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = scope == candidate,
                            onClick = { scope = candidate },
                            shape = SegmentedButtonDefaults.itemShape(index, ReplacementScope.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    when (candidate) {
                                        ReplacementScope.NOVEL -> MR.strings.leaf_novel_reader_replacements_this_novel
                                        ReplacementScope.APP_WIDE -> MR.strings.leaf_novel_reader_replacements_app_wide
                                    },
                                ),
                            )
                        }
                    }
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
                    Text(
                        text = stringResource(MR.strings.leaf_novel_reader_add_replacement),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (novelDraft != rulesDraft(novelRules)) {
                        onSaveNovel(NovelTextReplacements.encode(novelDraft.simplePairs()))
                    }
                    if (appWideDraft != rulesDraft(appWideRules)) {
                        onSaveAppWide(NovelTextReplacements.encode(appWideDraft.simplePairs()))
                    }
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            label = { Text(stringResource(MR.strings.leaf_novel_reader_replacement_find)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rule.replacement,
            onValueChange = { onChange(rule.copy(replacement = it)) },
            label = { Text(stringResource(MR.strings.leaf_novel_reader_replacement_replace)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
