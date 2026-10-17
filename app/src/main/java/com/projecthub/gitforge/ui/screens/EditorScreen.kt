package com.projecthub.gitforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.ui.EditorTab
import com.projecthub.gitforge.ui.EditorViewModel
import com.projecthub.gitforge.ui.Language
import com.projecthub.gitforge.ui.components.BannerTone
import com.projecthub.gitforge.ui.components.DestructiveConfirmDialog
import com.projecthub.gitforge.ui.components.EmptyState
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.RowDivider
import com.projecthub.gitforge.ui.components.SectionHeader
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.CodeTextStyle
import com.projecthub.gitforge.workspace.FileNode

/**
 * Editor: project explorer, tabs, code editing, find/replace and project search.
 *
 * The text field edits the real file contents held in the ViewModel, and Save writes
 * those exact bytes back to the repository working tree. The mobile character row is
 * collapsible so it never permanently consumes keyboard space.
 */
@Composable
fun EditorScreen(
    container: AppContainer,
    viewModel: EditorViewModel,
    activeRepositoryId: Long,
    onGoToRepositories: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = container.settingsRepository.snapshot(),
    )

    var showExplorer by remember { mutableStateOf(true) }
    var showFindReplace by remember { mutableStateOf(false) }
    var showProjectSearch by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf<CreateKind?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var showCharRow by remember { mutableStateOf(false) }

    LaunchedEffect(activeRepositoryId, state.repositoryId) {
        if (activeRepositoryId > 0 && state.repositoryId != activeRepositoryId) {
            viewModel.openRepository(activeRepositoryId)
        }
    }

    // Warn when a terminal command changed a file that also has unsaved edits.
    // Keyed on tab count + dirtiness, not the tabs list object (recreated per
    // keystroke), and only dirty tabs can produce a save conflict — a clean tab
    // changed by pull/checkout just needs a reload, not a scary banner.
    val dirtyFingerprints = state.tabs.filter { it.isDirty }.map { it.relativePath to it.lastModifiedOnDisk }
    LaunchedEffect(dirtyFingerprints, state.repositoryRoot) {
        if (dirtyFingerprints.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val external = viewModel.externallyModifiedTabs().filter { it.isDirty }
        if (external.isNotEmpty()) {
            viewModel.reportPathError(
                java.io.IOException(
                    "${external.size} open file(s) changed on disk: " +
                        external.joinToString { it.displayName } +
                        ". Save will be refused until you reload them.",
                ),
            )
        }
    }

    if (activeRepositoryId <= 0 || state.repositoryRoot == null) {
        NoRepositorySelected(onGoToRepositories)
        return
    }

    Column(Modifier.fillMaxSize()) {
        EditorToolbar(
            repositoryName = state.repositoryName,
            activeTab = state.activeTab,
            canSave = state.activeTab?.let { it.isDirty && !it.readOnly } == true,
            hasUnsaved = state.hasUnsavedChanges,
            onToggleExplorer = { showExplorer = !showExplorer },
            onSave = viewModel::saveActive,
            onSaveAll = viewModel::saveAll,
            onFind = {
                showFindReplace = !showFindReplace
                viewModel.toggleFind()
            },
            onProjectSearch = { showProjectSearch = !showProjectSearch },
            onToggleCharRow = { showCharRow = !showCharRow },
            onNewFile = { createDialog = CreateKind.FILE },
            onNewFolder = { createDialog = CreateKind.DIRECTORY },
        )

        state.errorMessage?.let {
            InfoBanner(
                message = it,
                tone = BannerTone.ERROR,
                actionLabel = "Dismiss",
                onAction = viewModel::clearError,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        state.message?.let {
            InfoBanner(
                message = it,
                tone = BannerTone.INFO,
                actionLabel = "OK",
                onAction = viewModel::clearMessage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (state.tabs.isNotEmpty()) {
            TabStrip(
                tabs = state.tabs,
                activeIndex = state.activeTabIndex,
                onSelect = viewModel::selectTab,
                onClose = viewModel::closeTab,
            )
        }

        if (showFindReplace) {
            FindReplacePanel(
                findQuery = state.findQuery,
                replaceQuery = state.replaceQuery,
                onFindChange = viewModel::setFindQuery,
                onReplaceChange = viewModel::setReplaceQuery,
                onReplace = viewModel::replaceNext,
                onReplaceAll = viewModel::replaceAll,
                onClose = {
                    showFindReplace = false
                    viewModel.toggleFind()
                },
            )
        }

        if (showProjectSearch) {
            ProjectSearchPanel(
                query = state.projectSearchQuery,
                results = state.searchResults,
                searching = state.searching,
                onQueryChange = viewModel::setProjectSearchQuery,
                onOpenHit = { hit ->
                    val dir = hit.relativePath.substringBeforeLast('/', "")
                    if (dir != state.currentDirectory) viewModel.loadDirectory(dir)
                    viewModel.openFile(hit.relativePath)
                    showProjectSearch = false
                },
                onClose = { showProjectSearch = false },
            )
        }

        if (showExplorer) {
            ExplorerPanel(
                currentDirectory = state.currentDirectory,
                entries = state.entries,
                loading = state.loadingTree,
                onOpenDirectory = viewModel::loadDirectory,
                onNavigateUp = { viewModel.navigateUp() },
                onOpenFile = viewModel::openFile,
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                modifier = Modifier.height(200.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            val tab = state.activeTab
            if (tab == null) {
                EmptyState(
                    title = "No file open",
                    description = "Pick a file from the explorer above, or use project search to find one.",
                    icon = Icons.Default.Description,
                    actionLabel = "Search project",
                    onAction = { showProjectSearch = true },
                )
            } else if (tab.loadError != null) {
                EmptyState(
                    title = "Could not open ${tab.displayName}",
                    description = tab.loadError ?: "",
                    icon = Icons.Default.Description,
                )
            } else {
                CodeEditor(
                    tab = tab,
                    fontSizeSp = settings.editorFontSizeSp,
                    showLineNumbers = settings.editorShowLineNumbers,
                    softWrap = settings.editorSoftWrap,
                    readOnly = tab.readOnly,
                    onTextChange = viewModel::updateActiveText,
                )
            }
        }

        if (showCharRow) {
            CharacterRow(onInsert = { text ->
                val tab = state.activeTab ?: return@CharacterRow
                viewModel.updateActiveText(tab.text + text)
            })
        }

        if (state.activeTab != null && !state.activeTab!!.readOnly) {
            EditorStatusBar(
                tab = state.activeTab!!,
                externallyChanged = viewModel.externallyModifiedTabs().any { it.relativePath == state.activeTab!!.relativePath },
                onReload = { viewModel.reloadTab(state.activeTabIndex) },
            )
        }
    }

    createDialog?.let { kind ->
        NameInputDialog(
            title = if (kind == CreateKind.FILE) "New file" else "New folder",
            initial = "",
            label = if (kind == CreateKind.FILE) "File name" else "Folder name",
            onDismiss = { createDialog = null },
            onConfirm = { name ->
                if (kind == CreateKind.FILE) {
                    viewModel.createFile(state.currentDirectory, name)
                } else {
                    viewModel.createDirectory(state.currentDirectory, name)
                }
                createDialog = null
            },
        )
    }

    renameTarget?.let { path ->
        NameInputDialog(
            title = "Rename",
            initial = path.substringAfterLast('/'),
            label = "New name",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.rename(path, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { path ->
        DestructiveConfirmDialog(
            title = "Delete ${path.substringAfterLast('/')}?",
            explanation = "This removes the item from the working tree. If it is tracked by Git, the deletion " +
                "will show up as an unstaged change and can be recovered from the last commit.",
            confirmLabel = "Delete",
            detail = path,
            onConfirm = {
                viewModel.delete(path)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (state.unsavedPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingAction,
            title = { Text("Unsaved changes") },
            text = { Text("This file has unsaved changes. Save them before closing the tab?") },
            confirmButton = { TextButton(onClick = viewModel::saveAndRunPending) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::cancelPendingAction) { Text("Cancel") }
                    TextButton(onClick = viewModel::confirmPendingAction) { Text("Discard") }
                }
            },
        )
    }
}

private enum class CreateKind { FILE, DIRECTORY }

@Composable
private fun EditorToolbar(
    repositoryName: String,
    activeTab: EditorTab?,
    canSave: Boolean,
    hasUnsaved: Boolean,
    onToggleExplorer: () -> Unit,
    onSave: () -> Unit,
    onSaveAll: () -> Unit,
    onFind: () -> Unit,
    onProjectSearch: () -> Unit,
    onToggleCharRow: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleExplorer) {
                    Icon(
                        if (repositoryName.isNotEmpty()) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = "Show or hide the file explorer",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = activeTab?.displayName ?: repositoryName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = activeTab?.let { "${it.language.displayName}${if (it.isDirty) " · unsaved" else ""}" }
                            ?: "No file open",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activeTab?.isDirty == true) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onFind) {
                    Icon(Icons.Default.Search, contentDescription = "Find and replace in this file")
                }
                IconButton(onClick = onProjectSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search across the whole project")
                }
                IconButton(onClick = onSave, enabled = canSave) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = if (hasUnsaved) "Save this file (unsaved changes)" else "Save this file",
                    )
                }
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "More file actions")
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Save all") },
                            onClick = { overflow = false; onSaveAll() },
                        )
                        DropdownMenuItem(
                            text = { Text("New file") },
                            onClick = { overflow = false; onNewFile() },
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            onClick = { overflow = false; onNewFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text("Toggle symbol row") },
                            onClick = { overflow = false; onToggleCharRow() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<EditorTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = activeIndex.coerceAtLeast(0),
        edgePadding = 8.dp,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = index == activeIndex,
                onClick = { onSelect(index) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tab.isDirty) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, androidx.compose.foundation.shape.CircleShape),
                            )
                        }
                    }
                },
                icon = {
                    if (tabs.size > 1) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close ${tab.displayName}",
                            modifier = Modifier
                                .size(16.dp)
                                .clickableNoRipple { onClose(index) },
                        )
                    }
                },
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/**
 * The code surface.
 *
 * Line numbers and syntax colouring are rendered beside a transparent text field, so
 * editing stays a real platform text field (selection handles, clipboard, IME) while
 * still showing a highlighted view. Highlighting is computed from the same string the
 * field edits, so the two can never drift apart.
 */
