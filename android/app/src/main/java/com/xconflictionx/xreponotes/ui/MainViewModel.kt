package com.xconflictionx.xreponotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xconflictionx.xreponotes.data.NotesDatabaseHelper
import com.xconflictionx.xreponotes.data.NotePage
import com.xconflictionx.xreponotes.data.RepoNote
import com.xconflictionx.xreponotes.data.TextDocument
import com.xconflictionx.xreponotes.util.AiException
import com.xconflictionx.xreponotes.util.AiFormatter
import com.xconflictionx.xreponotes.util.ApiKeyStore
import com.xconflictionx.xreponotes.util.FileHelper
import com.xconflictionx.xreponotes.util.NotesSyncEngine
import com.xconflictionx.xreponotes.util.NotesSyncScheduler
import com.xconflictionx.xreponotes.util.NotebookSyncEngine
import com.xconflictionx.xreponotes.util.SyncResult
import com.xconflictionx.xreponotes.util.UndoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PendingAction {
    data object NewDocument : PendingAction
    data class OpenUri(val uri: Uri) : PendingAction
    data class OpenNote(val note: RepoNote) : PendingAction
    data class OpenSharedText(val text: String) : PendingAction
}

data class AiDialogInfo(
    val title: String,
    val message: String,
    val offerOpenSettings: Boolean = false
)

class MainViewModel : ViewModel() {

    private val undoManager = UndoManager()

    private val _document = MutableStateFlow(TextDocument())
    val document: StateFlow<TextDocument> = _document.asStateFlow()

    private val _textFieldValue = MutableStateFlow(TextFieldValue(""))
    val textFieldValue: StateFlow<TextFieldValue> = _textFieldValue.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _wordWrap = MutableStateFlow(true)
    val wordWrap: StateFlow<Boolean> = _wordWrap.asStateFlow()

    private val _showLineNumbers = MutableStateFlow(true)
    val showLineNumbers: StateFlow<Boolean> = _showLineNumbers.asStateFlow()

    private val _fontSizeSp = MutableStateFlow(14f)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    private val _showFileInfoDialog = MutableStateFlow(false)
    val showFileInfoDialog: StateFlow<Boolean> = _showFileInfoDialog.asStateFlow()

    private val _showUnsavedDialog = MutableStateFlow(false)
    val showUnsavedDialog: StateFlow<Boolean> = _showUnsavedDialog.asStateFlow()

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _showSearch = MutableStateFlow(false)
    val showSearch: StateFlow<Boolean> = _showSearch.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // --- xRepoNotes State ---
    private val _repoNotes = MutableStateFlow<List<RepoNote>>(emptyList())
    val repoNotes: StateFlow<List<RepoNote>> = _repoNotes.asStateFlow()

    private val _activeRepoNote = MutableStateFlow<RepoNote?>(null)
    val activeRepoNote: StateFlow<RepoNote?> = _activeRepoNote.asStateFlow()

    private val _showNotesDrawer = MutableStateFlow(false)
    val showNotesDrawer: StateFlow<Boolean> = _showNotesDrawer.asStateFlow()

    private val _showSyncSettings = MutableStateFlow(false)
    val showSyncSettings: StateFlow<Boolean> = _showSyncSettings.asStateFlow()

