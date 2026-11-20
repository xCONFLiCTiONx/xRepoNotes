package cc.xconflictionx.reponotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.xconflictionx.reponotes.data.ApiException
import cc.xconflictionx.reponotes.data.Note
import cc.xconflictionx.reponotes.data.NotesApi
import cc.xconflictionx.reponotes.data.SecureSettings
import cc.xconflictionx.reponotes.files.FileTextDocument
import cc.xconflictionx.reponotes.files.TextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private var incomingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntent = intent
        setContent { RepoNotesApp(incomingIntent) { incomingIntent = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent = intent
    }
}

private enum class AppPage(val label: String) {
    NOTES("Notes"), FILE_EDITOR("File editor"), SETTINGS("Settings")
}

private val repoNotesColors = darkColorScheme(
    primary = Color(0xFFB7A4FF),
    onPrimary = Color(0xFF21163F),
    secondary = Color(0xFF9CC9C3),
    background = Color(0xFF101216),
    surface = Color(0xFF191D24),
    surfaceVariant = Color(0xFF242A33),
    onSurface = Color(0xFFE7E9EE),
    onSurfaceVariant = Color(0xFFB5BBC6),
    error = Color(0xFFFF8A80)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoNotesApp(incomingIntent: Intent?, consumeIntent: () -> Unit) {
    val context = LocalContext.current
    val secureSettings = remember { SecureSettings(context) }
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )

    var page by remember { mutableStateOf(AppPage.NOTES) }
    var serverUrl by remember { mutableStateOf(secureSettings.serverUrl) }
    var apiKey by remember { mutableStateOf(secureSettings.apiKey) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var repoPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var document by remember { mutableStateOf<TextDocument?>(null) }
    var fileText by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("Untitled.txt") }
    var pendingSharedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun api(): NotesApi? =
        if (serverUrl.isNotBlank() && apiKey.isNotBlank()) NotesApi(serverUrl, apiKey) else null

    suspend fun loadNotes() {
        val client = api() ?: return
        busy = true
        try {
            notes = client.getNotes()
            message = ""
        } catch (e: Exception) {
            message = e.message ?: "Could not load notes."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(serverUrl, apiKey, page) {
        if (page == AppPage.NOTES && serverUrl.isNotBlank() && apiKey.isNotBlank()) loadNotes()
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                val loaded = withContext(Dispatchers.IO) { FileTextDocument.read(context, uri) }
                document = loaded
                fileName = loaded.name
                fileText = loaded.text
                message = ""
                page = AppPage.FILE_EDITOR
            } catch (e: Exception) {
                message = e.message ?: "Unable to open this file."
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(fileText.toByteArray(StandardCharsets.UTF_8))
                    } ?: error("Android could not create the file.")
                }
                message = "Saved $fileName"
            } catch (e: Exception) {
                message = e.message ?: "Could not save the file."
            }
        }
    }

    LaunchedEffect(incomingIntent) {
        val intent = incomingIntent ?: return@LaunchedEffect
        val sharedUris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
            else -> emptyList()
        }
        pendingSharedUris = sharedUris.drop(1)
        val uri = sharedUris.firstOrNull()
        if (uri != null) {
            try {
                val loaded = withContext(Dispatchers.IO) { FileTextDocument.read(context, uri) }
                document = loaded
                fileName = loaded.name
                fileText = loaded.text
                page = AppPage.FILE_EDITOR
                message = ""
            } catch (e: Exception) {
                message = e.message ?: "Unable to open the shared file."
                page = AppPage.FILE_EDITOR
            }
        } else {
            val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!sharedText.isNullOrEmpty()) {
                document = null
                fileName = "Shared text.txt"
                fileText = sharedText
                page = AppPage.FILE_EDITOR
            }
        }
        consumeIntent()
    }

    MaterialTheme(colorScheme = repoNotesColors) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color(0xFF171A20),
                    modifier = Modifier.fillMaxWidth(0.82f)
                ) {
                    Text(
                        "RepoNotes",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
                    )
                    HorizontalDivider(color = Color(0xFF303640))
                    AppPage.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            selected = page == item,
                            onClick = {
                                page = item
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = Color(0xFF101216),
                topBar = {
                    TopAppBar(
                        title = { Text(page.label) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("☰", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)

                    when (page) {
                        AppPage.NOTES -> {
                            if (selectedNote == null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch { loadNotes() } }, enabled = !busy) {
                                        Text(if (busy) "Loading…" else "Refresh")
                                    }
                                    Button(onClick = {
                                        val client = api()
                                        if (client == null) {
                                            message = "Configure the server URL and API key in Settings first."
                                        } else scope.launch {
                                            busy = true
                                            try {
                                                val note = client.createNote("", "New note", "")
                                                notes = listOf(note) + notes
                                                selectedNote = note
                                                title = note.title
                                                body = note.body
                                                repoPath = note.repoPath
                                                message = ""
                                            } catch (e: Exception) {
                                                message = e.message ?: "Could not create note."
                                            } finally { busy = false }
                                        }
                                    }) { Text("＋ New note") }
                                }
                                if (notes.isEmpty() && !busy) {
                                    Text(
                                        if (api() == null) "Connect to your xServer database in Settings."
                                        else "No notes yet. Create your first note.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    label = { Text("Search notes") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                val visibleNotes = notes.filter {
                                    searchQuery.isBlank() ||
                                        it.title.contains(searchQuery, ignoreCase = true) ||
                                        it.body.contains(searchQuery, ignoreCase = true) ||
                                        it.repoPath.contains(searchQuery, ignoreCase = true)
                                }
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(visibleNotes, key = { it.id }) { note ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                selectedNote = note
                                                title = note.title
                                                body = note.body
                                                repoPath = note.repoPath
                                                message = ""
                                            },
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028)),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(note.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleMedium)
                                                Text(note.body.lineSequence().take(3).joinToString("\n"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    note.repoPath.substringAfterLast('/').ifBlank { "General" },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { selectedNote = null; message = "" }) { Text("← Notes") }
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            val current = selectedNote ?: return@Button
                                            val client = api() ?: return@Button
                                            scope.launch {
                                                busy = true
                                                try {
                                                    val saved = client.updateNote(current, title, body, repoPath)
                                                    notes = notes.map { if (it.id == saved.id) saved else it }
                                                    selectedNote = saved
                                                    message = "Saved"
                                                } catch (e: ApiException) {
                                                    message = if (e.status == 409) "This note changed on another device. Refresh before saving." else e.message ?: "Save failed."
                                                } catch (e: Exception) {
                                                    message = e.message ?: "Save failed."
                                                } finally { busy = false }
                                            }
                                        }
                                    ) { Text(if (busy) "Saving…" else "Save") }
                                }
                                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = repoPath, onValueChange = { repoPath = it }, label = { Text("Repository path (blank = General)") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(
                                    value = body,
                                    onValueChange = { body = it },
                                    label = { Text("Markdown note") },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    minLines = 8
                                )
                                Button(
                                    onClick = {
                                        val current = selectedNote ?: return@Button
                                        val client = api() ?: return@Button
                                        scope.launch {
                                            busy = true
                                            try {
                                                client.deleteNote(current)
                                                notes = notes.filterNot { it.id == current.id }
                                                selectedNote = null
                                                message = "Note deleted"
                                            } catch (e: Exception) {
                                                message = e.message ?: "Could not delete note."
                                            } finally { busy = false }
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("Delete note") }
                            }
                        }

                        AppPage.FILE_EDITOR -> {
                            if (pendingSharedUris.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${pendingSharedUris.size} more shared file(s)",
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Button(onClick = {
                                        val next = pendingSharedUris.first()
                                        scope.launch {
                                            try {
                                                val loaded = withContext(Dispatchers.IO) {
                                                    FileTextDocument.read(context, next)
                                                }
                                                document = loaded
                                                fileName = loaded.name
                                                fileText = loaded.text
                                                pendingSharedUris = pendingSharedUris.drop(1)
                                                message = ""
                                            } catch (e: Exception) {
                                                pendingSharedUris = pendingSharedUris.drop(1)
                                                message = e.message ?: "Unable to open the next shared file."
                                            }
                                        }
                                    }) { Text("Open next") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openDocument.launch(arrayOf("*/*")) }) { Text("Open file") }
                                Button(onClick = {
                                    val current = document
                                    if (current == null) {
                                        createDocument.launch(fileName.ifBlank { "Untitled.txt" })
                                    } else scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { FileTextDocument.write(context, current, fileText) }
                                            message = "Saved $fileName"
                                        } catch (e: Exception) {
                                            message = (e.message ?: "Save failed.") + " Use Save As to create a writable copy."
                                        }
                                    }
                                }) { Text("Save") }
                                Button(onClick = { createDocument.launch(fileName.ifBlank { "Untitled.txt" }) }) { Text("Save As") }
                            }
                            Text(fileName, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                            OutlinedTextField(
                                value = fileText,
                                onValueChange = { fileText = it },
                                label = { Text("Text file contents") },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                minLines = 12,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                "Text files are detected by their contents, not their extension. Database and binary files are rejected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AppPage.SETTINGS -> {
                            Text("Central xServer database", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("API base URL") },
                                placeholder = { Text("https://your-notes-api-hostname") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                            Text(
                                "The API key is encrypted with Android Keystore. When away, connect through your Zero Trust WARP profile and use the private server address if its route is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = {
                                secureSettings.serverUrl = serverUrl
                                secureSettings.apiKey = apiKey
                                message = "Settings saved"
                            }) { Text("Save settings") }
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        val client = api() ?: error("Enter the API URL and key first.")
                                        val loaded = client.getNotes()
                                        notes = loaded
                                        message = "Connected. ${loaded.size} notes found."
                                    } catch (e: Exception) {
                                        message = e.message ?: "Connection failed."
                                    }
                                }
                            }) { Text("Test connection") }
                        }
                    }
                }
            }
        }
    }
}
