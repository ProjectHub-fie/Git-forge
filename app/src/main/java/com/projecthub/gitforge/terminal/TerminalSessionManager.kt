package com.projecthub.gitforge.terminal

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.terminal.shell.BuiltinRegistry
import com.projecthub.gitforge.terminal.shell.CommandResolver
import com.projecthub.gitforge.terminal.shell.ShellContext
import com.projecthub.gitforge.terminal.shell.ShellEngine
import com.projecthub.gitforge.terminal.shell.ShellRunOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** A live terminal session: its state, its output buffer and its shell context. */
class TerminalSession(
    val id: Long,
    name: String,
    val context: ShellContext,
    private val engine: ShellEngine,
    private val parser: AnsiParser,
    private val scrollbackLimit: Int,
) {
    private val _state = MutableStateFlow(
        TerminalSessionState(
            id = id,
            name = name,
            state = SessionState.IDLE,
            workingDirectory = context.cwd.absolutePath,
            lastExitCode = null,
            runtimeLabel = "",
        ),
    )
    val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val _output = MutableStateFlow<List<TerminalLine>>(emptyList())
    val output: StateFlow<List<TerminalLine>> = _output.asStateFlow()

    private var carry = ""
    private var currentStyle = AnsiStyle.NONE
    private var buffer = mutableListOf<TerminalLine>()

    /** Appends already-parsed output. Bounded so a long session cannot exhaust memory. */
    fun append(lines: List<TerminalLine>) {
        if (lines.isEmpty()) return
        buffer.addAll(lines)
        val overflow = buffer.size - scrollbackLimit
        if (overflow > 0) buffer = buffer.drop(overflow).toMutableList()
        _output.value = buffer.toList()
    }

    @kotlin.jvm.Synchronized
    fun appendText(text: String, stream: TerminalLine.Stream) {
        if (text.isEmpty()) return
        val outcome = parser.parse(text, carry)
        carry = outcome.carry
        currentStyle = outcome.style
        val lines = outcome.lines.map { (lineText, style) -> TerminalLine(lineText, stream, style) }
        append(lines)
        if (carry.isNotEmpty()) {
            // Show the partial line so interactive output appears promptly; it is
            // replaced as soon as the line is completed.
            _output.update { existing ->
                val withoutTail = existing.filterNot { it.text.startsWith(TAIL_MARKER) }
                (withoutTail + TerminalLine("$TAIL_MARKER$carry", stream, currentStyle)).takeLast(scrollbackLimit)
            }
        }
    }

    fun clear() {
        buffer = mutableListOf()
        carry = ""
        _output.value = emptyList()
    }

    fun rename(newName: String) {
        _state.update { it.copy(name = newName) }
    }

    fun refreshWorkingDirectory() {
        _state.update { it.copy(workingDirectory = context.cwd.absolutePath) }
    }

    suspend fun run(commandLine: String): CommandResult {
        val started = System.currentTimeMillis()
        _state.update { it.copy(state = SessionState.RUNNING) }
        appendText("$ ${commandLine}\n", TerminalLine.Stream.COMMAND)

        val result: ShellRunOutput = engine.execute(context, commandLine) { chunk, stream ->
            appendText(chunk, stream)
        }

        // Flush any partial line so the next prompt starts cleanly.
        if (carry.isNotEmpty()) {
            appendText("\n", TerminalLine.Stream.STDOUT)
            carry = ""
        }

        val exitCode = result.exitCode
        _state.update {
            it.copy(
                state = if (result.cancelled) SessionState.CANCELLED else SessionState.IDLE,
                workingDirectory = context.cwd.absolutePath,
                lastExitCode = exitCode,
            )
        }

        val duration = System.currentTimeMillis() - started
        val exitLine = TerminalLine(
            "exit $exitCode  (${duration}ms)",
            if (exitCode == 0) TerminalLine.Stream.SYSTEM else TerminalLine.Stream.STDERR,
        )
        append(listOf(exitLine))

        return CommandResult(
            command = commandLine,
            exitCode = exitCode,
            output = buffer.toList(),
            durationMs = duration,
            cancelled = result.cancelled,
        )
    }

    suspend fun cancel(): Boolean = engine.cancel()

    val isRunning: Boolean get() = engine.isRunning

    /** Plain text of the whole buffer, for copy-to-clipboard. */
    fun asPlainText(): String = buffer.joinToString("\n") {
        it.text.removePrefix(TAIL_MARKER)
    }

    companion object {
        private const val TAIL_MARKER = "\u0000TAIL\u0000"
    }
}