    private val _serverUrl = MutableStateFlow(NotesSyncEngine.DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _notebookRefreshVersion = MutableStateFlow(0)
    val notebookRefreshVersion: StateFlow<Int> = _notebookRefreshVersion.asStateFlow()

    private val _fileEditorOpenRequestVersion = MutableStateFlow(0)
    val fileEditorOpenRequestVersion: StateFlow<Int> = _fileEditorOpenRequestVersion.asStateFlow()

    // --- AI Formatting State ---
    private val _aiServerUrl = MutableStateFlow("")
    val aiServerUrl: StateFlow<String> = _aiServerUrl.asStateFlow()

    private val _aiModel = MutableStateFlow("")
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()

    private val _aiApiKey = MutableStateFlow("")
    val aiApiKey: StateFlow<String> = _aiApiKey.asStateFlow()

    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    val aiModels: StateFlow<List<String>> = _aiModels.asStateFlow()

    private val _isFetchingAiModels = MutableStateFlow(false)
    val isFetchingAiModels: StateFlow<Boolean> = _isFetchingAiModels.asStateFlow()

    private val _isTestingAiConnection = MutableStateFlow(false)
    val isTestingAiConnection: StateFlow<Boolean> = _isTestingAiConnection.asStateFlow()

    private val _isFormattingAi = MutableStateFlow(false)
    val isFormattingAi: StateFlow<Boolean> = _isFormattingAi.asStateFlow()

    private val _aiDialogInfo = MutableStateFlow<AiDialogInfo?>(null)
    val aiDialogInfo: StateFlow<AiDialogInfo?> = _aiDialogInfo.asStateFlow()

    private var aiFormatJob: Job? = null

    private fun loadSyncConfig(context: Context) {
        val prefs = context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("xtexteditor_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(
            NotesSyncEngine.PREF_KEY_SERVER_URL,
            legacyPrefs.getString(NotesSyncEngine.PREF_KEY_SERVER_URL, NotesSyncEngine.DEFAULT_SERVER_URL)
        )
        if (savedUrl != null) {
            _serverUrl.value = savedUrl
        }
        _apiKey.value = ApiKeyStore.read(context).orEmpty()
        _aiServerUrl.value = prefs.getString(AiFormatter.PREF_KEY_AI_URL, "").orEmpty()
        _aiModel.value = prefs.getString(AiFormatter.PREF_KEY_AI_MODEL, "").orEmpty()
        _aiApiKey.value = ApiKeyStore.readAiKey(context).orEmpty()
    }

    fun loadNotesOnLaunch(context: Context) {
        viewModelScope.launch {
            loadSyncConfig(context)

            refreshLocalNotes(context)

            // Auto sync with xRepoNotes
            syncNotesInternal(context, silent = true)
        }
    }

    fun refreshLocalNotes(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = NotesDatabaseHelper(context)
            val notes = dbHelper.getAllNotes()
            _repoNotes.value = notes
        }
    }

    fun openRepoNote(context: Context, note: RepoNote) {
        if (_document.value.isModified) {
            _pendingAction.value = PendingAction.OpenNote(note)
            _showUnsavedDialog.value = true
        } else {
            openRepoNoteInternal(note)
        }
    }

    private fun openRepoNoteInternal(note: RepoNote) {
        _activeRepoNote.value = note
        setDocumentContent(
            TextDocument(
                fileName = note.displayTitle + ".md",
                content = note.body,
                initialContent = note.body,
                fileSize = note.body.toByteArray(Charsets.UTF_8).size.toLong()
            )
        )
        _statusMessage.value = "Opened note: ${note.displayTitle}"
    }

