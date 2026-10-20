package com.skano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.skano.di.AppContainer
import com.skano.platform.createDriver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // Built once per Activity instance (remember): recreating the
            // container on recomposition would open a new SQLite connection
            // every frame.
            val container = remember { AppContainer(createDriver(applicationContext)) }
            App(container)
        }
    }
}