/**
 * Creates and tracks terminal sessions.
 *
 * Sessions survive navigation because they are held here rather than in a screen's
 * ViewModel, so switching to the editor and back does not lose output or a running
 * command.
 */
class TerminalSessionManager(
    private val workspaceRoot: File,
    private val homeDir: File,
    private val runtimeEnvironment: () -> Map<String, String>,
    private val runtimeLabel: () -> String,
    private val scrollbackLimit: () -> Int,
    private val scope: CoroutineScope,
) {

    private val nextId = java.util.concurrent.atomic.AtomicLong(1)
    private val sessions = linkedMapOf<Long, TerminalSession>()

    private val _sessionIds = MutableStateFlow<List<Long>>(emptyList())
    val sessionIds: StateFlow<List<Long>> = _sessionIds.asStateFlow()

    private val _activeId = MutableStateFlow(-1L)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    private fun buildEngine(): ShellEngine {
        // The registry is built with a `which` resolver that knows about the runtimes,
        // so `which node` reports the truth.
        lateinit var resolver: CommandResolver
        val builtins = BuiltinRegistry(describeCommand = { resolver.describeForWhich(it) })
        resolver = CommandResolver(builtins, runtimeCommands)
        return ShellEngine(builtins, resolver, scope)
    }

    /** Populated by the dependency container before the first session is created. */
    var runtimeCommands: List<com.projecthub.gitforge.terminal.shell.ExternalCommand> = emptyList()

    fun createSession(name: String? = null, repositoryRoot: File? = null): TerminalSession {
        val id = nextId.getAndIncrement()
        val startDir = repositoryRoot?.takeIf { it.isDirectory } ?: workspaceRoot
        val environment = runtimeEnvironment().toMutableMap()
        val context = ShellContext(
            cwd = startDir,
            env = environment,
            home = homeDir,
            workspaceRoot = workspaceRoot,
        )
        val session = TerminalSession(
            id = id,
            name = name ?: "Session $id",
            context = context,
            engine = buildEngine(),
            // Each session gets its own parser: AnsiParser keeps style/carry
            // state and is fed from stdout/stderr reader threads, so sharing one
            // across sessions corrupts colours and races on style fields.
            parser = AnsiParser(),
            scrollbackLimit = scrollbackLimit(),
        )
        sessions[id] = session
        _sessionIds.value = sessions.keys.toList()
        _activeId.value = id
        AppLog.i("Created terminal session $id in ${startDir.name}")
        return session
    }

    fun session(id: Long): TerminalSession? = sessions[id]

    fun active(): TerminalSession? = sessions[_activeId.value]

    fun select(id: Long) {
        if (sessions.containsKey(id)) _activeId.value = id
    }

    fun close(id: Long) {
        val session = sessions.remove(id) ?: return
        // Cancel first: closing during `npm install` must kill the child
        // process instead of leaking it after the UI is gone. The kill runs off
        // the main thread because a process that ignores termination is waited
        // on for up to three seconds, and close() comes from a UI click.
        scope.launch { runCatching { session.cancel() } }
        session.clear()
        _sessionIds.value = sessions.keys.toList()
        if (_activeId.value == id) _activeId.value = sessions.keys.firstOrNull() ?: -1L
        AppLog.i("Closed terminal session $id")
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    fun runtimeEnvironmentForSession(): Map<String, String> = runtimeEnvironment()

    val workspace: File get() = workspaceRoot
}