    fun createNewRepoNote(context: Context, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = NotesDatabaseHelper(context)
            val newNote = RepoNote(
                title = title.ifBlank { "New Note" },
                body = "",
                isDirty = true
            )
            val newId = dbHelper.insertNote(newNote)
            val created = newNote.copy(id = newId)
            refreshLocalNotes(context)
            withContext(Dispatchers.Main) {
                openRepoNoteInternal(created)
            }
        }
    }

    fun saveCurrentRepoNote(context: Context) {
        val currentNote = _activeRepoNote.value ?: return
        val updatedContent = _document.value.content
        val updatedNote = currentNote.copy(
            body = updatedContent,
            updatedUtc = RepoNote.getCurrentIsoUtcString(),
            isDirty = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = NotesDatabaseHelper(context)
            dbHelper.updateNote(updatedNote)
            _activeRepoNote.value = updatedNote
            _document.value = _document.value.copy(initialContent = updatedContent)
            refreshLocalNotes(context)
            withContext(Dispatchers.Main) {
                _statusMessage.value = "Saved note locally: ${updatedNote.displayTitle}"
            }
            // Background sync note
            syncNotesInternal(context, silent = true)
        }
    }

    fun deleteRepoNote(context: Context, note: RepoNote) {
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = NotesDatabaseHelper(context)
            dbHelper.deleteNote(note.id)
            if (_activeRepoNote.value?.id == note.id) {
                _activeRepoNote.value = null
                setDocumentContent(TextDocument())
            }
            refreshLocalNotes(context)
            withContext(Dispatchers.Main) {
                _statusMessage.value = "Deleted note: ${note.displayTitle}"
            }
            syncNotesInternal(context, silent = true)
        }
    }

    fun deleteNotebookNote(context: Context, page: NotePage) {
        viewModelScope.launch(Dispatchers.IO) {
            val database = NotesDatabaseHelper(context)
            val match = database.getAllNotes(includeDeleted = true).firstOrNull { note ->
                (page.localDbId > 0L && note.id == page.localDbId) ||
                    (page.pcId > 0L && note.pcId == page.pcId) ||
                    (page.clientId.isNotBlank() && note.clientId == page.clientId)
            }
            if (match != null) {
                if (!match.isDeleted) database.deleteNote(match.id)
            } else {
                val now = RepoNote.getCurrentIsoUtcString()
                database.insertNote(
                    RepoNote(
                        pcId = page.pcId,
                        clientId = page.clientId,
                        title = page.title,
                        body = page.content,
                        createdUtc = now,
                        updatedUtc = now,
                        lastSyncedBody = page.lastSyncedContent,
                        isDirty = page.isDirty,
                        isDeleted = true
                    )
                )
            }
            withContext(Dispatchers.Main) {
                _statusMessage.value = "Deleted note: ${page.title.ifBlank { "Untitled" }}"
            }
            syncNotesInternal(context, silent = true)
        }
    }

    private var autoSyncJob: Job? = null
    private var pendingSyncAfterEdit: Boolean = false

    fun setEditingNote(context: Context, editing: Boolean) {
        NotebookSyncEngine.isEditingNote = editing
        if (editing) {
            autoSyncJob?.cancel()
        } else if (pendingSyncAfterEdit) {
            pendingSyncAfterEdit = false
            scheduleAutoSync(context, delayMs = 300L)
        }
    }

    fun scheduleAutoSync(context: Context, delayMs: Long = 1200L) {
        if (NotebookSyncEngine.isEditingNote) {
            pendingSyncAfterEdit = true
            autoSyncJob?.cancel()
            return
        }
        val appContext = context.applicationContext
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(delayMs)
            if (NotebookSyncEngine.isEditingNote) {
                pendingSyncAfterEdit = true
                return@launch
            }
            syncNotesInternal(appContext, silent = true)
        }
    }

    fun triggerManualSync(context: Context) {
        viewModelScope.launch {
            syncNotesInternal(context, silent = false, force = true)
        }
    }

    private suspend fun syncNotesInternal(context: Context, silent: Boolean, force: Boolean = false) {
        if (!force && NotebookSyncEngine.isEditingNote) {
            pendingSyncAfterEdit = true
            return
        }
        _isSyncing.value = true
        if (!silent) _isLoading.value = true

        val result = NotebookSyncEngine.sync(context, _serverUrl.value, _apiKey.value, force = force)
        _isSyncing.value = false
        if (!silent) _isLoading.value = false

        when (result) {
            is SyncResult.Deferred -> {
                pendingSyncAfterEdit = true
            }
            is SyncResult.Success -> {
                if (result.hasChanges) {
                    _notebookRefreshVersion.value += 1
                    refreshLocalNotes(context)
                    val currentActive = _activeRepoNote.value
                    if (currentActive != null) {
                        val dbHelper = NotesDatabaseHelper(context)
                        val updated = dbHelper.getNoteById(currentActive.id)
                        if (updated != null && updated.body != currentActive.body) {
                            _activeRepoNote.value = updated
                            _document.value = _document.value.copy(
                                content = updated.body,
                                initialContent = updated.body
                            )
                            _textFieldValue.value = TextFieldValue(updated.body)
                        }
                    }
                }
                if (!silent) {
                    _statusMessage.value = "Sync Successful: ${result.message}"
                }
            }
            is SyncResult.Failure -> {
                NotesSyncScheduler.enqueueImmediate(context)
                if (!silent) {
                    _statusMessage.value = "Sync queued for retry: ${result.errorMessage}"
                }
            }
        }
    }

    fun updateServerUrl(context: Context, newUrl: String) {
        val trimmed = newUrl.trim()
        _serverUrl.value = trimmed
        val prefs = context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(NotesSyncEngine.PREF_KEY_SERVER_URL, trimmed).apply()
        _statusMessage.value = "xRepoNotes server URL saved"
        NotesSyncScheduler.enqueueImmediate(context)
    }

    fun updateApiKey(context: Context, newApiKey: String) {
        val trimmed = newApiKey.trim()
        if (trimmed.isEmpty()) {
            ApiKeyStore.clear(context)
            _apiKey.value = ""
            _statusMessage.value = "xRepoNotes API key cleared"
            return
        }
        if (!ApiKeyStore.save(context, trimmed)) {
            _statusMessage.value = "Could not securely save the xRepoNotes API key"
            return
        }
        _apiKey.value = trimmed
        _statusMessage.value = "xRepoNotes API key saved securely"
        NotesSyncScheduler.enqueueImmediate(context)
    }

    fun saveAiConfig(context: Context, serverUrl: String, model: String, apiKey: String) {
        val cleanUrl = serverUrl.trim()
        val cleanModel = model.trim()
        val cleanKey = apiKey.trim()

        val prefs = context.getSharedPreferences("xreponotes_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(AiFormatter.PREF_KEY_AI_URL, cleanUrl)
            .putString(AiFormatter.PREF_KEY_AI_MODEL, cleanModel)
            .apply()
        _aiServerUrl.value = cleanUrl
        _aiModel.value = cleanModel

        if (cleanKey.isEmpty()) {
            ApiKeyStore.clearAiKey(context)
            _aiApiKey.value = ""
        } else {
            if (!ApiKeyStore.saveAiKey(context, cleanKey)) {
                _statusMessage.value = "Could not securely save the AI API key"
                return
            }
            _aiApiKey.value = cleanKey
        }
        _statusMessage.value = "AI settings saved"
    }

    fun clearAiKey(context: Context) {
        ApiKeyStore.clearAiKey(context)
        _aiApiKey.value = ""
        _statusMessage.value = "AI API key cleared"
    }

    fun fetchAiModels(serverUrl: String, apiKey: String, silent: Boolean = false) {
        val cleanUrl = serverUrl.trim()
        val cleanKey = apiKey.trim()
        if (cleanUrl.isEmpty() && cleanKey.isEmpty()) {
            if (!silent) {
                _aiDialogInfo.value = AiDialogInfo(
                    title = "No Models Found",
                    message = "Enter an AI Server URL or API key first."
                )
            }
            return
        }
        viewModelScope.launch {
            _isFetchingAiModels.value = true
            try {
                val models = AiFormatter.fetchModels(cleanUrl, cleanKey)
                _aiModels.value = models
                if (models.isNotEmpty() && _aiModel.value.isBlank()) {
                    _aiModel.value = models.first()
                }
                if (!silent) {
                    if (models.isNotEmpty()) {
                        _aiDialogInfo.value = AiDialogInfo(
                            title = "Models Found",
                            message = "Successfully retrieved ${models.size} model(s):\n\n" +
                                models.joinToString("\n") { " • $it" }
                        )
                    } else {
                        _aiDialogInfo.value = AiDialogInfo(
                            title = "No Models Found",
                            message = "Could not retrieve models list from the server. Check your Server URL or API key."
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ex: Exception) {
                if (!silent) {
                    _aiDialogInfo.value = AiDialogInfo(
                        title = "Error Fetching Models",
                        message = ex.message ?: "Unknown error"
                    )
                }
            } finally {
                _isFetchingAiModels.value = false
            }
        }
    }

    fun testAiConnection(serverUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            _isTestingAiConnection.value = true
            try {
                val result = AiFormatter.testConnection(serverUrl, apiKey, model)
                if (result.models.isNotEmpty()) {
                    _aiModels.value = result.models
                }
                _aiDialogInfo.value = AiDialogInfo(
                    title = if (result.success) "Connection Successful" else "Connection Failed",
                    message = result.message
                )
            } catch (e: CancellationException) {
                throw e
            } catch (ex: Exception) {
                _aiDialogInfo.value = AiDialogInfo(
                    title = "Test Connection Error",
                    message = ex.message ?: "Unknown error"
                )
            } finally {
                _isTestingAiConnection.value = false
            }
        }
    }

    fun formatWithAi(text: String, onFormatted: (String) -> Unit) {
        if (_isFormattingAi.value) return
        if (text.isBlank()) {
            _aiDialogInfo.value = AiDialogInfo(
                title = "Nothing to format",
                message = "Add some text to the note first."
            )
            return
        }

        val url = _aiServerUrl.value.trim()
        val model = _aiModel.value.trim()
        val key = _aiApiKey.value.trim()

        if (url.isEmpty() && key.isEmpty()) {
            _aiDialogInfo.value = AiDialogInfo(
                title = "AI Configuration Needed",
                message = "To format notes with AI, please configure your AI server URL or API key in Settings.",
                offerOpenSettings = true
            )
            return
        }

        if (url.isNotEmpty() && model.isEmpty()) {
            _aiDialogInfo.value = AiDialogInfo(
                title = "AI Model Name Needed",
                message = "Please specify the AI model name in Settings (e.g. fast-agent:3b).",
                offerOpenSettings = true
            )
            return
        }

        aiFormatJob?.cancel()
        aiFormatJob = viewModelScope.launch {
            _isFormattingAi.value = true
            try {
                val formatted = AiFormatter.format(url, key, model, text)
                onFormatted(formatted)
            } catch (_: CancellationException) {
                // User cancelled
            } catch (ex: AiException) {
                _aiDialogInfo.value = AiDialogInfo(
                    title = "AI formatting didn't apply",
                    message = ex.message ?: "Formatting failed."
                )
            } catch (ex: Exception) {
                _aiDialogInfo.value = AiDialogInfo(
                    title = "AI formatting failed",
                    message = ex.message ?: "Unknown error."
                )
            } finally {
                _isFormattingAi.value = false
            }
        }
    }

    fun cancelAiFormatting() {
        aiFormatJob?.cancel()
        aiFormatJob = null
        _isFormattingAi.value = false
    }

    fun dismissAiDialog() {
        _aiDialogInfo.value = null
    }

    fun setNotesDrawerVisible(visible: Boolean) {
        _showNotesDrawer.value = visible
    }

    fun setSyncSettingsVisible(visible: Boolean) {
        _showSyncSettings.value = visible
    }

    fun handleIntent(context: Context, intent: Intent) {
        loadSyncConfig(context)
        val action = intent.action
        val streamUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val clipUri = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT) {
            val uri = intent.data ?: streamUri ?: clipUri
            if (uri != null) {
                requestOpenDocument(uri, context)
            }
        } else if (action == Intent.ACTION_SEND) {
            val uri = streamUri ?: clipUri ?: intent.data
            if (uri != null) {
                requestOpenDocument(uri, context)
            } else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                requestOpenSharedText(text)
            }
        }
    }

    fun onTextFieldValueChanged(newValue: TextFieldValue) {
        val oldValue = _textFieldValue.value
        undoManager.onFieldValueChanged(newValue, oldValue)
        _textFieldValue.value = newValue
        _document.value = _document.value.copy(content = newValue.text)
        updateUndoRedoStates()
    }

    fun undo() {
        val previous = undoManager.undo(_textFieldValue.value)
        if (previous != null) {
            _textFieldValue.value = previous
            _document.value = _document.value.copy(content = previous.text)
            updateUndoRedoStates()
        }
    }

    fun redo() {
        val next = undoManager.redo(_textFieldValue.value)
        if (next != null) {
            _textFieldValue.value = next
            _document.value = _document.value.copy(content = next.text)
            updateUndoRedoStates()
        }
    }

    private fun updateUndoRedoStates() {
        _canUndo.value = undoManager.canUndo
        _canRedo.value = undoManager.canRedo
    }

    fun requestNewDocument() {
        if (_document.value.isModified) {
            _pendingAction.value = PendingAction.NewDocument
            _showUnsavedDialog.value = true
        } else {
            createNewDocumentInternal()
        }
    }

    fun requestOpenDocument(uri: Uri, context: Context) {
        if (_document.value.isModified) {
            _pendingAction.value = PendingAction.OpenUri(uri)
            _showUnsavedDialog.value = true
        } else {
            openUriInternal(context, uri)
        }
    }

    fun requestOpenSharedText(text: String) {
        if (_document.value.isModified) {
            _pendingAction.value = PendingAction.OpenSharedText(text)
            _showUnsavedDialog.value = true
        } else {
            openSharedTextInternal(text)
        }
    }

    private fun openSharedTextInternal(text: String) {
        _activeRepoNote.value = null
        _fileEditorOpenRequestVersion.value += 1
        setDocumentContent(
            TextDocument(
                fileName = "Shared_Text.txt",
                content = text,
                initialContent = text,
                fileSize = text.toByteArray(Charsets.UTF_8).size.toLong()
            )
        )
        _statusMessage.value = "Loaded shared text"
    }

    fun confirmPendingAction(context: Context) {
        _showUnsavedDialog.value = false
        when (val action = _pendingAction.value) {
            is PendingAction.NewDocument -> createNewDocumentInternal()
            is PendingAction.OpenUri -> openUriInternal(context, action.uri)
            is PendingAction.OpenNote -> openRepoNoteInternal(action.note)
            is PendingAction.OpenSharedText -> openSharedTextInternal(action.text)
            null -> {}
        }
        _pendingAction.value = null
    }

    fun dismissUnsavedDialog() {
        _showUnsavedDialog.value = false
        _pendingAction.value = null
    }

    private fun createNewDocumentInternal() {
        _activeRepoNote.value = null
        _fileEditorOpenRequestVersion.value += 1
        setDocumentContent(TextDocument())
        _statusMessage.value = "Created new document"
    }

    private fun openUriInternal(context: Context, uri: Uri) {
        _fileEditorOpenRequestVersion.value += 1
        viewModelScope.launch {
            _activeRepoNote.value = null
            _isLoading.value = true
            val loadedDoc = withContext(Dispatchers.IO) {
                FileHelper.readDocumentFromUri(context, uri)
            }
            setDocumentContent(loadedDoc)
            _isLoading.value = false
            if (loadedDoc.errorMessage != null) {
                _statusMessage.value = loadedDoc.errorMessage
            } else {
                _statusMessage.value = "Opened ${loadedDoc.fileName}"
            }
        }
    }

    private fun setDocumentContent(doc: TextDocument) {
        _document.value = doc
        _textFieldValue.value = TextFieldValue(text = doc.content)
        undoManager.reset()
        updateUndoRedoStates()
    }

    fun saveDocument(context: Context, onRequestSaveAs: () -> Unit) {
        if (_activeRepoNote.value != null) {
            saveCurrentRepoNote(context)
            return
        }

        val currentDoc = _document.value
        val uri = currentDoc.uri

        if (uri == null || currentDoc.isReadOnly || currentDoc.isBinaryWarning) {
            _statusMessage.value = "This file cannot be safely overwritten. Select a save location..."
            onRequestSaveAs()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val success = withContext(Dispatchers.IO) {
                FileHelper.saveDocumentToUri(context, uri, currentDoc.content, currentDoc.initialContent)
            }
            _isLoading.value = false
            if (success) {
                _document.value = currentDoc.copy(initialContent = currentDoc.content)
                _statusMessage.value = "Saved ${currentDoc.fileName}"
            } else {
                _statusMessage.value = "The original file changed or does not permit safe saving. Choose 'Save As'..."
                onRequestSaveAs()
            }
        }
    }

    fun saveDocumentAs(context: Context, newUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            val currentDoc = _document.value
            val success = withContext(Dispatchers.IO) {
                FileHelper.saveDocumentToUri(context, newUri, currentDoc.content)
            }
            _isLoading.value = false
            if (success) {
                val updatedDoc = withContext(Dispatchers.IO) {
                    FileHelper.readDocumentFromUri(context, newUri).copy(
                        content = currentDoc.content,
                        initialContent = currentDoc.content
                    )
                }
                setDocumentContent(updatedDoc)
                _statusMessage.value = "Saved as ${updatedDoc.fileName}"
            } else {
                _statusMessage.value = "Failed to save file to selected location."
            }
        }
    }

    fun toggleWordWrap() {
        _wordWrap.value = !_wordWrap.value
    }

    fun toggleLineNumbers() {
        _showLineNumbers.value = !_showLineNumbers.value
    }

    fun changeFontSize(delta: Float) {
        val newSize = (_fontSizeSp.value + delta).coerceIn(10f, 32f)
        _fontSizeSp.value = newSize
    }

    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) {
            _searchQuery.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFileInfoDialogVisible(visible: Boolean) {
        _showFileInfoDialog.value = visible
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
