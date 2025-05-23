package whispers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import whispers.ui.main.MainScreenEntryPoint
import whispers.ui.main.MainScreenViewModel
import whispers.ui.theme.WhisperCppDemoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhisperCppDemoTheme {
                MainScreenEntryPoint(viewModel)
            }
        }
    }
}