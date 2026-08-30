package leaf.novel.presentation.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import leaf.novel.ui.reader.NovelTextReplacements
import leaf.novel.ui.reader.setting.NovelTextReplacement
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * The list of find-and-replace rules, and the dialog that edits one.
 *
 * Rules apply in the order they were added, so the list is not reorderable — moving one is
 * deleting it and adding it again, and nobody has asked for better yet.
 *
 * The whole list round-trips through JSON on every edit. It is a handful of small objects written
 * when a person taps Save, so the cost is nothing and it keeps the stored form and the edited form
 * from being two things that have to agree.
 */
@Composable
fun ColumnScope.TextReplacements(preference: Preference<String>) {
    val rulesJson by preference.collectAsState()
    val rules = remember(rulesJson) { NovelTextReplacements.parse(rulesJson) }

    // The rule being edited, and where it goes back. A null index is one being added.
    var editing by remember { mutableStateOf<Pair<Int?, NovelTextReplacement>?>(null) }

    fun write(updated: List<NovelTextReplacement>) {
        preference.set(NovelTextReplacements.encode(updated))
    }

    HeadingItem(MR.strings.leaf_novel_reader_heading_replacements)

    Text(
        text = stringResource(MR.strings.leaf_novel_reader_replacements_subtitle),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(horizontal = SettingsItemsPaddings.Horizontal)
            .secondaryItemAlpha(),
    )

    rules.forEachIndexed { index, rule ->
        RuleRow(
            rule = rule,
            onToggle = { write(rules.replacing(index, rule.copy(enabled = it))) },
            onClick = { editing = index to rule },
        )
    }

    Text(
        text = stringResource(MR.strings.leaf_novel_reader_add_replacement),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { editing = null to NovelTextReplacement() }
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )

    editing?.let { (index, rule) ->
        RuleDialog(
            rule = rule,
            onDismissRequest = { editing = null },
            onDelete = index?.let {
                {
                    write(rules.removing(it))
                    editing = null
                }
            },
            onSave = { saved ->
                write(if (index == null) rules + saved else rules.replacing(index, saved))
                editing = null
            },
        )
    }
}

/** One rule: what it is called, what it matches, and whether it is on. */
@Composable
private fun RuleRow(rule: NovelTextReplacement, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // An unnamed rule is identified by what it looks for, which is the next best thing.
                text = rule.title.ifBlank { rule.pattern },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    MR.strings.leaf_novel_reader_replacement_summary,
                    rule.pattern,
                    rule.replacement,
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
            )
        }

        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

/**
 * Editing one rule.
 *
 * A dialog over the settings dialog, which is the shape the reader already uses for the things that
 * need more than a row — and the only one that gives a text field room to be typed into.
 */
@Composable
private fun RuleDialog(
    rule: NovelTextReplacement,
    onDismissRequest: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (NovelTextReplacement) -> Unit,
) {
    var draft by remember { mutableStateOf(rule) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.leaf_novel_reader_heading_replacements)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft = draft.copy(title = it) },
                    label = { Text(stringResource(MR.strings.leaf_novel_reader_replacement_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.pattern,
                    onValueChange = { draft = draft.copy(pattern = it) },
                    label = { Text(stringResource(MR.strings.leaf_novel_reader_replacement_find)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.replacement,
                    onValueChange = { draft = draft.copy(replacement = it) },
                    label = { Text(stringResource(MR.strings.leaf_novel_reader_replacement_replace)) },
                    singleLine = true,
                )

                RuleSwitch(
                    labelRes = MR.strings.leaf_novel_reader_replacement_regex,
                    checked = draft.isRegex,
                ) { draft = draft.copy(isRegex = it) }

                RuleSwitch(
                    labelRes = MR.strings.leaf_novel_reader_replacement_case,
                    checked = draft.caseSensitive,
                ) { draft = draft.copy(caseSensitive = it) }

                // Meaningless once the pattern is a regular expression, which can say so itself.
                if (!draft.isRegex) {
                    RuleSwitch(
                        labelRes = MR.strings.leaf_novel_reader_replacement_whole_word,
                        checked = draft.matchWholeWord,
                    ) { draft = draft.copy(matchWholeWord = it) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.pattern.isNotBlank(),
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(MR.strings.action_delete))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun RuleSwitch(
    labelRes: dev.icerock.moko.resources.StringResource,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable { onCheckedChange(!checked) }
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun List<NovelTextReplacement>.replacing(index: Int, rule: NovelTextReplacement) =
    toMutableList().also { it[index] = rule }

private fun List<NovelTextReplacement>.removing(index: Int) =
    toMutableList().also { it.removeAt(index) }
