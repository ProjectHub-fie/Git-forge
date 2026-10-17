package com.projecthub.gitforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.AppResult
import com.projecthub.gitforge.core.PathSafety
import com.projecthub.gitforge.core.appResult
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.workspace.FileContent
import com.projecthub.gitforge.workspace.FileNode
import com.projecthub.gitforge.workspace.LineEnding
import com.projecthub.gitforge.workspace.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** An open editor tab. Holds the text in memory so switching tabs preserves edits. */
data class EditorTab(
    val relativePath: String,
    val displayName: String,
    val language: Language,
    val text: String,
    val savedText: String,
    val lineEnding: LineEnding,
    val charsetName: String,
    val lastModifiedOnDisk: Long,
    val onDiskSizeBytes: Long = -1L,
    val readOnly: Boolean = false,
    val truncated: Boolean = false,
    val loadError: String? = null,
) {
    val isDirty: Boolean get() = text != savedText
    val lineCount: Int get() = text.count { it == '\n' } + 1
}

/** Languages with highlighting rules. [extensions] drives detection. */
enum class Language(
    val displayName: String,
    val extensions: Set<String>,
    val keywords: Set<String>,
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
) {
    KOTLIN(
        "Kotlin", setOf("kt", "kts"),
        setOf(
            "abstract", "actual", "as", "break", "by", "catch", "class", "companion", "const",
            "constructor", "continue", "crossinline", "data", "do", "else", "enum", "expect",
            "external", "false", "final", "finally", "for", "fun", "get", "if", "import", "in",
            "init", "inline", "interface", "internal", "is", "lateinit", "null", "object", "open",
            "operator", "out", "override", "package", "private", "protected", "public", "return",
            "sealed", "set", "super", "suspend", "this", "throw", "true", "try", "typealias",
            "val", "var", "vararg", "when", "while",
        ),
        "//", "/*" to "*/",
    ),
    JAVA(
        "Java", setOf("java"),
        setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
        ),
        "//", "/*" to "*/",
    ),
    JAVASCRIPT(
        "JavaScript", setOf("js", "mjs", "cjs", "jsx"),
        setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
            "function", "if", "import", "in", "instanceof", "let", "new", "null", "return",
            "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined",
            "var", "void", "while", "with", "yield",
        ),
        "//", "/*" to "*/",
    ),
    TYPESCRIPT(
        "TypeScript", setOf("ts", "tsx"),
        setOf(
            "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch",
            "class", "const", "continue", "declare", "default", "do", "else", "enum", "export",
            "extends", "false", "finally", "for", "from", "function", "if", "implements",
            "import", "in", "interface", "keyof", "let", "namespace", "new", "null", "number",
            "private", "protected", "public", "readonly", "return", "static", "string", "super",
            "switch", "this", "throw", "true", "try", "type", "typeof", "undefined", "var",
            "void", "while", "yield",
        ),
        "//", "/*" to "*/",
    ),
    PYTHON(
        "Python", setOf("py", "pyw"),
        setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "True", "try", "while", "with", "yield",
        ),
        "#", null,
    ),
    HTML(
        "HTML", setOf("html", "htm", "xml", "svg"),
        setOf("html", "head", "body", "div", "span", "script", "style", "title", "meta", "link"),
        null, "<!--" to "-->",
    ),
    CSS("CSS", setOf("css", "scss", "less"), emptySet(), null, "/*" to "*/"),
    JSON("JSON", setOf("json"), setOf("true", "false", "null"), null, null),
    YAML("YAML", setOf("yml", "yaml"), setOf("true", "false", "null"), "#", null),
    MARKDOWN("Markdown", setOf("md", "markdown"), emptySet(), null, null),
    SQL(
        "SQL", setOf("sql"),
        setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "drop", "alter", "join", "left", "right", "inner", "outer", "on",
            "group", "by", "order", "having", "limit", "offset", "and", "or", "not", "null",
            "primary", "key", "foreign", "references", "index", "as", "distinct", "count", "sum",
        ),
        "--", "/*" to "*/",
    ),
    C(
        "C", setOf("c", "h"),
        setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
            "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
            "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
            "switch", "typedef", "union", "unsigned", "void", "volatile", "while",
        ),
        "//", "/*" to "*/",
    ),
    CPP(
        "C++", setOf("cpp", "cc", "cxx", "hpp", "hh"),
        setOf(
            "auto", "bool", "break", "case", "catch", "char", "class", "const", "constexpr",
            "continue", "default", "delete", "do", "double", "else", "enum", "explicit",
            "extern", "false", "float", "for", "friend", "if", "inline", "int", "long",
            "namespace", "new", "noexcept", "nullptr", "operator", "override", "private",
            "protected", "public", "return", "short", "sizeof", "static", "struct", "switch",
            "template", "this", "throw", "true", "try", "typedef", "typename", "union",
            "unsigned", "using", "virtual", "void", "volatile", "while",
        ),
        "//", "/*" to "*/",
    ),
    SHELL(
        "Shell", setOf("sh", "bash", "zsh"),
        setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "return", "export", "local", "echo", "cd", "set", "unset",
        ),
        "#", null,
    ),
    GRADLE("Gradle", setOf("gradle"), setOf("plugins", "dependencies", "android", "repositories"), "//", null),
    PLAIN("Plain text", emptySet(), emptySet(), null, null);

    companion object {
        fun fromFileName(name: String): Language {
            val lower = name.lowercase()
            // Special file names without an extension.
            when (lower) {
                "dockerfile" -> return PLAIN
                "makefile" -> return PLAIN
                "gradlew", "gradlew.bat" -> return SHELL
                ".gitignore", ".gitattributes" -> return PLAIN
            }
            val ext = lower.substringAfterLast('.', "")
            return entries.firstOrNull { ext in it.extensions } ?: PLAIN
        }
    }
}

