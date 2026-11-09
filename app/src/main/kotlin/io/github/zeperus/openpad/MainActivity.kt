package io.github.zeperus.openpad

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.zeperus.openpad.data.ExternalAccess
import io.github.zeperus.openpad.data.TreeExporter
import io.github.zeperus.openpad.ui.NotesScreen
import io.github.zeperus.openpad.ui.NotesViewModel
import io.github.zeperus.openpad.ui.editor.EditorDiagnostics
import io.github.zeperus.openpad.ui.theme.OpenPadTheme

class MainActivity : AppCompatActivity() {
    private var viewModel: NotesViewModel? = null
    private var pendingIntent: Intent? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OpenPadApplication
        // A document handed to us by another app ("Open with"); not re-handled when the screen is merely recreated.
        if (savedInstanceState == null) pendingIntent = intent
        if (BuildConfig.DIAGNOSTICS) {
            // real frame times (layout, composition, drawing) for the Settings diagnostics
            val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                EditorDiagnostics.recordFrame(frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION))
            }
            frameListener = listener
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }
        setContent {
            OpenPadTheme {
                val vm: NotesViewModel = viewModel(
                    factory = viewModelFactory { initializer { NotesViewModel(app.repository, app.sessionStore, app.settings, app.appScope, app.editorStates, app.languageManager, TreeExporter(app)) } },
                )
                viewModel = vm
                LaunchedEffect(vm) {
                    pendingIntent?.let { handleViewIntent(vm, it) }
                    pendingIntent = null
                }
                // Whatever the reason for leaving the screen, pending edits go to disk first.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
                // Back on screen: an external document another app changed meanwhile is shown anew (if nothing is unsaved).
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onAppResumed() }
                NotesScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        frameListener?.let { listener ->
            try {
                window.removeOnFrameMetricsAvailableListener(listener)
            } catch (e: IllegalArgumentException) {
                // not registered (anymore): nothing to remove
            }
        }
        frameListener = null
        super.onDestroy()
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
