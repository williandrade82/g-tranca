package com.gtranca

import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.gtranca.ui.sound.AndroidSoundPlayer
import com.gtranca.ui.sound.LocalSound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.gtranca.ui.AppNavHost
import com.gtranca.ui.theme.GTrancaTheme

class MainActivity : ComponentActivity() {
    private val sound = AndroidSoundPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cria os efeitos sonoros fora da thread principal, antes de o primeiro ser tocado.
        lifecycleScope.launch(Dispatchers.Default) { sound.warmUp() }
        // Todas as telas têm fundo verde escuro: ícones claros nas barras do sistema.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            GTrancaTheme {
                CompositionLocalProvider(LocalSound provides sound) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
