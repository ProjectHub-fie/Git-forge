package com.projecthub.gitforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.terminal.AnsiColor
import com.projecthub.gitforge.terminal.AnsiStyle
import com.projecthub.gitforge.terminal.SessionState
import com.projecthub.gitforge.terminal.TerminalLine
import com.projecthub.gitforge.ui.TerminalViewModel
import com.projecthub.gitforge.ui.components.BannerTone
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.TerminalColors
import com.projecthub.gitforge.ui.theme.TerminalTextStyle
import androidx.compose.foundation.isSystemInDarkTheme
import com.projecthub.gitforge.ui.theme.DarkTerminal
import com.projecthub.gitforge.ui.theme.LightTerminal

/**
 * Terminal: real command execution against the installed userspace.
 *
 * Output is a lazy list so a command producing thousands of lines stays responsive,
 * and the buffer is capped by the scrollback setting. Nothing here is simulated:
 * every line comes from a real process or a real builtin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    container: AppContainer,
    viewModel: TerminalViewModel,
    activeRepositoryId: Long,
    onGoToPackages: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    var showSessionMenu by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf<String?>(null) }
    val terminalColors = if (isSystemInDarkTheme()) DarkTerminal else LightTerminal

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }

    LaunchedEffect(activeRepositoryId) {
        if (activeRepositoryId > 0) {
            val root = runCatching {
                container.workspaceManager.resolveRelative(
                    container.database.repositoryDao().byId(activeRepositoryId)?.localPath ?: "",
                )
            }.getOrNull()
            if (root != null && root.isDirectory) {
                viewModel.ensureSession(root)
                viewModel.setWorkingDirectory(root.absolutePath)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        TerminalToolbar(
            sessionName = state.sessionName,
            workingDirectory = state.workingDirectory,
            sessionState = state.sessionState,
            lastExitCode = state.lastExitCode,
            sessionCount = state.sessionIds.size,
            running = state.busy || state.sessionState == SessionState.RUNNING,
            onNewSession = { viewModel.newSession() },
            onToggleSessionMenu = { showSessionMenu = true },
            onClear = viewModel::clearOutput,
            onCopy = {
                clipboard.setText(AnnotatedString(viewModel.copyOutput()))
            },
            onStop = { showStopConfirm = true },
            onPackages = onGoToPackages,
        )

        showSessionMenu.let { _ ->
            // Session picker is rendered as an anchored menu below the toolbar.
        }

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
                tone = BannerTone.WARNING,
                actionLabel = "OK",
                onAction = viewModel::clearMessage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        SessionPickerRow(
            visible = showSessionMenu,
            sessionIds = state.sessionIds,
            activeId = state.activeSessionId,
            onSelect = {
                viewModel.selectSession(it)
                showSessionMenu = false
            },
            onClose = { id -> viewModel.closeSession(id) },
            onDismiss = { showSessionMenu = false },
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            color = terminalColors.background,
        ) {
            if (state.lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = terminalColors.foreground.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "GitForge shell ready",
                            style = TerminalTextStyle,
                            color = terminalColors.foreground.copy(alpha = 0.7f),
                        )
                        Text(
                            "Type 'help' for builtins, or install runtimes in Packages for node, npm and python3.",
                            style = TerminalTextStyle,
                            color = terminalColors.foreground.copy(alpha = 0.5f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(state.lines.size) { index ->
                        val line = state.lines[index]
                        TerminalOutputLine(line, terminalColors)
                    }
                }
            }
        }

        QuickCommandRow(
            onRun = { command ->
                if (isRiskyCommand(command)) pendingCommand = command else viewModel.runCommand(command)
            },
        )

        TerminalInputBar(
            input = state.input,
            running = state.busy || state.sessionState == SessionState.RUNNING,
            workingDirectory = state.workingDirectory,
            onInputChange = viewModel::setInput,
            onSubmit = viewModel::submit,
            onHistoryPrevious = viewModel::historyPrevious,
            onHistoryNext = viewModel::historyNext,
        )
    }

    if (showStopConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop the running command?") },
            text = { Text("The running process will be terminated. Output produced so far is kept in the scrollback.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancel()
                    showStopConfirm = false
                }) { Text("Stop command") }
            },
            dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text("Keep running") } },
        )
    }

    pendingCommand?.let { command ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingCommand = null },
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            title = { Text("Run this command?") },
            text = {
                Column {
                    Text(
                        "This command can execute third-party scripts from the repository. " +
                            "A cloned repository is untrusted input.",
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(command, style = TerminalTextStyle, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Choosing \"Run without scripts\" adds --ignore-scripts so dependency lifecycle " +
                            "scripts are not executed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.runCommand(command)
                    pendingCommand = null
                }) { Text("Run as written") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingCommand = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        viewModel.runCommand("$command --ignore-scripts")
                        pendingCommand = null
                    }) { Text("Run without scripts") }
                }
            },
        )
    }
}

/** npm installs can run arbitrary lifecycle scripts, so they are confirmed first. */
private fun isRiskyCommand(command: String): Boolean {
    val normalised = command.trim()
    if (normalised.contains("--ignore-scripts")) return false
    // Split pipelines/redirects into segments so `echo x | npm install` and
    // `npm --prefix dir install` cannot bypass the confirmation dialog.
    return normalised.split('|', ';', '&', '>', '<').any { segment ->
        val tokens = segment.trim().split(Regex("\\s+"))
        val npmIndex = tokens.indexOfFirst { it == "npm" || it.endsWith("/npm") }
        if (npmIndex < 0) return@any false
        val sub = tokens.drop(npmIndex + 1).firstOrNull { !it.startsWith("-") } ?: return@any false
        sub in setOf("install", "i", "ci", "add", "update", "rebuild", "link") || sub == "run"
    }
}