@Composable
private fun CodeEditor(
    tab: EditorTab,
    fontSizeSp: Int,
    showLineNumbers: Boolean,
    softWrap: Boolean,
    readOnly: Boolean,
    onTextChange: (String) -> Unit,
) {
    val highlight = remember(tab.text, tab.language) { highlightCode(tab.text, tab.language) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val style = CodeTextStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(fontSizeSp.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp))

    Column(Modifier.fillMaxSize()) {
        if (tab.truncated) {
            InfoBanner(
                message = "This file is larger than the editor limit, so only the beginning is shown. " +
                    "Editing is disabled to avoid truncating the file on save.",
                tone = BannerTone.WARNING,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Row(Modifier.fillMaxSize()) {
            if (showLineNumbers) {
                LazyColumn(
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(vertical),
                    horizontalAlignment = Alignment.End,
                ) {
                    items((1..tab.lineCount).toList()) { line ->
                        Text(
                            text = line.toString(),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp, top = 0.dp, bottom = 0.dp),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (readOnly) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(vertical)
                            .horizontalScroll(if (softWrap) rememberScrollState(0) else horizontal)
                            .padding(8.dp),
                    ) {
                        Text(highlight, style = style)
                    }
                } else {
                    var value by remember(tab.relativePath) {
                        mutableStateOf(TextFieldValue(tab.text, androidx.compose.ui.text.TextRange(tab.text.length)))
                    }
                    // Keep the field in step when the tab text changes elsewhere
                    // (find/replace, reload from disk, undo).
                    LaunchedEffect(tab.text) {
                        if (value.text != tab.text) {
                            value = value.copy(text = tab.text, selection = androidx.compose.ui.text.TextRange(tab.text.length))
                        }
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { newValue ->
                            value = newValue
                            if (newValue.text != tab.text) onTextChange(newValue.text)
                        },
                        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vertical)
                            .horizontalScroll(if (softWrap) rememberScrollState(0) else horizontal)
                            .padding(8.dp),
                        visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
            }
        }
    }
}

/**
 * Tokenises a document into coloured spans.
 *
 * A full parser is not needed for readable highlighting: comments and strings are
 * found first and everything inside them is left alone, then keywords, numbers and
 * annotations are coloured. That ordering is what stops a keyword inside a string
 * literal from being coloured incorrectly.
 */
internal fun highlightCode(text: String, language: Language): AnnotatedString = buildAnnotatedString {
    append(text)
    if (language == Language.PLAIN || text.isEmpty()) return@buildAnnotatedString

    val commentColor = SpanStyle(color = Color(0xFF6A9955))
    val stringColor = SpanStyle(color = Color(0xFFCE9178))
    val keywordColor = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Medium)
    val numberColor = SpanStyle(color = Color(0xFFB5CEA8))

    val protected = BooleanArray(text.length)

    fun protect(start: Int, end: Int) {
        for (i in start until end.coerceAtMost(text.length)) protected[i] = true
    }

    // Strings first, so comment markers inside them are not treated as comments.
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '"' || c == '\'') {
            val quote = c
            var j = i + 1
            while (j < text.length) {
                if (text[j] == '\\') { j += 2; continue }
                if (text[j] == quote) { j++; break }
                j++
            }
            val end = j.coerceAtMost(text.length)
            addStyle(stringColor, i, end)
            protect(i, end)
            i = end
        } else {
            i++
        }
    }

    // Comments.
    language.lineComment?.let { marker ->
        var index = text.indexOf(marker)
        while (index >= 0) {
            if (!protected.getOrElse(index) { false }) {
                val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                addStyle(commentColor, index, end)
                protect(index, end)
            }
            index = text.indexOf(marker, index + marker.length)
        }
    }
    language.blockComment?.let { (open, close) ->
        var index = text.indexOf(open)
        while (index >= 0) {
            if (!protected.getOrElse(index) { false }) {
                val end = text.indexOf(close, index + open.length).let { if (it < 0) text.length else it + close.length }
                addStyle(commentColor, index, end)
                protect(index, end)
                index = text.indexOf(open, end)
            } else {
                index = text.indexOf(open, index + open.length)
            }
        }
    }

    // Keywords, only outside protected regions.
    if (language.keywords.isNotEmpty()) {
        val pattern = Regex("""\b(${language.keywords.joinToString("|") { Regex.escape(it) }})\b""")
        pattern.findAll(text).forEach { match ->
            val start = match.range.first
            if (!protected.getOrElse(start) { false }) {
                addStyle(keywordColor, start, match.range.last + 1)
                protect(start, match.range.last + 1)
            }
        }
    }

    // Numbers, only outside protected regions.
    Regex("""\b\d+(\.\d+)?\b""").findAll(text).forEach { match ->
        val start = match.range.first
        if (!protected.getOrElse(start) { false }) {
            addStyle(numberColor, start, match.range.last + 1)
        }
    }
}

