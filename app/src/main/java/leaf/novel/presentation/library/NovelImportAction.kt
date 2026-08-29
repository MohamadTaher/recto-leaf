package leaf.novel.presentation.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.launch
import leaf.novel.data.imports.NovelImportConflict
import leaf.novel.data.imports.NovelImportFailure
import leaf.novel.data.imports.NovelImportResult
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private data class PendingConflict(val uri: Uri, val title: String)

/**
 * "Import EPUB" affordance for the novel source's browse screen, plus the dialogs it needs.
 *
 * Mihon has no import UI to reuse, so this owns its own file picker, duplicate prompt and progress
 * dialog; the caller only supplies its snackbar host and where to go afterwards.
 */
@Composable
fun NovelImportAction(
    snackbarHostState: SnackbarHostState,
    onImported: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { context.appGraph.novelImporter }

    var importing by remember { mutableStateOf(false) }
    var pendingConflict by remember { mutableStateOf<PendingConflict?>(null) }

    fun runImport(uri: Uri, conflict: NovelImportConflict) {
        scope.launch {
            importing = true
            val result = try {
                importer.import(uri, conflict)
            } finally {
                importing = false
            }
            when (result) {
                // Navigating to the new entry is the confirmation; a snackbar shown here would be
                // cancelled with this screen's composition before it could appear.
                is NovelImportResult.Success -> onImported(result.mangaId)
                is NovelImportResult.Conflict -> {
                    pendingConflict = PendingConflict(uri, result.existingTitle)
                }
                is NovelImportResult.Failure -> {
                    snackbarHostState.showSnackbar(context.stringResource(result.reason.messageRes()))
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport(uri, NovelImportConflict.ASK)
    }

    AssistChip(
        onClick = { picker.launch(EPUB_MIME_TYPES) },
        leadingIcon = {
            Icon(
                imageVector = MaterialSymbols.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        label = { Text(text = stringResource(MR.strings.leaf_action_import_epub)) },
    )

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(text = stringResource(MR.strings.leaf_novel_import_in_progress)) },
            text = { Row { CircularProgressIndicator(modifier = Modifier.size(24.dp)) } },
        )
    }

    pendingConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { pendingConflict = null },
            title = { Text(text = stringResource(MR.strings.leaf_novel_import_duplicate_title)) },
            text = {
                Text(text = stringResource(MR.strings.leaf_novel_import_duplicate_body, conflict.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConflict = null
                        runImport(conflict.uri, NovelImportConflict.REPLACE)
                    },
                ) {
                    Text(text = stringResource(MR.strings.leaf_novel_import_action_replace))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingConflict = null
                        runImport(conflict.uri, NovelImportConflict.KEEP_BOTH)
                    },
                ) {
                    Text(text = stringResource(MR.strings.leaf_novel_import_action_keep_both))
                }
            },
        )
    }
}

/**
 * The specific type first, with a wildcard behind it: several file providers mistype EPUBs, and a
 * picker that hides the user's file is worse than one that shows too much.
 */
private val EPUB_MIME_TYPES = arrayOf("application/epub+zip", "*/*")

private fun NovelImportFailure.messageRes(): StringResource = when (this) {
    NovelImportFailure.NO_STORAGE_LOCATION -> MR.strings.no_location_set
    NovelImportFailure.UNREADABLE -> MR.strings.leaf_novel_import_error_unreadable
    NovelImportFailure.MISSING_PACKAGE -> MR.strings.leaf_novel_import_error_missing_package
    NovelImportFailure.EMPTY_SPINE -> MR.strings.leaf_novel_import_error_empty_spine
    NovelImportFailure.DRM_PROTECTED -> MR.strings.leaf_novel_import_error_drm
    NovelImportFailure.WRITE_FAILED -> MR.strings.leaf_novel_import_error_write
}
