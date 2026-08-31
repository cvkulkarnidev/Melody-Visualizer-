package dev.cvkulkarnidev.melodyvisualizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.cvkulkarnidev.melodyvisualizer.ui.MelodyVisualizerApp
import dev.cvkulkarnidev.melodyvisualizer.ui.theme.MelodyVisualizerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MelodyVisualizerTheme {
                MelodyVisualizerApp(viewModel = viewModel)
            }
        }
    }
}
