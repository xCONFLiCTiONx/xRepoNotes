package com.xconflictionx.xreponotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xconflictionx.xreponotes.data.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.xconflictionx.xreponotes.util.NoteImageStore
import com.xconflictionx.xreponotes.ui.theme.cardColorForAccent
import com.xconflictionx.xreponotes.ui.theme.chipColorForAccent
import com.xconflictionx.xreponotes.ui.theme.projectAccentColor
import com.xconflictionx.xreponotes.ui.theme.projectCardColor
import com.xconflictionx.xreponotes.ui.theme.projectChipColor
import com.xconflictionx.xreponotes.ui.theme.workNoteAccentColor
import com.xconflictionx.xreponotes.ui.theme.workNoteHue
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Destination { NOTES, FILE_EDITOR, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextEditorScreen(viewModel: MainViewModel, onOpenClick: () -> Unit, onSaveAsClick: () -> Unit) {
    val context = LocalContext.current
    val document by viewModel.document.collectAsState()
    val documentError = document.errorMessage
    val textValue by viewModel.textFieldValue.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val searchVisible by viewModel.showSearch.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val showUnsaved by viewModel.showUnsavedDialog.collectAsState()
    val notebookRefreshVersion by viewModel.notebookRefreshVersion.collectAsState()
    val fileEditorOpenRequestVersion by viewModel.fileEditorOpenRequestVersion.collectAsState()
    val aiServerUrl by viewModel.aiServerUrl.collectAsState()
    val aiModel by viewModel.aiModel.collectAsState()
    val aiApiKey by viewModel.aiApiKey.collectAsState()
    val aiModels by viewModel.aiModels.collectAsState()
    val isFetchingAiModels by viewModel.isFetchingAiModels.collectAsState()
    val isTestingAiConnection by viewModel.isTestingAiConnection.collectAsState()
    val isFormattingAi by viewModel.isFormattingAi.collectAsState()
    val aiDialogInfo by viewModel.aiDialogInfo.collectAsState()
    val store = remember { NotebookStore(context) }
    val workStore = remember { WorkNotebookStore(context) }
    val vaultStore = remember { VaultNotebookStore(context) }
    val initialNotebooks = remember {
        val existing = store.load()
        val legacyWork = existing.firstOrNull { it.title.equals("Work Notebook", true) }
        val work = workStore.load(legacyWork)
        val vault = vaultStore.load()
        val personal = existing.filterNot {
            it.title.equals("Work Notebook", true) || it.title.equals(VaultNotebookStore.VAULT_TITLE, true)
        }
        if (legacyWork != null) store.save(personal)
        personal + work + vault
    }
    var notebooks by remember { mutableStateOf(initialNotebooks) }
    var notebookId by remember { mutableStateOf(notebooks.first().id) }
    var sectionId by remember { mutableStateOf(notebooks.first().sections.first().id) }
    var pageId by remember { mutableStateOf(notebooks.first().sections.first().pages.first().id) }
    var destination by remember { mutableStateOf(Destination.NOTES) }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerScope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var pageContent by remember { mutableStateOf("") }
    var previewMode by remember { mutableStateOf(true) }
    var noteFilter by remember { mutableStateOf("All Notes") }
    var selectedProjectSectionId by remember { mutableStateOf<String?>(null) }
    var noteOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<NotePage?>(null) }
    val preferences = remember { context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE) }
    val legacyPreferences = remember { context.getSharedPreferences("xtexteditor_prefs", Context.MODE_PRIVATE) }
    var fontSize by remember { mutableFloatStateOf(preferences.getFloat("editor_font_size", legacyPreferences.getFloat("editor_font_size", 16f))) }
    var themeChoice by remember { mutableStateOf(preferences.getString("theme", legacyPreferences.getString("theme", "Dark")) ?: "Dark") }
    var serverUrlDraft by remember { mutableStateOf(serverUrl) }
    var apiKeyDraft by remember { mutableStateOf(apiKey) }
    var aiUrlDraft by remember { mutableStateOf(aiServerUrl) }
    var aiModelDraft by remember { mutableStateOf(aiModel) }
    var aiKeyDraft by remember { mutableStateOf(aiApiKey) }
    var aiKeyVisible by remember { mutableStateOf(false) }
    var aiModelMenuExpanded by remember { mutableStateOf(false) }
    var undoAiState by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(serverUrl) { serverUrlDraft = serverUrl }
    LaunchedEffect(apiKey) { apiKeyDraft = apiKey }
    LaunchedEffect(aiServerUrl) { aiUrlDraft = aiServerUrl }
    LaunchedEffect(aiModel) { aiModelDraft = aiModel }
    LaunchedEffect(aiApiKey) { aiKeyDraft = aiApiKey }
    LaunchedEffect(destination) {
        if (destination == Destination.SETTINGS && aiModels.isEmpty() && (aiServerUrl.isNotBlank() || aiApiKey.isNotBlank())) {
            viewModel.fetchAiModels(aiServerUrl, aiApiKey, silent = true)
        }
    }

    val notebook = notebooks.firstOrNull { it.id == notebookId } ?: notebooks.first()
    val section = notebook.sections.firstOrNull { it.id == sectionId } ?: notebook.sections.first()
    val page = section.pages.firstOrNull { it.id == pageId } ?: section.pages.first()
    fun persistNotebooks(all: List<Notebook>) {
        store.save(all.filterNot {
            it.title.equals("Work Notebook", true) || it.title.equals(VaultNotebookStore.VAULT_TITLE, true)
        })
        all.firstOrNull { it.title.equals("Work Notebook", true) }?.let(workStore::save)
        all.firstOrNull { it.title.equals(VaultNotebookStore.VAULT_TITLE, true) }?.let(vaultStore::save)
    }
    fun mutate(transform: (List<Notebook>) -> List<Notebook>) { notebooks = transform(notebooks) }
    fun savePage(title: String = pageTitle, body: String = pageContent) {
        pageTitle = title
        pageContent = body
        mutate { all -> all.map { nb -> if (nb.id != notebook.id) nb else nb.copy(sections = nb.sections.map { sec -> if (sec.id != section.id) sec else sec.copy(pages = sec.pages.map { p -> if (p.id == page.id) p.copy(title = title, content = body, updatedAt = System.currentTimeMillis(), isDirty = true) else p }) }) } }
        viewModel.scheduleAutoSync(context)
    }
    LaunchedEffect(page.id) { pageTitle = page.title; pageContent = page.content }
    LaunchedEffect(fileEditorOpenRequestVersion) {
        if (fileEditorOpenRequestVersion > 0) {
            destination = Destination.FILE_EDITOR
            noteOpen = false
            drawerOpen = false
        }
    }
    LaunchedEffect(notebookRefreshVersion) {
        if (notebookRefreshVersion > 0) {
            val currentNotebookTitle = notebook.title
            val currentSectionRepoPath = section.repoPath
            val refreshed = store.load() + workStore.load() + vaultStore.load()
            notebooks = refreshed
            val selectedNotebook = refreshed.firstOrNull { it.id == notebookId }
                ?: refreshed.firstOrNull { it.title.equals(currentNotebookTitle, ignoreCase = true) }
                ?: refreshed.first()
            val selectedSection = selectedNotebook.sections.firstOrNull { it.id == sectionId }
                ?: selectedNotebook.sections.firstOrNull { it.repoPath.equals(currentSectionRepoPath, ignoreCase = true) }
                ?: selectedNotebook.sections.first()
            val selectedPage = selectedSection.pages.firstOrNull { it.id == pageId } ?: selectedSection.pages.first()
            notebookId = selectedNotebook.id
            sectionId = selectedSection.id
            pageId = selectedPage.id
            pageTitle = selectedPage.title
            pageContent = selectedPage.content
        }
    }
    LaunchedEffect(notebooks) {
        delay(400)
        persistNotebooks(notebooks)
    }
    LaunchedEffect(fontSize) {
        preferences.edit().putFloat("editor_font_size", fontSize).apply()
        legacyPreferences.edit().putFloat("editor_font_size", fontSize).apply()
    }
    LaunchedEffect(status) { status?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.clearStatusMessage() } }
    fun syncNow() {
        persistNotebooks(notebooks)
        viewModel.triggerManualSync(context)
    }

    BackHandler(enabled = noteOpen || drawerOpen || destination != Destination.NOTES || dialog.isNotBlank()) {
        when {
            dialog.isNotBlank() -> dialog = ""
            drawerOpen -> drawerOpen = false
            noteOpen -> { noteOpen = false; previewMode = true }
            destination != Destination.NOTES -> destination = Destination.NOTES
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            savePage(body = pageContent + "\n\n[Attachment](" + uri.toString() + ")")
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    fun closeDrawer() {
        drawerOpen = false
        drawerScope.launch { drawerState.close() }
    }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) drawerOpen = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("xRepoNotes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Your notes and files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    NavigationDrawerItem(
                        label = { Text("Personal Notebook") },
                        selected = destination == Destination.NOTES &&
                            !notebook.title.equals("Work Notebook", true) &&
                            !notebook.title.equals(VaultNotebookStore.VAULT_TITLE, true),
                        onClick = {
                            destination = Destination.NOTES
                            val personal = notebooks.first {
                                !it.title.equals("Work Notebook", true) && !it.title.equals(VaultNotebookStore.VAULT_TITLE, true)
                            }
                            noteFilter = "All Notes"
                            noteOpen = false
                            notebookId = personal.id
                            sectionId = personal.sections.first().id
                            pageId = personal.sections.first().pages.first().id
                            closeDrawer()
                        },
                        icon = { Icon(Icons.Default.Book, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Work Notebook") },
                        selected = destination == Destination.NOTES && notebook.title.equals("Work Notebook", true),
                        onClick = {
                            destination = Destination.NOTES
                            val work = notebooks.first { it.title.equals("Work Notebook", true) }
                            noteFilter = "All Notes"
                            noteOpen = false
                            notebookId = work.id
                            sectionId = work.sections.first().id
                            pageId = work.sections.first().pages.first().id
                            closeDrawer()
                        },
                        icon = { Icon(Icons.Default.Work, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Vault") },
                        selected = destination == Destination.NOTES && notebook.title.equals(VaultNotebookStore.VAULT_TITLE, true),
                        onClick = {
                            destination = Destination.NOTES
                            val vault = notebooks.first { it.title.equals(VaultNotebookStore.VAULT_TITLE, true) }
                            noteFilter = "All Notes"
                            noteOpen = false
                            notebookId = vault.id
                            sectionId = vault.sections.first().id
                            pageId = vault.sections.first().pages.first().id
                            closeDrawer()
                        },
                        icon = { Icon(Icons.Default.Lock, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("File Editor") },
                        selected = destination == Destination.FILE_EDITOR,
                        onClick = {
                            destination = Destination.FILE_EDITOR
                            noteOpen = false
                            closeDrawer()
                        },
                        icon = { Icon(Icons.Default.Description, null) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Open File…") },
                        selected = false,
                        onClick = {
                            closeDrawer()
                            onOpenClick()
                        },
                        icon = { Icon(Icons.Default.FolderOpen, null) }
                    )
                    NavigationDrawerItem(label = { Text("Settings") }, selected = destination == Destination.SETTINGS, onClick = { destination = Destination.SETTINGS; closeDrawer() }, icon = { Icon(Icons.Default.Settings, null) })
                }
            }
        }
    ) {
        Scaffold(topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { drawerOpen = true }) { Icon(Icons.Default.Menu, "Open menu") } },
                title = {
                    Text(
                        when (destination) {
                            Destination.NOTES -> "Notes"
                            Destination.FILE_EDITOR -> "File Editor"
                            Destination.SETTINGS -> "Settings"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = { syncNow() }, enabled = !syncing) {
                        if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, "Sync notes")
                    }
                    when (destination) {
                        Destination.NOTES -> {
                            if (noteOpen) {
                                val inVault = notebook.title.equals(VaultNotebookStore.VAULT_TITLE, true)
                                IconButton(onClick = {
                                    val movedPage = page.copy(updatedAt = System.currentTimeMillis(), isDirty = true)
                                    val targetNotebookTitle = when {
                                        inVault && isVaultWorkRepoPath(section.repoPath) -> "Work Notebook"
                                        inVault -> "Personal Notebook"
                                        else -> VaultNotebookStore.VAULT_TITLE
                                    }
                                    val targetSectionMatch: (NoteSection) -> Boolean = when {
                                        inVault && isVaultWorkRepoPath(section.repoPath) -> { _ -> true }
                                        inVault -> { sec -> sec.repoPath.isBlank() }
                                        notebook.title.equals("Work Notebook", true) -> { sec -> isVaultWorkRepoPath(sec.repoPath) }
                                        else -> { sec -> isVaultPersonalRepoPath(sec.repoPath) }
                                    }
                                    val updated = notebooks.map { nb ->
                                        when {
                                            nb.id == notebook.id -> nb.copy(sections = nb.sections.map { sec ->
                                                if (sec.id != section.id) sec
                                                else sec.copy(pages = sec.pages.filterNot { it.id == movedPage.id }.ifEmpty { listOf(NotePage()) })
                                            })
                                            nb.title.equals(targetNotebookTitle, true) -> {
                                                val targetSec = nb.sections.firstOrNull(targetSectionMatch) ?: nb.sections.first()
                                                nb.copy(sections = nb.sections.map { sec ->
                                                    if (sec.id != targetSec.id) sec
                                                    else sec.copy(pages = sec.pages.filterNot {
                                                        (it.title == "New page" || it.title == "New note") && it.content.isBlank() && it.pcId == 0L
                                                    } + movedPage)
                                                })
                                            }
                                            else -> nb
                                        }
                                    }
                                    notebooks = updated
                                    persistNotebooks(updated)
                                    viewModel.scheduleAutoSync(context)
                                    noteOpen = false
                                    previewMode = true
                                    Toast.makeText(
                                        context,
                                        if (inVault) "Restored from Vault" else "Moved to Vault",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Icon(
                                        if (inVault) Icons.Default.LockOpen else Icons.Default.Lock,
                                        if (inVault) "Restore from Vault" else "Move to Vault"
                                    )
                                }
                                IconButton(onClick = { deleteTarget = page }) {
                                    Icon(Icons.Default.Delete, "Delete note", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            IconButton(onClick = { dialog = "search" }) { Icon(Icons.Default.Search, "Search notes") }
                        }
                        Destination.FILE_EDITOR -> {
                            IconButton(
                                onClick = {
                                    val sent = textValue.text
                                    viewModel.formatWithAi(sent) { formatted ->
                                        if (viewModel.textFieldValue.value.text == sent) {
                                            viewModel.onTextFieldValueChanged(TextFieldValue(formatted))
                                            Toast.makeText(context, "Formatted with AI", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Document changed while AI was working; result discarded.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isFormattingAi
                            ) {
                                if (isFormattingAi) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.AutoAwesome, "Format with AI")
                            }
                            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) { Text("↶") }
                            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) { Text("↷") }
                            IconButton(onClick = { viewModel.toggleSearch() }) { Icon(Icons.Default.Search, "Find") }
                            TextButton(onClick = { viewModel.saveDocument(context, onSaveAsClick) }) { Text("Save") }
                        }
                        Destination.SETTINGS -> Unit
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                when (destination) {
                    Destination.NOTES -> {
                        var sectionMenu by remember { mutableStateOf(false) }
                        val isWorkNotebook = notebook.title.equals("Work Notebook", true)
                        val isVaultNotebook = notebook.title.equals(VaultNotebookStore.VAULT_TITLE, true)
                        val matches = remember(notebook, search) {
                            if (search.isBlank()) emptyList()
                            else notebook.sections.flatMap { sec -> sec.pages.map { Triple(notebook, sec, it) } }
                                .filter { it.third.title.contains(search, true) || it.third.content.contains(search, true) }
                        }
                        val selectedProjectSection = remember(notebook, selectedProjectSectionId) {
                            selectedProjectSectionId?.let { id ->
                                notebook.sections.firstOrNull { it.id == id && it.repoPath.isNotBlank() }
                            }
                        }
                        val dashboardCards = notebook.sections.flatMap { sec ->
                            sec.pages.filter { it.title != "New page" || it.content.isNotBlank() }.map { sec to it }
                        }.filter { item ->
                            when {
                                isWorkNotebook -> true
                                isVaultNotebook -> when (noteFilter) {
                                    "Personal" -> isVaultPersonalRepoPath(item.first.repoPath)
                                    "Work" -> isVaultWorkRepoPath(item.first.repoPath)
                                    else -> true
                                }
                                else -> when (noteFilter) {
                                    "General" -> item.first.repoPath.isBlank()
                                    "Project" -> item.first.repoPath.isNotBlank() &&
                                        (selectedProjectSection == null || item.first.id == selectedProjectSection.id)
                                    else -> true
                                }
                            }
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .imePadding()
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (noteOpen) {
                                    TextButton(onClick = { noteOpen = false; previewMode = true }) {
                                        Icon(Icons.Default.ArrowBack, null)
                                        Text("Notes")
                                    }
                                } else {
                                    Text(notebook.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = {
                                        if (!isWorkNotebook && !isVaultNotebook && noteFilter == "Project" && selectedProjectSection == null) {
                                            dialog = "no_project"
                                            return@TextButton
                                        }
                                        val targetSection = when {
                                            isWorkNotebook -> section
                                            isVaultNotebook && noteFilter == "Work" ->
                                                notebook.sections.firstOrNull { isVaultWorkRepoPath(it.repoPath) } ?: notebook.sections.last()
                                            isVaultNotebook ->
                                                notebook.sections.firstOrNull { isVaultPersonalRepoPath(it.repoPath) } ?: notebook.sections.first()
                                            noteFilter == "Project" && selectedProjectSection != null -> selectedProjectSection
                                            else -> notebook.sections.firstOrNull { it.repoPath.isBlank() } ?: notebook.sections.first()
                                        }
                                        val p = when {
                                            isWorkNotebook -> {
                                                val id = nextWorkNoteId(targetSection.pages)
                                                NotePage(id = id, clientId = "work:$id", title = "New note")
                                            }
                                            isVaultNotebook -> {
                                                val id = UUID.randomUUID().toString()
                                                NotePage(id = id, clientId = "vault:$id", title = "New note")
                                            }
                                            else -> NotePage(title = "New note")
                                        }
                                        mutate { all -> all.map { n -> if (n.id == notebook.id) n.copy(sections = n.sections.map { sec -> if (sec.id == targetSection.id) sec.copy(pages = sec.pages + p) else sec }) else n } }
                                        sectionId = targetSection.id
                                        pageId = p.id
                                        pageTitle = p.title
                                        pageContent = ""
                                        previewMode = false
                                        noteOpen = true
                                    }) {
                                        Icon(Icons.Default.Add, null)
                                        Text("New note")
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                            }
                            if (!noteOpen && !isWorkNotebook && !isVaultNotebook && noteFilter == "Project") {
                                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    OutlinedButton(
                                        onClick = { sectionMenu = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            selectedProjectSection?.title ?: "All Projects",
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = selectedProjectSection?.let { projectAccentColor(it.repoPath) }
                                                ?: MaterialTheme.colorScheme.onSurface
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose project")
                                    }
                                    DropdownMenu(sectionMenu, { sectionMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("All Projects", maxLines = 1) },
                                            onClick = {
                                                sectionMenu = false
                                                selectedProjectSectionId = null
                                            }
                                        )
                                        notebook.sections.filter { it.repoPath.isNotBlank() }.forEach { projectSection ->
                                            DropdownMenuItem(
                                                text = { Text(projectSection.title, maxLines = 1, color = projectAccentColor(projectSection.repoPath)) },
                                                onClick = {
                                                    sectionMenu = false
                                                    selectedProjectSectionId = projectSection.id
                                                    sectionId = projectSection.id
                                                    projectSection.pages.firstOrNull()?.let { pageId = it.id }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            if (!noteOpen && search.isBlank()) {
                                if (isVaultNotebook) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                        listOf("All Notes", "Personal", "Work").forEach { filter ->
                                            FilterChip(
                                                selected = noteFilter == filter,
                                                onClick = {
                                                    noteFilter = filter
                                                    val target = when (filter) {
                                                        "Personal" -> notebook.sections.firstOrNull { isVaultPersonalRepoPath(it.repoPath) }
                                                        "Work" -> notebook.sections.firstOrNull { isVaultWorkRepoPath(it.repoPath) }
                                                        else -> null
                                                    }
                                                    if (target != null) {
                                                        sectionId = target.id
                                                        target.pages.firstOrNull()?.let { pageId = it.id }
                                                    }
                                                },
                                                label = { Text(filter) }
                                            )
                                        }
                                    }
                                } else if (!isWorkNotebook) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                        listOf("All Notes", "General", "Project").forEach { filter ->
                                            FilterChip(
                                                selected = noteFilter == filter,
                                                onClick = {
                                                    noteFilter = filter
                                                    if (filter == "Project") {
                                                        selectedProjectSectionId = null
                                                    } else if (filter == "General") {
                                                        notebook.sections.firstOrNull { it.repoPath.isBlank() }?.let { target ->
                                                            sectionId = target.id
                                                            target.pages.firstOrNull()?.let { pageId = it.id }
                                                        }
                                                    }
                                                },
                                                label = { Text(filter) }
                                            )
                                        }
                                    }
                                }
                                if (dashboardCards.isEmpty()) {
                                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                        Text(
                                            when {
                                                isVaultNotebook -> "No notes in Vault"
                                                !isWorkNotebook && noteFilter == "Project" -> "No project notes yet"
                                                else -> "No notes yet"
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        columns = GridCells.Adaptive(minSize = 145.dp),
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        gridItems(dashboardCards, key = { it.second.id }) { item ->
                                            val accent = if (isWorkNotebook) {
                                                workNoteAccentColor(item.second.clientId.ifBlank { item.second.id })
                                            } else {
                                                projectAccentColor(item.first.repoPath)
                                            }
                                            val cardColor = if (isWorkNotebook) cardColorForAccent(accent) else projectCardColor(item.first.repoPath)
                                            val chipColor = if (isWorkNotebook) chipColorForAccent(accent) else projectChipColor(item.first.repoPath)
                                            Card(
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).combinedClickable(
                                                    onClick = {
                                                        sectionId = item.first.id
                                                        pageId = item.second.id
                                                        noteOpen = true
                                                        previewMode = true
                                                    },
                                                    onLongClick = {
                                                        val rendered = renderMarkdownAsPlainText(item.second.title, item.second.content)
                                                        copyToClipboard(context, item.second.title.ifBlank { "Note" }, rendered)
                                                        Toast.makeText(context, "Copied rendered note", Toast.LENGTH_SHORT).show()
                                                    }
                                                ),
                                                colors = CardDefaults.cardColors(containerColor = cardColor),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Column {
                                                    Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
                                                    Column(Modifier.padding(10.dp)) {
                                                        Text(item.second.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                                        Spacer(Modifier.height(5.dp))
                                                        Text(
                                                            NoteImageStore.replaceImageMarkdownWithPlaceholder(item.second.content),
                                                            maxLines = 3,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Spacer(Modifier.height(4.dp))
                                                        Surface(color = chipColor, shape = RoundedCornerShape(50)) {
                                                            Text(item.first.title, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (!noteOpen && search.isNotBlank()) {
                                Text(matches.size.toString() + " matching notes", color = MaterialTheme.colorScheme.primary)
                                LazyColumn(Modifier.weight(1f)) {
                                    items(matches, key = { it.third.id }) { m ->
                                        ListItem(
                                            headlineContent = { Text(m.third.title) },
                                            supportingContent = { Text(m.second.title) },
                                            modifier = Modifier.combinedClickable(
                                                onClick = { sectionId = m.second.id; pageId = m.third.id; noteOpen = true; previewMode = true },
                                                onLongClick = {
                                                    val rendered = renderMarkdownAsPlainText(m.third.title, m.third.content)
                                                    copyToClipboard(context, m.third.title.ifBlank { "Note" }, rendered)
                                                    Toast.makeText(context, "Copied rendered note", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                            if (noteOpen) {
                                val noteAccent = if (isWorkNotebook) {
                                    workNoteAccentColor(page.clientId.ifBlank { page.id })
                                } else {
                                    projectAccentColor(section.repoPath)
                                }
                                Box(Modifier.fillMaxWidth().height(3.dp).background(noteAccent, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.height(6.dp))
                                Text(section.title, style = MaterialTheme.typography.titleSmall, color = noteAccent)
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = pageTitle,
                                    onValueChange = { savePage(title = it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    readOnly = previewMode,
                                    placeholder = { Text("Note title") },
                                    textStyle = MaterialTheme.typography.headlineSmall,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                                )
                                Divider()
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    if (previewMode) {
                                        MarkdownPreview(
                                            markdown = pageContent,
                                            serverUrl = serverUrl,
                                            apiKey = apiKey,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .combinedClickable(
                                                    onClick = {},
                                                    onLongClick = {
                                                        val rendered = renderMarkdownAsPlainText(pageTitle, pageContent)
                                                        copyToClipboard(context, pageTitle.ifBlank { "Note" }, rendered)
                                                        Toast.makeText(context, "Copied rendered note", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                        )
                                    } else {
                                        val editImageIds = remember(pageContent) { NoteImageStore.extractImageIds(pageContent) }
                                        Column(
                                            Modifier
                                                .fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                                .imePadding()
                                        ) {
                                            BasicTextField(
                                                value = pageContent,
                                                onValueChange = { savePage(body = it) },
                                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 160.dp),
                                                textStyle = TextStyle(
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    fontSize = fontSize.sp,
                                                    lineHeight = (fontSize * 1.55f).sp
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                                decorationBox = { inner ->
                                                    if (pageContent.isEmpty()) Text("Start writing… Markdown supports headings, lists, checklists, tables, code and links.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    inner()
                                                }
                                            )
                                            if (editImageIds.isNotEmpty()) {
                                                Spacer(Modifier.height(12.dp))
                                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    editImageIds.forEach { imageId ->
                                                        NoteImageView(
                                                            imageId = imageId,
                                                            serverUrl = serverUrl,
                                                            apiKey = apiKey,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (isFormattingAi) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                tonalElevation = 6.dp,
                                                color = MaterialTheme.colorScheme.surface
                                            ) {
                                                Column(
                                                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                                ) {
                                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                                    Text("Formatting with AI…", style = MaterialTheme.typography.bodyMedium)
                                                    OutlinedButton(onClick = { viewModel.cancelAiFormatting() }) {
                                                        Text("Cancel")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (undoAiState?.first == page.id) {
                                        TextButton(
                                            onClick = {
                                                val previous = undoAiState?.second ?: return@TextButton
                                                savePage(body = previous)
                                                undoAiState = null
                                                Toast.makeText(context, "AI formatting undone", Toast.LENGTH_SHORT).show()
                                            },
                                            enabled = !isFormattingAi
                                        ) {
                                            Icon(Icons.Default.Undo, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Undo AI")
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            val targetPageId = page.id
                                            val sentBody = pageContent
                                            viewModel.formatWithAi(sentBody) { formatted ->
                                                if (page.id != targetPageId || pageContent != sentBody) {
                                                    Toast.makeText(
                                                        context,
                                                        "The note changed while AI was working, so the result was discarded.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    undoAiState = targetPageId to sentBody
                                                    savePage(body = formatted)
                                                    Toast.makeText(context, "Formatted with AI", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        enabled = !isFormattingAi
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Format with AI")
                                    }
                                    if (!previewMode) {
                                        TextButton(
                                            onClick = {
                                                drawerScope.launch {
                                                    val imageId = NoteImageStore.saveClipboardImage(context)
                                                    if (imageId == null) {
                                                        Toast.makeText(context, "No image in clipboard", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val prefix = if (pageContent.isBlank() || pageContent.endsWith("\n")) "" else "\n"
                                                        savePage(body = "$pageContent$prefix![image](xreponotes-image://$imageId)\n")
                                                        val uploaded = NoteImageStore.uploadImage(context, imageId, serverUrl, apiKey)
                                                        Toast.makeText(
                                                            context,
                                                            if (uploaded) "Image pasted" else "Image saved locally (upload pending)",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Image, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Paste image")
                                        }
                                        TextButton(
                                            onClick = {
                                                val fullText = if (pageTitle.isNotBlank() && pageContent.isNotBlank()) {
                                                    "$pageTitle\n\n$pageContent"
                                                } else {
                                                    pageContent.ifBlank { pageTitle }
                                                }
                                                copyToClipboard(context, pageTitle.ifBlank { "Note" }, fullText)
                                                Toast.makeText(context, "Copied note with Markdown", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, null)
                                            Spacer(Modifier.width(4.dp))
                                            Text("Copy all")
                                        }
                                    }
                                    TextButton(onClick = { previewMode = !previewMode }) {
                                        Icon(if (previewMode) Icons.Default.Edit else Icons.Default.Check, null)
                                        Text(if (previewMode) "Edit" else "Done")
                                    }
                                }
                            }
                        }
                    }
                    Destination.FILE_EDITOR -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .imePadding()
                                .padding(12.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = onOpenClick) { Text("Open") }
                                OutlinedButton(onClick = viewModel::requestNewDocument) { Text("New") }
                                OutlinedButton(onClick = onSaveAsClick) { Text("Save As") }
                            }
                            if (searchVisible) Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    searchQuery,
                                    viewModel::updateSearchQuery,
                                    Modifier.weight(1f),
                                    label = { Text("Find in document") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                                )
                                TextButton(onClick = viewModel::toggleSearch) { Text("Close") }
                            }
                            documentError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                            if (document.isBinaryWarning) Text("Warning: this file appears to contain binary data. Editing is allowed, but saving over the original is disabled.", color = MaterialTheme.colorScheme.error)
                            Text(
                                document.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text(
                                buildString {
                                    if (document.isModified) append("Unsaved changes • ")
                                    append("${document.lineCount} lines • ${document.wordCount} words • ${document.formattedSize}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                            )
                            BoxWithConstraints(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                val editorMinHeight = maxHeight
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    BasicTextField(
                                        value = textValue,
                                        onValueChange = viewModel::onTextFieldValueChanged,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = editorMinHeight),
                                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 21.sp),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                        decorationBox = { innerTextField ->
                                            if (textValue.text.isEmpty()) {
                                                Text(
                                                    "Start typing…",
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = fontSize.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Destination.SETTINGS -> {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .imePadding()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("xRepoNotes server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                serverUrlDraft,
                                { serverUrlDraft = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("Server URL") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                apiKeyDraft,
                                { apiKeyDraft = it },
                                Modifier.fillMaxWidth(),
                                label = { Text("xRepoNotes API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Text(
                                "The API key is encrypted with Android Keystore on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.updateServerUrl(context, serverUrlDraft) }) {
                                    Text("Save URL")
                                }
                                OutlinedButton(onClick = { viewModel.updateApiKey(context, apiKeyDraft) }) {
                                    Text("Save key")
                                }
                                TextButton(onClick = {
                                    apiKeyDraft = ""
                                    viewModel.updateApiKey(context, "")
                                }) {
                                    Text("Clear key")
                                }
                            }
                            Button(
                                onClick = {
                                    persistNotebooks(notebooks)
                                    viewModel.updateServerUrl(context, serverUrlDraft)
                                    viewModel.updateApiKey(context, apiKeyDraft)
                                    viewModel.triggerManualSync(context)
                                },
                                enabled = !syncing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sync, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (syncing) "Syncing…" else "Sync now")
                            }
                            Divider()
                            Text("AI Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Configure a local/custom Ollama or OpenAI-compatible server, or leave Server URL blank to use Google Gemini cloud.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = aiUrlDraft,
                                onValueChange = { aiUrlDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("AI Server URL (Optional)") },
                                placeholder = { Text("e.g. https://ollama.home.xconflictionx.cc") },
                                singleLine = true
                            )
                            Box(Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = aiModelDraft,
                                    onValueChange = { aiModelDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("AI Model Name") },
                                    placeholder = { Text("e.g. llama3:8b or gemini-2.5-flash") },
                                    singleLine = true,
                                    trailingIcon = if (aiModels.isNotEmpty()) {
                                        {
                                            IconButton(onClick = { aiModelMenuExpanded = !aiModelMenuExpanded }) {
                                                Icon(Icons.Default.ArrowDropDown, "Choose model")
                                            }
                                        }
                                    } else null
                                )
                                DropdownMenu(
                                    expanded = aiModelMenuExpanded,
                                    onDismissRequest = { aiModelMenuExpanded = false }
                                ) {
                                    aiModels.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m) },
                                            onClick = {
                                                aiModelDraft = m
                                                aiModelMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = aiKeyDraft,
                                onValueChange = { aiKeyDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("AI API Key") },
                                singleLine = true,
                                visualTransformation = if (aiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { aiKeyVisible = !aiKeyVisible }) {
                                        Icon(
                                            if (aiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (aiKeyVisible) "Hide key" else "Show key"
                                        )
                                    }
                                }
                            )
                            Text(
                                when {
                                    aiKeyDraft.trim() != aiApiKey.trim() -> "Unsaved API key changes — tap Save AI settings to store encrypted."
                                    aiApiKey.isNotBlank() -> "AI API key is saved encrypted with Android Keystore."
                                    else -> "Required for Gemini cloud or authenticated AI servers. Stored encrypted."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.fetchAiModels(aiUrlDraft, aiKeyDraft, silent = false) },
                                    enabled = !isFetchingAiModels && !isTestingAiConnection,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isFetchingAiModels) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Fetching…")
                                    } else {
                                        Text("Fetch models")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { viewModel.testAiConnection(aiUrlDraft, aiKeyDraft, aiModelDraft) },
                                    enabled = !isTestingAiConnection && !isFetchingAiModels,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isTestingAiConnection) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Testing…")
                                    } else {
                                        Text("Test connection")
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { viewModel.saveAiConfig(context, aiUrlDraft, aiModelDraft, aiKeyDraft) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save AI settings")
                                }
                                TextButton(
                                    onClick = {
                                        aiKeyDraft = ""
                                        viewModel.clearAiKey(context)
                                    }
                                ) {
                                    Text("Clear key")
                                }
                            }
                            Divider()
                            Text("Font size: " + fontSize.toInt() + " sp")
                            Slider(fontSize, { fontSize = it }, valueRange = 12f..24f)
                            Divider()
                            Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Dark", "Light", "System").forEach { choice ->
                                    FilterChip(
                                        selected = themeChoice == choice,
                                        onClick = {
                                            themeChoice = choice
                                            preferences.edit().putString("theme", choice).apply()
                                            legacyPreferences.edit().putString("theme", choice).apply()
                                        },
                                        label = { Text(choice) }
                                    )
                                }
                            }
                            Divider()
                            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("xRepoNotes — personal and work notebooks with Markdown notes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Version 1.0", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = { Text("“${target.title.ifBlank { "Untitled" }}” will be deleted from xRepoNotes and removed from this device after sync.") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = notebooks.map { nb ->
                        nb.copy(sections = nb.sections.map { sec ->
                            val remainingPages = sec.pages.filterNot { it.id == target.id }
                            sec.copy(pages = remainingPages.ifEmpty { listOf(NotePage()) })
                        })
                    }
                    notebooks = updated
                    persistNotebooks(updated)
                    viewModel.deleteNotebookNote(context, target)
                    noteOpen = false
                    previewMode = true
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    when (dialog) {
        "search" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Search notes") },
            text = {
                OutlinedTextField(
                    search,
                    { search = it },
                    placeholder = { Text("Page title or content") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Done") } }
        )
        "link" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Insert link") },
            text = {
                Column {
                    OutlinedTextField(
                        linkText,
                        { linkText = it },
                        label = { Text("Link text") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    )
                    OutlinedTextField(
                        linkUrl,
                        { linkUrl = it },
                        label = { Text("URL") }
                    )
                }
            },
            confirmButton = { TextButton(onClick = { savePage(body = pageContent + "\n[" + linkText.ifBlank { linkUrl } + "](" + linkUrl + ")"); linkText = ""; linkUrl = ""; dialog = "" }) { Text("Insert") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
        )
        "no_project" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("No project selected") },
            text = { Text("Please select a project from the dropdown before creating a new project note.") },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("OK") } }
        )
    }

    if (aiDialogInfo != null) {
        val info = aiDialogInfo!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissAiDialog() },
            title = { Text(info.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(info.message)
                }
            },
            confirmButton = {
                if (info.offerOpenSettings) {
                    TextButton(onClick = {
                        viewModel.dismissAiDialog()
                        noteOpen = false
                        destination = Destination.SETTINGS
                    }) { Text("Open settings") }
                } else {
                    TextButton(onClick = { viewModel.dismissAiDialog() }) { Text("OK") }
                }
            },
            dismissButton = if (info.offerOpenSettings) {
                { TextButton(onClick = { viewModel.dismissAiDialog() }) { Text("Cancel") } }
            } else null
        )
    }

    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnsavedDialog() },
            title = { Text("Unsaved changes") },
            text = { Text("The current document has unsaved changes. Save them before continuing?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUnsavedDialog()
                    viewModel.saveDocument(context, onSaveAsClick)
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.confirmPendingAction(context) }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { viewModel.dismissUnsavedDialog() }) { Text("Cancel") }
                }
            }
        )
    }

}

@Composable
private fun MarkdownPreview(
    markdown: String,
    serverUrl: String,
    apiKey: String,
    modifier: Modifier = Modifier
) {
    val imageLineRegex = remember { Regex("^!\\[[^\\]]*]\\(xreponotes-image://([a-zA-Z0-9_-]+)\\)$") }
    var inCodeBlock = false
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEach { line ->
            val imageMatch = if (!inCodeBlock) imageLineRegex.matchEntire(line.trim()) else null
            when {
                line.trim().startsWith("```") -> inCodeBlock = !inCodeBlock
                inCodeBlock -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(line, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                imageMatch != null -> NoteImageView(
                    imageId = imageMatch.groupValues[1],
                    serverUrl = serverUrl,
                    apiKey = apiKey,
                    modifier = Modifier.fillMaxWidth()
                )
                line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                line.startsWith("### ") -> Text(line.removePrefix("### "), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = !line.startsWith("- [ ] "), onCheckedChange = null)
                    Text(inlineMarkdown(line.drop(6)))
                }
                line.startsWith("- ") -> Row(verticalAlignment = Alignment.Top) {
                    Text("•", modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMarkdown(line.drop(2)))
                }
                line.matches(Regex("^\\d+\\.\\s.*")) -> Text(inlineMarkdown(line))
                line.startsWith("|") && line.indexOf("|", startIndex = 1) >= 0 && !line.contains("---") -> {
                    val cells = line.trim().trim('|').split("|").map { it.trim() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cells.forEach { cell ->
                            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                Text(cell, Modifier.padding(7.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun inlineMarkdown(source: String): AnnotatedString {
    val pattern = Regex("(\\*\\*.+?\\*\\*|\\*.+?\\*|`.+?`)")
    return buildAnnotatedString {
        var cursor = 0
        pattern.findAll(source).forEach { match ->
            append(source.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removePrefix("**").removeSuffix("**")) }
                token.startsWith("*") -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.removePrefix("*").removeSuffix("*")) }
                else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialThemeColorCode)) { append(token.removePrefix("`").removeSuffix("`")) }
            }
            cursor = match.range.last + 1
        }
        append(source.substring(cursor))
    }
}

private val MaterialThemeColorCode = androidx.compose.ui.graphics.Color(0x33222222)

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun nextWorkNoteId(existingPages: List<NotePage>): String {
    val recentHues = existingPages
        .filter { it.title != "New page" || it.content.isNotBlank() }
        .takeLast(6)
        .map { workNoteHue(it.clientId.ifBlank { it.id }) }
    if (recentHues.isEmpty()) return UUID.randomUUID().toString()

    var bestId = UUID.randomUUID().toString()
    var bestScore = -1.0
    repeat(24) {
        val candidate = UUID.randomUUID().toString()
        val candidateHue = workNoteHue("work:$candidate")
        val minDistance = recentHues.minOf { existingHue ->
            val diff = abs(candidateHue - existingHue)
            minOf(diff, 360.0 - diff)
        }
        if (minDistance > bestScore) {
            bestScore = minDistance
            bestId = candidate
        }
        if (minDistance >= 90.0) return candidate
    }
    return bestId
}

private fun renderMarkdownAsPlainText(title: String, markdown: String): String {
    var inCodeBlock = false
    val renderedLines = mutableListOf<String>()
    markdown.lines().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> inCodeBlock = !inCodeBlock
            inCodeBlock -> renderedLines += line
            line.startsWith("# ") -> renderedLines += stripInlineMarkdown(line.removePrefix("# "))
            line.startsWith("## ") -> renderedLines += stripInlineMarkdown(line.removePrefix("## "))
            line.startsWith("### ") -> renderedLines += stripInlineMarkdown(line.removePrefix("### "))
            line.startsWith("#### ") -> renderedLines += stripInlineMarkdown(line.removePrefix("#### "))
            line.startsWith("- [ ] ") -> renderedLines += "☐ " + stripInlineMarkdown(line.drop(6))
            line.startsWith("- [x] ") || line.startsWith("- [X] ") -> renderedLines += "☑ " + stripInlineMarkdown(line.drop(6))
            line.startsWith("- ") -> renderedLines += "• " + stripInlineMarkdown(line.drop(2))
            line.startsWith("|") && line.indexOf("|", startIndex = 1) >= 0 -> {
                if (!line.contains("---")) {
                    val cells = trimmed.trim('|').split("|").map { stripInlineMarkdown(it.trim()) }
                    renderedLines += cells.joinToString("   ")
                }
            }
            else -> renderedLines += stripInlineMarkdown(line)
        }
    }
    val renderedBody = renderedLines.joinToString("\n").trim()
    val cleanTitle = title.trim()
    return when {
        cleanTitle.isNotBlank() && renderedBody.isNotBlank() -> "$cleanTitle\n\n$renderedBody"
        cleanTitle.isNotBlank() -> cleanTitle
        else -> renderedBody
    }
}

@Composable
private fun NoteImageView(
    imageId: String,
    serverUrl: String,
    apiKey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(imageId, serverUrl, apiKey) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(imageId, serverUrl, apiKey) { mutableStateOf(true) }

    LaunchedEffect(imageId, serverUrl, apiKey) {
        loading = true
        bitmap = NoteImageStore.loadImageBitmap(context, imageId, serverUrl, apiKey)
        loading = false
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        val loaded = bitmap
        when {
            loaded != null -> Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = "Note image",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
            )
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            else -> Text(
                "[Image unavailable]",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun stripInlineMarkdown(source: String): String {
    val withImagePlaceholders = NoteImageStore.replaceImageMarkdownWithPlaceholder(source)
    val withLinks = withImagePlaceholders.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    return withLinks.replace(Regex("(\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`)")) { match ->
        match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: match.value
    }
}