@Composable
private fun TerminalToolbar(
    sessionName: String,
    workingDirectory: String,
    sessionState: SessionState,
    lastExitCode: Int?,
    sessionCount: Int,
    running: Boolean,
    onNewSession: () -> Unit,
    onToggleSessionMenu: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onStop: () -> Unit,
    onPackages: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleSessionMenu) {
                    Text(sessionName.ifEmpty { "Session" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (sessionCount > 1) {
                    StatusChip("$sessionCount sessions", MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                }
                when {
                    running -> StatusChip("running", MaterialTheme.colorScheme.tertiary)
                    lastExitCode != null && lastExitCode != 0 ->
                        StatusChip("exit $lastExitCode", MaterialTheme.colorScheme.error)
                    lastExitCode != null -> StatusChip("exit 0", MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.weight(1f))
                if (running) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop the running command", tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy terminal output")
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear terminal output")
                }
                IconButton(onClick = onNewSession) {
                    Icon(Icons.Default.Add, contentDescription = "New terminal session")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoPath(workingDirectory, Modifier.weight(1f))
                TextButton(onClick = onPackages) { Text("Runtimes") }
            }
        }
    }
}

@Composable
private fun SessionPickerRow(
    visible: Boolean,
    sessionIds: List<Long>,
    activeId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sessions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            sessionIds.forEach { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onSelect(id) }, modifier = Modifier.weight(1f)) {
                        Text(
                            "Session $id" + if (id == activeId) "  (active)" else "",
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { onClose(id) }, enabled = sessionIds.size > 1) {
                        Icon(Icons.Default.Close, contentDescription = "Close session $id", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalOutputLine(line: TerminalLine, colors: TerminalColors) {
    val baseColor = when (line.stream) {
        TerminalLine.Stream.STDERR -> colors.error
        TerminalLine.Stream.SYSTEM -> colors.foreground.copy(alpha = 0.55f)
        TerminalLine.Stream.COMMAND -> colors.prompt
        TerminalLine.Stream.STDOUT -> colors.foreground
    }
    val ansiColor = line.ansi.fg?.let { resolveAnsiColor(it) }
    Text(
        text = line.text.ifEmpty { " " },
        style = TerminalTextStyle.copy(
            fontWeight = if (line.ansi.bold) FontWeight.Bold else FontWeight.Normal,
        ),
        color = ansiColor ?: baseColor,
        softWrap = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Maps the parser's palette onto readable colours on the terminal background. */
private fun resolveAnsiColor(color: AnsiColor): androidx.compose.ui.graphics.Color = when (color) {
    AnsiColor.BLACK, AnsiColor.BRIGHT_BLACK -> androidx.compose.ui.graphics.Color(0xFF6B7280)
    AnsiColor.RED, AnsiColor.BRIGHT_RED -> androidx.compose.ui.graphics.Color(0xFFF87171)
    AnsiColor.GREEN, AnsiColor.BRIGHT_GREEN -> androidx.compose.ui.graphics.Color(0xFF4ADE80)
    AnsiColor.YELLOW, AnsiColor.BRIGHT_YELLOW -> androidx.compose.ui.graphics.Color(0xFFFACC15)
    AnsiColor.BLUE, AnsiColor.BRIGHT_BLUE -> androidx.compose.ui.graphics.Color(0xFF60A5FA)
    AnsiColor.MAGENTA, AnsiColor.BRIGHT_MAGENTA -> androidx.compose.ui.graphics.Color(0xFFC084FC)
    AnsiColor.CYAN, AnsiColor.BRIGHT_CYAN -> androidx.compose.ui.graphics.Color(0xFF22D3EE)
    AnsiColor.WHITE, AnsiColor.BRIGHT_WHITE -> androidx.compose.ui.graphics.Color(0xFFE5E7EB)
}

@Composable
private fun QuickCommandRow(onRun: (String) -> Unit) {
    val quick = listOf(
        "git status",
        "git log --oneline -n 10",
        "ls -la",
        "pwd",
        "node --version",
        "npm --version",
        "python3 --version",
        "npm run build",
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(quick) { command ->
            AssistChip(onClick = { onRun(command) }, label = { Text(command, style = TerminalTextStyle) })
        }
    }
}

@Composable
private fun TerminalInputBar(
    input: String,
    running: Boolean,
    workingDirectory: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryPrevious: () -> Unit,
    onHistoryNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onHistoryPrevious) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous command from history")
            }
            IconButton(onClick = onHistoryNext) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next command from history")
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Run a command…") },
                textStyle = TerminalTextStyle,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onSubmit() },
                ),
            )
            IconButton(onClick = onSubmit, enabled = input.isNotBlank() && !running) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run the command")
            }
        }
    }
}
