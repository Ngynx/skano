package com.skano.presentation.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
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
 * top-end single/batch mode toggle, and a bottom chip overlay showing the
 * last persisted scan, its count, a copy affordance, and a dismiss button.
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
            if (last != null) {
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
