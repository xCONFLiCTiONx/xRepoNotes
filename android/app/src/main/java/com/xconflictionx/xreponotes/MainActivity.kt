package com.xconflictionx.xreponotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.xconflictionx.xreponotes.ui.MainViewModel
import com.xconflictionx.xreponotes.ui.TextEditorScreen
import com.xconflictionx.xreponotes.ui.theme.XRepoNotesTheme
import com.xconflictionx.xreponotes.util.NotesSyncScheduler

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            viewModel.requestOpenDocument(it, this)
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            viewModel.saveDocumentAs(this, it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotesSyncScheduler.schedule(this)
        NotesSyncScheduler.enqueueImmediate(this)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            XRepoNotesTheme {
                TextEditorScreen(
                    viewModel = viewModel,
                    onOpenClick = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onSaveAsClick = {
                        val currentName = viewModel.document.value.fileName
                        createDocumentLauncher.launch(currentName)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        if (uri != null && uri.scheme == "content") {
            try {
                val takeFlags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags != 0) {
                    @Suppress("WrongConstant")
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (uri != null || intent.action == Intent.ACTION_SEND) {
            viewModel.handleIntent(this, intent)
        } else {
            // Opened directly from app launcher / apps menu
            viewModel.loadNotesOnLaunch(this)
        }
    }
}
