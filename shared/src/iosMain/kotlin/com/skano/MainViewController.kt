package com.skano

import androidx.compose.ui.window.ComposeUIViewController
import com.skano.di.AppContainer
import com.skano.platform.createDriver

// The iOS driver actual ignores the context argument.
fun MainViewController() = ComposeUIViewController { App(AppContainer(createDriver(null))) }
