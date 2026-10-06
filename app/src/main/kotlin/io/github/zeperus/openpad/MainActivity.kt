package io.github.zeperus.openpad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zeperus.openpad.data.ExternalAccess
import io.github.zeperus.openpad.ui.NotesScreen
import io.github.zeperus.openpad.ui.NotesViewModel
import io.github.zeperus.openpad.ui.theme.OpenPadTheme

class MainActivity : ComponentActivity() {
    private var viewModel: NotesViewModel? = null
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OpenPadApplication
        // A document handed to us by another app ("Open with"); not re-handled when the screen is merely recreated.
        if (savedInstanceState == null) pendingIntent = intent
        setContent {
            OpenPadTheme {
                val vm: NotesViewModel = viewModel(
                    factory = viewModelFactory { initializer { NotesViewModel(app.repository, app.sessionStore, app.settings, app.appScope, app.editorStates) } },
                )
                viewModel = vm
                LaunchedEffect(vm) {
                    pendingIntent?.let { handleViewIntent(vm, it) }
                    pendingIntent = null
                }
                // Whatever the reason for leaving the screen, pending edits go to disk first.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
                NotesScreen(vm)
            }
        }
    }

    // singleTask: a second "Open with" arrives here instead of creating another screen (and another session)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val vm = viewModel
        if (vm != null) handleViewIntent(vm, intent) else pendingIntent = intent
    }

    private fun handleViewIntent(vm: NotesViewModel, intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return
        val uri = intent.data ?: return
        if (uri.scheme != "content") return
        // Access handed over by "Open with" usually ends with the task; it is kept only if the sender allows it.
        val persistent = ExternalAccess.takePersistable(contentResolver, uri)
        vm.openExternal(uri.toString(), persistent)
        intent.data = null // handled
    }
}
