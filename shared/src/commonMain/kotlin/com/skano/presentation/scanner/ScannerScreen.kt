package com.skano.presentation.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skano.data.repository.RecordRepository
import com.skano.domain.model.CodeFormat
import com.skano.platform.BarcodeScannerView
import com.skano.platform.currentTimeMillis
import com.skano.platform.rememberCameraPermissionState
import com.skano.platform.rememberPlatformClipboard
import kotlinx.coroutines.delay

/**
 * Temporary scanning screen (Phase 5 replaces this with real navigation):
 * live scanner when permission is granted, permission prompt when not, a
 * top-end single/batch mode toggle, and a bottom overlay showing results —
 * a bottom-center chip for the last scan in single mode, or a bottom-start
 * stack panel of distinct codes in batch mode (see [BatchScanPanel]).
 */
@Composable
fun ScannerScreen(records: RecordRepository) {
    val permission = rememberCameraPermissionState()
    val viewModel = remember {
        ScannerViewModel(records, nowMs = { currentTimeMillis() })
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.resetDebouncer() }
    }

    if (permission.isGranted) {
        Box(modifier = Modifier.fillMaxSize()) {
            BarcodeScannerView(
                formats = setOf(
                    CodeFormat.EAN_13,
                    CodeFormat.EAN_8,
                    CodeFormat.UPC_A,
                    CodeFormat.UPC_E,
                    CodeFormat.CODE_128,
                ),
                onDetected = viewModel::onRawDetection,
                modifier = Modifier.fillMaxSize(),
            )
            val uiState by viewModel.uiState.collectAsState()
            val clipboard = rememberPlatformClipboard()
            val last = uiState.lastScan
            // Result overlay is mode-exclusive: batch mode shows the stack
            // panel (single chip hidden — its lock semantics don't apply
            // here), single mode keeps the chip exactly as before. Batch ON
            // with an empty stack shows nothing, so aiming stays clear.
            if (uiState.batchMode) {
                if (uiState.scanned.isNotEmpty()) {
                    BatchScanPanel(
                        scanned = uiState.scanned,
                        onClear = viewModel::clearScanned,
                        onCopy = clipboard::setText,
                        onSetNote = viewModel::setEntryNote,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            // Clear of the home indicator / gesture bar (iPhone).
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(start = 16.dp, bottom = 16.dp),
                    )
                }
            } else if (last != null) {
                // Fresh false again whenever a NEW scan replaces the current one.
                var justCopied by remember(last) { mutableStateOf(false) }
                LaunchedEffect(justCopied) {
                    if (justCopied) {
                        delay(1_500L)
                        justCopied = false
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Clear of the home indicator / gesture bar (iPhone).
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(bottom = 24.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = if (justCopied) {
                                "${last.value}  ·  Copied"
                            } else {
                                "${last.value}  ·  ${last.format.name}  ×${uiState.count}"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.clickable {
                                clipboard.setText(last.value)
                                justCopied = true
                            },
                        )
                        TextButton(onClick = viewModel::dismissLastScan) {
                            Text(
                                text = "✕",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            // Mode toggle lives above the chip layer (not inside it): filled
            // when batch mode is engaged, outlined otherwise, so a single tap
            // is all it takes to lock or release scanning. safeDrawing keeps
            // the control clear of the status bar / Dynamic Island — inside
            // that system strip taps are unreliable (eaten by SpringBoard).
            Button(
                onClick = viewModel::toggleBatchMode,
                colors = if (uiState.batchMode) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
                border = if (uiState.batchMode) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            ) {
                Text(if (uiState.batchMode) "Batch ✓" else "Batch")
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Skano needs camera access to scan barcodes.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Button(onClick = permission::request, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Grant camera permission")
                }
            }
        }
    }
}

/**
 * Batch-mode result panel: distinct scanned codes stacked at the bottom-
 * START corner instead of the single bottom-center chip, so continuous
 * scanning is never interrupted by a result overlay.
 *
 * Rows render oldest→top / newest→bottom (first-seen order) and the list
 * auto-scrolls to the newest row as the stack grows. Each row leads with its
 * positional reference (#1, #2, …, stable across repeat upserts), keeps the
 * code on ONE line with Excel-style ellipsis for extreme-length values, and
 * anchors an editable manual reference-label slot (a "+" affordance when
 * blank, a pill once set — see [BatchRowNoteSlot]) at the row END, right of
 * the ×N accumulator. Size is capped in both dimensions — header + five rows
 * tall, 320dp wide max — so the screen center stays clear for aiming.
 * Tapping a row copies that code; tapping
 * the note slot opens the label editor (its own clickable consumes the
 * gesture, so it never also copies); the header ✕ clears the panel
 * (view-only, database untouched).
 *
 * [onSetNote] persists one row's label via the ViewModel; the editor dialog
 * is panel-level state (one at a time), not per-row state.
 *
 * [modifier] carries the Box anchor (BottomStart) + insets + corner padding
 * because [androidx.compose.ui.Alignment] only resolves inside a BoxScope.
 */
@Composable
private fun BatchScanPanel(
    scanned: List<ScannedEntry>,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
    onSetNote: (Long, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed by VALUE, not index: each copied row owns its 1.5s "Copied"
    // swap, and copying another row simply replaces the active one.
    var copiedValue by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedValue) {
        if (copiedValue != null) {
            delay(1_500L)
            copiedValue = null
        }
    }

    // Panel-level (NOT per-row): at most one label editor is open at a time.
    var editingRecordId by remember { mutableStateOf<Long?>(null) }

    val listState = rememberLazyListState()
    // Newest row sits at the bottom of the stack: keep it in view whenever
    // the panel grows (also runs on first entry, jumping to the last row).
    LaunchedEffect(scanned.size) {
        if (scanned.isNotEmpty()) listState.animateScrollToItem(scanned.lastIndex)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            // Wrap content up to the caps: ≈ header (44dp) + five rows
            // (5 × 44dp) tall, and narrow enough to leave the screen
            // center free for aiming.
            .widthIn(max = 320.dp)
            .heightIn(max = 264.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
            ) {
                Text(
                    text = "Scanned ${scanned.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) {
                    Text(
                        text = "✕",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
            )
            LazyColumn(state = listState) {
                itemsIndexed(scanned, key = { _, it -> "${it.value}·${it.format.name}" }) { index, entry ->
                    val copied = copiedValue == entry.value
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCopy(entry.value)
                                copiedValue = entry.value
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        // Positional reference (#1, #2, …): derived from the
                        // stable first-seen index, so it survives repeat
                        // upserts and restarts at #1 after a clear.
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        // Extreme-case guard (Excel-style trim): codes are
                        // short in practice, but a long Code-128 value must
                        // ellipsize instead of wrapping or overflowing.
                        Text(
                            text = if (copied) {
                                "${entry.value}  ·  Copied"
                            } else {
                                "${entry.value}  ·  ${entry.format.name}  ×${entry.count}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Manual reference label (e.g. "A07"): anchored at the
                        // row END, right of the ×N accumulator. Its own
                        // clickable consumes the tap, so editing a label
                        // never also copies the code.
                        BatchRowNoteSlot(
                            note = entry.note,
                            onClick = { editingRecordId = entry.recordId },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // Label editor for whichever row is being edited. Draft is reset
    // (prefilled from the entry's current note) on EVERY open because it is
    // remembered against editingRecordId, which nulls on dismiss.
    val editingId = editingRecordId
    if (editingId != null) {
        var draftNote by remember(editingId) {
            mutableStateOf(scanned.firstOrNull { it.recordId == editingId }?.note.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { editingRecordId = null },
            title = { Text("Reference label") },
            text = {
                OutlinedTextField(
                    value = draftNote,
                    onValueChange = { draftNote = it },
                    singleLine = true,
                    placeholder = { Text("e.g. A07") },
                )
            },
            confirmButton = {
                // Blank/whitespace-only save clears the label (ViewModel
                // normalizes it to null before persisting).
                TextButton(onClick = {
                    onSetNote(editingId, draftNote.trim())
                    editingRecordId = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRecordId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * One row's manual reference-label affordance inside [BatchScanPanel]:
 * a thin-outlined "+" box when the row has no label yet, or a compact
 * tertiary pill showing the current label. Both open the panel-level
 * editor on tap; the clickable here consumes the gesture so the row-wide
 * copy action does not also fire.
 */
@Composable
private fun BatchRowNoteSlot(
    note: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (note.isNullOrBlank()) {
        val slotShape = RoundedCornerShape(6.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .height(24.dp)
                .clip(slotShape)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    slotShape,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = modifier
                .widthIn(max = 88.dp)
                .clickable(onClick = onClick),
        ) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}