data class EditorUiState(
    val repositoryId: Long = -1L,
    val repositoryName: String = "",
    val repositoryRoot: File? = null,
    val currentDirectory: String = "",
    val entries: List<FileNode> = emptyList(),
    val loadingTree: Boolean = false,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabIndex: Int = -1,
    val findVisible: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val projectSearchQuery: String = "",
    val message: String? = null,
    val errorMessage: String? = null,
    val unsavedPrompt: Boolean = false,
    val pendingAction: (() -> Unit)? = null,
) {
    val activeTab: EditorTab? get() = tabs.getOrNull(activeTabIndex)
    val hasUnsavedChanges: Boolean get() = tabs.any { it.isDirty }
}

/**
 * Drives the Editor screen: file tree, tabs, editing, saving, find/replace and
 * project-wide search.
 *
 * Correctness rules enforced here:
 *  * a save writes the exact text the user sees, preserving the detected line
 *    endings and charset;
 *  * a save is refused if the file changed on disk since it was opened, so a
 *    terminal command cannot be silently overwritten;
 *  * closing a dirty tab asks first;
 *  * every path is validated against the repository root before it is used.
 */
class EditorViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Binds the editor to a repository and opens its root directory. */
    fun openRepository(repositoryId: Long) {
        viewModelScope.launch {
            val entity = container.database.repositoryDao().byId(repositoryId) ?: run {
                _state.update { it.copy(errorMessage = "That repository is no longer registered.") }
                return@launch
            }
            val root = runCatching { container.workspaceManager.resolveRelative(entity.localPath) }.getOrNull()
            if (root == null || !root.isDirectory) {
                _state.update { it.copy(errorMessage = "The folder for ${entity.displayName} is missing on disk.") }
                return@launch
            }
            _state.update {
                it.copy(
                    repositoryId = repositoryId,
                    repositoryName = entity.displayName,
                    repositoryRoot = root,
                    currentDirectory = "",
                    tabs = emptyList(),
                    activeTabIndex = -1,
                )
            }
            loadDirectory("")
        }
    }

    fun loadDirectory(relativeDir: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingTree = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching { container.fileTreeReader.list(root, relativeDir) }
            }
            result
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            loadingTree = false,
                            currentDirectory = relativeDir,
                            entries = entries,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loadingTree = false,
                            errorMessage = failure.message ?: "Could not list $relativeDir",
                        )
                    }
                }
        }
    }

    fun navigateUp(): Boolean {
        val current = _state.value.currentDirectory
        if (current.isEmpty()) return false
        loadDirectory(current.substringBeforeLast('/', ""))
        return true
    }

    /** Opens a file in a tab, reusing an existing tab when the path is already open. */
    fun openFile(relativePath: String) {
        val existingIndex = _state.value.tabs.indexOfFirst { it.relativePath == relativePath }
        if (existingIndex >= 0) {
            _state.update { it.copy(activeTabIndex = existingIndex) }
            return
        }

        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult {
                val content: FileContent = container.fileOperations.read(root, relativePath)
                content
            }
            when (result) {
                is AppResult.Success -> {
                    val content = result.data
                    val tab = EditorTab(
                        relativePath = content.relativePath,
                        displayName = content.relativePath.substringAfterLast('/'),
                        language = Language.fromFileName(content.relativePath),
                        text = content.text,
                        savedText = content.text,
                        lineEnding = content.lineEnding,
                        charsetName = content.charsetName,
                        lastModifiedOnDisk = content.lastModified,
                        onDiskSizeBytes = content.sizeBytes,
                        readOnly = content.isBinary || content.truncated,
                        truncated = content.truncated,
                    )
                    _state.update { current ->
                        val tabs = current.tabs + tab
                        current.copy(tabs = tabs, activeTabIndex = tabs.lastIndex)
                    }
                    container.database.recentFileDao().upsert(
                        com.projecthub.gitforge.data.db.RecentFileEntity(
                            repositoryId = _state.value.repositoryId,
                            relativePath = content.relativePath,
                            openedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(errorMessage = result.error.message) }
                }
            }
        }
    }

    fun selectTab(index: Int) {
        if (index in _state.value.tabs.indices) _state.update { it.copy(activeTabIndex = index) }
    }

    fun updateActiveText(text: String) {
        _state.update { current ->
            val tabs = current.tabs.toMutableList()
            val index = current.activeTabIndex
            if (index !in tabs.indices) return@update current
            tabs[index] = tabs[index].copy(text = text)
            current.copy(tabs = tabs)
        }
    }

    fun saveActive() {
        val current = _state.value
        val index = current.activeTabIndex
        val tab = current.activeTab ?: return
        if (tab.readOnly) {
            _state.update { it.copy(errorMessage = "${tab.displayName} is read-only (binary or truncated file).") }
            return
        }
        val root = current.repositoryRoot ?: return

        viewModelScope.launch {
            val result = appResult {
                container.fileOperations.write(
                    root = root,
                    relativePath = tab.relativePath,
                    text = tab.text,
                    lineEnding = tab.lineEnding,
                    charsetName = tab.charsetName,
                    expectedLastModified = tab.lastModifiedOnDisk,
                    expectedSizeBytes = tab.onDiskSizeBytes.takeIf { it >= 0 },
                )
            }
            when (result) {
                is AppResult.Success -> {
                    val onDisk = withContext(Dispatchers.IO) {
                        runCatching { PathSafety.resolveInside(root, tab.relativePath) }
                            .map { it.lastModified() to it.length() }
                            .getOrDefault(0L to 0L)
                    }
                    _state.update { s ->
                        val tabs = s.tabs.toMutableList()
                        if (index in tabs.indices) {
                            tabs[index] = tabs[index].copy(
                                savedText = tabs[index].text,
                                lastModifiedOnDisk = onDisk.first,
                                onDiskSizeBytes = onDisk.second,
                            )
                        }
                        s.copy(tabs = tabs, message = "Saved ${tab.displayName}")
                    }
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(errorMessage = result.error.message) }
                }
            }
        }
    }

    /**
     * Saves every dirty writable tab. Returns the launched [Job] so callers that
     * must run after the saves (such as [saveAndRunPending]) can join it first.
     * Fire-and-forget callers (for example a method reference) simply ignore the
     * return value, so this stays a drop-in replacement for a Unit function.
     */
    fun saveAll(): Job {
        val dirty = _state.value.tabs.filter { it.isDirty && !it.readOnly }
        if (dirty.isEmpty()) {
            _state.update { it.copy(message = "Nothing to save.") }
            // Return an already-completed job so join() is a no-op for the caller.
            return viewModelScope.launch { }
        }
        return viewModelScope.launch {
            var saved = 0
            val failures = mutableListOf<String>()
            val failedPaths = mutableSetOf<String>()
            val root = _state.value.repositoryRoot ?: return@launch
            val refreshed = mutableMapOf<String, Pair<Long, Long>>()
            for (tab in dirty) {
                val result = appResult {
                    container.fileOperations.write(
                        root = root,
                        relativePath = tab.relativePath,
                        text = tab.text,
                        lineEnding = tab.lineEnding,
                        charsetName = tab.charsetName,
                        expectedLastModified = tab.lastModifiedOnDisk,
                        expectedSizeBytes = tab.onDiskSizeBytes.takeIf { it >= 0 },
                    )
                }
                if (result is AppResult.Success) {
                    saved++
                    refreshed[tab.relativePath] = withContext(Dispatchers.IO) {
                        runCatching { PathSafety.resolveInside(root, tab.relativePath) }
                            .map { it.lastModified() to it.length() }
                            .getOrDefault(0L to 0L)
                    }
                } else {
                    failures += tab.displayName
                    failedPaths += tab.relativePath
                }
            }
            _state.update { s ->
                val tabs = s.tabs.map { t ->
                    val stamp = refreshed[t.relativePath]
                    if (stamp != null && t.isDirty && !t.readOnly && t.relativePath !in failedPaths) {
                        t.copy(
                            savedText = t.text,
                            lastModifiedOnDisk = stamp.first,
                            onDiskSizeBytes = stamp.second,
                        )
                    } else {
                        t
                    }
                }
                s.copy(
                    tabs = tabs,
                    message = "Saved $saved file(s)",
                    errorMessage = if (failures.isEmpty()) null else "Could not save: ${failures.joinToString()}",
                )
            }
        }
    }

    /** Closes a tab, prompting when it has unsaved changes. */
    fun closeTab(index: Int) {
        val tab = _state.value.tabs.getOrNull(index) ?: return
        if (tab.isDirty) {
            _state.update {
                it.copy(
                    unsavedPrompt = true,
                    pendingAction = { closeTabForced(index) },
                )
            }
            return
        }
        closeTabForced(index)
    }

    private fun closeTabForced(index: Int) {
        _state.update { current ->
            val tabs = current.tabs.toMutableList()
            if (index !in tabs.indices) return@update current
            tabs.removeAt(index)
            val newActive = when {
                tabs.isEmpty() -> -1
                current.activeTabIndex >= tabs.size -> tabs.lastIndex
                else -> current.activeTabIndex
            }
            current.copy(tabs = tabs, activeTabIndex = newActive, unsavedPrompt = false, pendingAction = null)
        }
    }

    fun confirmPendingAction() {
        val action = _state.value.pendingAction
        _state.update { it.copy(unsavedPrompt = false, pendingAction = null) }
        action?.invoke()
    }

    fun cancelPendingAction() {
        _state.update { it.copy(unsavedPrompt = false, pendingAction = null) }
    }

    fun saveAndRunPending() {
        val action = _state.value.pendingAction
        // Save first and only run the pending action once the saves landed:
        // awaiting the job guarantees the file contents are on disk (or the
        // failure message is shown) before the close proceeds.
        val saveJob = saveAll()
        viewModelScope.launch {
            saveJob.join()
            _state.update { it.copy(unsavedPrompt = false, pendingAction = null) }
            action?.invoke()
        }
    }

    // ------------------------------------------------------------ find / replace

    fun toggleFind() = _state.update { it.copy(findVisible = !it.findVisible) }

    fun setFindQuery(query: String) = _state.update { it.copy(findQuery = query) }

    fun setReplaceQuery(query: String) = _state.update { it.copy(replaceQuery = query) }

    fun replaceNext() {
        val current = _state.value
        val tab = current.activeTab ?: return
        if (current.findQuery.isEmpty()) return
        val index = tab.text.indexOf(current.findQuery)
        if (index < 0) {
            _state.update { it.copy(message = "No match for '${current.findQuery}'") }
            return
        }
        val replaced = tab.text.replaceRange(index, index + current.findQuery.length, current.replaceQuery)
        _state.update { s ->
            val tabs = s.tabs.toMutableList()
            tabs[s.activeTabIndex] = tabs[s.activeTabIndex].copy(text = replaced)
            s.copy(tabs = tabs)
        }
    }

    fun replaceAll() {
        val current = _state.value
        val tab = current.activeTab ?: return
        if (current.findQuery.isEmpty()) return
        val count = tab.text.windowed(current.findQuery.length).count { it == current.findQuery }
        if (count == 0) {
            _state.update { it.copy(message = "No match for '${current.findQuery}'") }
            return
        }
        val replaced = tab.text.replace(current.findQuery, current.replaceQuery)
        _state.update { s ->
            val tabs = s.tabs.toMutableList()
            tabs[s.activeTabIndex] = tabs[s.activeTabIndex].copy(text = replaced)
            s.copy(tabs = tabs, message = "Replaced $count occurrence(s)")
        }
    }

    // ------------------------------------------------------------- project search

    fun setProjectSearchQuery(query: String) {
        _state.update { it.copy(projectSearchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        val root = _state.value.repositoryRoot ?: return
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            kotlinx.coroutines.delay(250)
            val results = withContext(Dispatchers.IO) {
                runCatching { container.fileOperations.search(root, query, maxResults = 200) }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(searching = false, searchResults = results) }
        }
    }

    // ------------------------------------------------------------ file management

    fun createFile(parentDirectory: String, name: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val path = if (parentDirectory.isEmpty()) name else "$parentDirectory/$name"
            val result = appResult { container.fileOperations.createFile(root, path) }
            if (result is AppResult.Success) {
                loadDirectory(parentDirectory)
                _state.update { it.copy(message = "Created $name") }
                openFile(path)
            } else {
                _state.update { it.copy(errorMessage = (result as AppResult.Failure).error.message) }
            }
        }
    }

    fun createDirectory(parentDirectory: String, name: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val path = if (parentDirectory.isEmpty()) name else "$parentDirectory/$name"
            val result = appResult { container.fileOperations.createDirectory(root, path) }
            if (result is AppResult.Success) {
                loadDirectory(parentDirectory)
                _state.update { it.copy(message = "Created folder $name") }
            } else {
                _state.update { it.copy(errorMessage = (result as AppResult.Failure).error.message) }
            }
        }
    }

    fun rename(relativePath: String, newName: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.fileOperations.rename(root, relativePath, newName) }
            when (result) {
                is AppResult.Success -> {
                    val newPath = PathSafety.relativize(root, result.data)
                    _state.update { current ->
                        current.copy(
                            tabs = current.tabs.map {
                                if (it.relativePath == relativePath) {
                                    it.copy(relativePath = newPath, displayName = result.data.name)
                                } else {
                                    it
                                }
                            },
                            message = "Renamed to $newName",
                        )
                    }
                    loadDirectory(_state.value.currentDirectory)
                }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    /** Deletes a file or folder. The caller must have confirmed first. */
    fun delete(relativePath: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.fileOperations.delete(root, relativePath) }
            when (result) {
                is AppResult.Success -> {
                    _state.update { current ->
                        current.copy(
                            tabs = current.tabs.filterNot { it.relativePath == relativePath || it.relativePath.startsWith("$relativePath/") },
                            message = "Deleted ${relativePath.substringAfterLast('/')}",
                        )
                    }
                    val tabs = _state.value.tabs
                    _state.update {
                        it.copy(activeTabIndex = if (tabs.isEmpty()) -1 else it.activeTabIndex.coerceIn(0, tabs.lastIndex))
                    }
                    loadDirectory(_state.value.currentDirectory)
                }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    /**
     * Reloads a tab from disk. Used when a terminal command changed the file and the
     * user chooses to take the on-disk version.
     */
    fun reloadTab(index: Int) {
        val tab = _state.value.tabs.getOrNull(index) ?: return
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.fileOperations.read(root, tab.relativePath) }
            when (result) {
                is AppResult.Success -> {
                    val content = result.data
                    _state.update { s ->
                        val tabs = s.tabs.toMutableList()
                        if (index in tabs.indices) {
                            tabs[index] = tabs[index].copy(
                                text = content.text,
                                savedText = content.text,
                                lineEnding = content.lineEnding,
                                charsetName = content.charsetName,
                                lastModifiedOnDisk = content.lastModified,
                                onDiskSizeBytes = content.sizeBytes,
                                // A reloaded tab reflects the current on-disk content,
                                // so a previously truncated/binary view becomes
                                // editable again when the file now loads fully.
                                readOnly = content.isBinary || content.truncated,
                                truncated = content.truncated,
                            )
                        }
                        s.copy(tabs = tabs, message = "Reloaded ${tab.displayName} from disk")
                    }
                }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    /** Detects files that changed on disk while their tab had unsaved edits. */
    fun externallyModifiedTabs(): List<EditorTab> {
        val root = _state.value.repositoryRoot ?: return emptyList()
        return _state.value.tabs.filter { tab ->
            val onDisk = runCatching { PathSafety.resolveInside(root, tab.relativePath) }.getOrNull()
            // A missing file (deleted out from under the tab) always counts as a
            // conflict: saving blindly would resurrect content the user removed.
            if (onDisk == null || !onDisk.exists()) return@filter true
            onDisk.lastModified() != tab.lastModifiedOnDisk ||
                (tab.onDiskSizeBytes >= 0 && onDisk.length() != tab.onDiskSizeBytes)
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    /** Reports a path-safety failure without crashing the editor. */
    fun reportPathError(e: IOException) {
        AppLog.w("Path validation rejected an editor operation", e)
        _state.update { it.copy(errorMessage = e.message) }
    }

    override fun onCleared() {
        searchJob?.cancel()
        super.onCleared()
    }
}
