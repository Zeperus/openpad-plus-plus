package io.github.zeperus.openpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zeperus.openpad.ui.NotesScreen
import io.github.zeperus.openpad.ui.NotesViewModel
import io.github.zeperus.openpad.ui.theme.OpenPadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OpenPadApplication
        setContent {
            OpenPadTheme {
                val vm: NotesViewModel = viewModel(
                    factory = viewModelFactory { initializer { NotesViewModel(app.repository, app.appScope) } },
                )
                // Whatever the reason for leaving the screen, pending edits go to disk first.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
                NotesScreen(vm)
            }
        }
    }
}
