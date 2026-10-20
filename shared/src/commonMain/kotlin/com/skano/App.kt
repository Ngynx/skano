package com.skano

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.skano.di.AppContainer
import com.skano.presentation.scanner.ScannerScreen

@Composable
fun App(container: AppContainer) {
    MaterialTheme {
        ScannerScreen(container.records)
    }
}
