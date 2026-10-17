package com.projecthub.gitforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.gitforge.core.AppResult
import com.projecthub.gitforge.core.appResult
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.terminal.SessionState
import com.projecthub.gitforge.terminal.TerminalLine
import com.projecthub.gitforge.terminal.TerminalSession
import com.projecthub.gitforge.terminal.runtime.RuntimeGroup
import com.projecthub.gitforge.terminal.runtime.RuntimeInstallProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class TerminalUiState(
    val sessionIds: List<Long> = emptyList(),
    val activeSessionId: Long = -1L,
    val sessionName: String = "",
    val sessionState: SessionState = SessionState.IDLE,
    val workingDirectory: String = "",
    val lastExitCode: Int? = null,
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null,
    val runtimeReady: Boolean = false,
)

/**
 * Drives the Terminal screen.
 *
 * The session objects themselves live in [com.projecthub.gitforge.terminal.TerminalSessionManager],
 * not here, so navigating away and back does not lose output or kill a running
 * command. This ViewModel only mirrors the active session's state into the UI.
 */
class TerminalViewModel(private val container: AppContainer) : ViewModel() {

    private val manager = container.terminalSessionManager

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var outputJob: kotlinx.coroutines.Job? = null
    private var stateJob: kotlinx.coroutines.Job? = null
    private val runMutex = Mutex()