@Composable
private fun FindReplacePanel(
    findQuery: String,
    replaceQuery: String,
    onFindChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = onFindChange,
                    label = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close find and replace")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = replaceQuery,
                onValueChange = onReplaceChange,
                label = { Text("Replace with") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplace, enabled = findQuery.isNotEmpty()) { Text("Replace") }
                Button(onClick = onReplaceAll, enabled = findQuery.isNotEmpty()) { Text("Replace all") }
            }
        }
    }
}

@Composable
private fun ProjectSearchPanel(
    query: String,
    results: List<com.projecthub.gitforge.workspace.SearchHit>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onOpenHit: (com.projecthub.gitforge.workspace.SearchHit) -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search in project") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close project search")
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                searching -> Text("Searching…", style = MaterialTheme.typography.bodySmall)
                query.isBlank() -> Text(
                    "Type to search every text file in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.isEmpty() -> Text(
                    "No matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.height(180.dp)) {
                    items(results, key = { "${it.relativePath}:${it.lineNumber}" }) { hit ->
                        Surface(
                            onClick = { onOpenHit(hit) },
                            color = Color.Transparent,
                        ) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    "${hit.relativePath}:${hit.lineNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    hit.lineText.trim(),
                                    style = CodeTextStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerPanel(
    currentDirectory: String,
    entries: List<FileNode>,
    loading: Boolean,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onNavigateUp, enabled = currentDirectory.isNotEmpty()) {
                    Text("Up")
                }
                MonoPath(
                    path = "/" + currentDirectory.ifEmpty { "" },
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp))
                }
            }
            RowDivider()
            if (entries.isEmpty() && !loading) {
                Text(
                    "This folder is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.relativePath }) { node ->
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (node.isDirectory) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickableNoRipple {
                                        if (node.isDirectory) onOpenDirectory(node.relativePath) else onOpenFile(node.relativePath)
                                    },
                            )
                            if (!node.isDirectory) {
                                Text(
                                    formatBytes(node.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(
                                        Icons.Default.CreateNewFolder,
                                        contentDescription = "Actions for ${node.name}",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (!node.isDirectory) {
                                        DropdownMenuItem(
                                            text = { Text("Open") },
                                            onClick = { menu = false; onOpenFile(node.relativePath) },
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Open folder") },
                                            onClick = { menu = false; onOpenDirectory(node.relativePath) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = { menu = false; onRename(node.relativePath) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = { menu = false; onDelete(node.relativePath) },
                                    )
                                }
                            }
                        }
                        RowDivider()
                    }
                }
            }
        }
    }
}

/** A compact row of the punctuation most often needed on a phone keyboard. */
@Composable
private fun CharacterRow(onInsert: (String) -> Unit) {
    val symbols = listOf("{", "}", "[", "]", "(", ")", ";", ":", ".", ",", "=", "_", "/", "'", "\"", "<", ">", "*", "&", "|", "\$", "#", "@", "-", "+", "%", "!", "?")
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(symbols) { symbol ->
                AssistChip(onClick = { onInsert(symbol) }, label = { Text(symbol, style = CodeTextStyle) })
            }
        }
    }
}

@Composable
private fun EditorStatusBar(
    tab: EditorTab,
    externallyChanged: Boolean,
    onReload: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${tab.lineCount} lines · ${tab.text.length} chars · ${tab.lineEnding.name} · ${tab.charsetName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (externallyChanged) {
                StatusChip(label = "Changed on disk", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onReload) { Text("Reload") }
            } else if (tab.isDirty) {
                StatusChip(label = "Unsaved", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


