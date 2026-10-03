package com.gtranca

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gtranca.ui.AppNavHost
import com.gtranca.ui.theme.GTrancaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GTrancaTheme {
                AppNavHost()
            }
        }
    }
}