    init {
        viewModelScope.launch {
            manager.sessionIds.collect { ids ->
                _state.update { it.copy(sessionIds = ids) }
                if (ids.isNotEmpty() && _state.value.activeSessionId !in ids) {
                    selectSession(ids.first())
                }
            }
        }
        viewModelScope.launch {
            manager.activeId.collect { id ->
                if (id > 0) selectSession(id)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect {
                _state.update { s -> s.copy(runtimeReady = container.runtimeManager.installation().hasNode || container.runtimeManager.installation().hasPython) }
            }
        }
        // Start with one session so the screen is immediately usable.
        ensureSession()
    }

    fun ensureSession(repositoryRoot: File? = null) {
        if (manager.sessionIds.value.isEmpty()) {
            manager.createSession(repositoryRoot = repositoryRoot)
        }
    }

    fun newSession(repositoryRoot: File? = null) {
        manager.createSession(repositoryRoot = repositoryRoot)
    }

    fun selectSession(id: Long) {
        manager.select(id)
        val session = manager.session(id) ?: return
        outputJob?.cancel()
        stateJob?.cancel()
        outputJob = viewModelScope.launch {
            session.output.collect { lines ->
                _state.update { it.copy(lines = lines) }
            }
        }
        stateJob = viewModelScope.launch {
            session.state.collect { s ->
                _state.update {
                    it.copy(
                        activeSessionId = s.id,
                        sessionName = s.name,
                        sessionState = s.state,
                        workingDirectory = s.workingDirectory,
                        lastExitCode = s.lastExitCode,
                        history = session.context.history.toList(),
                    )
                }
            }
        }
    }

    fun closeSession(id: Long) {
        manager.close(id)
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    /** Runs the current input line. */
    fun submit() {
        val command = _state.value.input
        if (command.isBlank()) return
        runCommand(command)
        _state.update { it.copy(input = "", historyIndex = -1) }
    }

    /** Runs an explicit command, used by quick-action chips and package shortcuts. */
    fun runCommand(command: String) {
        val session = manager.active() ?: manager.createSession()
        if (_state.value.busy) return
        viewModelScope.launch {
            val result = runMutex.withLock {
                _state.update { it.copy(busy = true) }
                session.run(command)
            }
            _state.update {
                it.copy(
                    busy = false,
                    history = session.context.history.toList(),
                    message = if (result.exitCode != 0 && !result.cancelled) {
                        "'${command.lineSequence().first()}' exited with code ${result.exitCode}"
                    } else {
                        null
                    },
                )
            }
        }
    }

    suspend fun cancelRunning(): Boolean = manager.active()?.cancel() ?: false

    fun cancel() {
        viewModelScope.launch { manager.active()?.cancel() }
    }

    fun clearOutput() {
        manager.active()?.clear()
    }

    fun copyOutput(): String = manager.active()?.asPlainText().orEmpty()

    /** Walks command history, the way a shell does. */
    fun historyPrevious() {
        val history = _state.value.history
        if (history.isEmpty()) return
        val index = if (_state.value.historyIndex < 0) history.lastIndex else (_state.value.historyIndex - 1).coerceAtLeast(0)
        _state.update { it.copy(historyIndex = index, input = history[index]) }
    }

    fun historyNext() {
        val history = _state.value.history
        if (history.isEmpty() || _state.value.historyIndex < 0) return
        val index = _state.value.historyIndex + 1
        if (index >= history.size) {
            _state.update { it.copy(historyIndex = -1, input = "") }
        } else {
            _state.update { it.copy(historyIndex = index, input = history[index]) }
        }
    }

    /** Runs a command only after the caller has shown the appropriate warning. */
    fun runWithConfirmation(command: String, confirmed: Boolean) {
        if (confirmed) runCommand(command)
    }

    fun setWorkingDirectory(path: String) {
        val session = manager.active() ?: return
        val target = File(path)
        if (target.isDirectory) {
            session.context.cwd = target
            session.refreshWorkingDirectory()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    override fun onCleared() {
        // Sessions deliberately survive; only the output subscription is dropped.
        outputJob?.cancel()
        stateJob?.cancel()
        super.onCleared()
    }
}

data class PackagesUiState(
    val installing: Boolean = false,
    val progress: RuntimeInstallProgress? = null,
    val nodeInstalled: Boolean = false,
    val npmInstalled: Boolean = false,
    val pythonInstalled: Boolean = false,
    val pipInstalled: Boolean = false,
    val gitVersion: String? = null,
    val pythonVersion: String? = null,
    val nodeVersion: String? = null,
    val npmVersion: String? = null,
    val shellDescription: String = "",
    val abiSupported: Boolean = true,
    val abiSummary: String = "",
    val usageBytes: Long = 0,
    val message: String? = null,
    val errorMessage: String? = null,
    val pendingInstall: Set<RuntimeGroup>? = null,
    val pendingUninstall: Boolean = false,
)

/**
 * Drives the Packages screen: runtime installation, version probing and npm
 * shortcuts.
 *
 * Versions shown are always probed from the actual binaries, so the screen can never
 * claim a runtime is installed when it is not.
 */
class PackagesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PackagesUiState())
    val state: StateFlow<PackagesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val installation = container.runtimeManager.installation()
            _state.update {
                it.copy(
                    nodeInstalled = installation.hasNode,
                    npmInstalled = installation.hasNpm,
                    pythonInstalled = installation.hasPython,
                    pipInstalled = installation.hasPip,
                    abiSupported = container.runtimeManager.isAbiSupported(),
                    abiSummary = container.runtimeManager.abiSummary(),
                    usageBytes = container.runtimeManager.usageBytes(),
                )
            }

            if (installation.hasNode || installation.hasPython) {
                val probe = container.runtimeProbe
                _state.update {
                    it.copy(
                        gitVersion = probe.gitVersion(),
                        pythonVersion = if (installation.hasPython) probe.pythonVersion() else null,
                        nodeVersion = if (installation.hasNode) probe.nodeVersion() else null,
                        npmVersion = if (installation.hasNpm) probe.npmVersion() else null,
                        shellDescription = probe.shellDescription(),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        gitVersion = container.runtimeProbe.gitVersion(),
                        pythonVersion = null,
                        nodeVersion = null,
                        npmVersion = null,
                        shellDescription = container.runtimeProbe.shellDescription(),
                    )
                }
            }
        }
    }

    fun requestInstall(groups: Set<RuntimeGroup>) {
        _state.update { it.copy(pendingInstall = groups) }
    }

    fun cancelInstallRequest() = _state.update { it.copy(pendingInstall = null) }

    /** Performs the install the user confirmed. */
    fun confirmInstall() {
        val groups = _state.value.pendingInstall ?: return
        _state.update { it.copy(pendingInstall = null, installing = true, errorMessage = null) }
        viewModelScope.launch {
            val result = appResult {
                container.runtimeManager.install(groups) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
            }
            when (result) {
                is AppResult.Success -> {
                    _state.update { it.copy(installing = false, progress = null, message = "Runtime installed") }
                    refresh()
                }
                is AppResult.Failure -> {
                    _state.update {
                        it.copy(installing = false, progress = null, errorMessage = result.error.message)
                    }
                }
            }
        }
    }

    fun requestUninstall() = _state.update { it.copy(pendingUninstall = true) }

    fun cancelUninstall() = _state.update { it.copy(pendingUninstall = false) }

    fun confirmUninstall() {
        _state.update { it.copy(pendingUninstall = false) }
        viewModelScope.launch {
            container.runtimeManager.uninstall()
            _state.update { it.copy(message = "Userspace removed", progress = null) }
            refresh()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }
}
