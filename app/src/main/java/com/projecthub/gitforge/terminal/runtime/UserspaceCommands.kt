package com.projecthub.gitforge.terminal.runtime

import com.projecthub.gitforge.core.Redaction
import com.projecthub.gitforge.terminal.TerminalLine
import com.projecthub.gitforge.terminal.shell.ExternalCommand
import com.projecthub.gitforge.terminal.shell.ShellContext
import com.projecthub.gitforge.terminal.shell.ShellRunOutput
import java.io.File

/**
 * Base class for the interpreters GitForge runs out of the installed userspace.
 *
 * All of them share the same contract: report honestly whether the runtime is
 * present, run the real binary, stream its output, and never invent a result.
 */
abstract class UserspaceCommand(
    protected val runtimeManager: RuntimeManager,
) : ExternalCommand {

    protected val installation get() = runtimeManager.installation()

    /** Absolute path of the interpreter, or null when it is not installed. */
    protected abstract fun executablePath(): File?

    override val available: Boolean get() = executablePath() != null

    override val unavailableReason: String?
        get() = if (available) null else missingMessage()

    protected abstract fun missingMessage(): String

    protected suspend fun runProcess(
        ctx: ShellContext,
        args: List<String>,
        onOutput: (String, TerminalLine.Stream) -> Unit,
        extraEnv: Map<String, String> = emptyMap(),
        stdin: String = "",
    ): ShellRunOutput {
        val executable = executablePath()
            ?: return ShellRunOutput("", "${name}: ${missingMessage()}\n", 127)

        val environment = runtimeManager.environmentFor(ctx.effectiveEnv() + extraEnv)
        val outcome = NativeProcess.run(
            executable = executable,
            args = args,
            workingDirectory = ctx.cwd,
            environment = environment,
            stdin = stdin,
            onOutput = onOutput,
        )

        if (outcome.timedOut) {
            onOutput("\n$name: terminated after the time limit\n", TerminalLine.Stream.STDERR)
        }
        return ShellRunOutput(outcome.stdout, outcome.stderr, outcome.exitCode)
    }
}

/**
 * `python3` from the installed userspace.
 *
 * `python` is aliased to this by [com.projecthub.gitforge.terminal.shell.CommandResolver]
 * so both spellings work. The working directory is the session's directory, so
 * `python script.py` and relative imports behave the way a developer expects.
 */
class PythonCommand(runtimeManager: RuntimeManager) : UserspaceCommand(runtimeManager) {

    override val name = "python3"
    override val summary = "CPython 3 from the installed userspace"

    override fun executablePath(): File? = installation.executable("python3")

    override fun missingMessage() =
        "Python 3 is not installed. Open Packages and install the Python runtime."

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        // Keep the bytecode cache inside app-private storage; Android's /tmp is
        // not writable by the app.
        val cacheDir = File(runtimeManager.prefix, ".python-cache").apply { mkdirs() }
        val environment = mapOf(
            "PYTHONPYCACHEPREFIX" to cacheDir.absolutePath,
            "PYTHONDONTWRITEBYTECODE" to "0",
        )
        return runProcess(ctx, args, onOutput, environment, stdin)
    }
}

/** `pip`, the Python package manager shipped with the userspace. */
class PipCommand(runtimeManager: RuntimeManager) : UserspaceCommand(runtimeManager) {

    override val name = "pip"
    override val summary = "Python package manager"

    override fun executablePath(): File? = installation.executable("pip")

    override fun missingMessage() =
        "pip is not installed. Open Packages and install the Python runtime."

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        // Pure-Python wheels work on Android; anything needing a native build does
        // not, because there is no compiler in this userspace. Point pip at the
        // Termux package index first, which serves prebuilt Android wheels.
        val environment = mapOf(
            "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
            "PIP_NO_INPUT" to "1",
        )
        return runProcess(ctx, args, onOutput, environment, stdin)
    }
}

/**
 * `node` from the installed userspace.
 *
 * Runs real JavaScript: `node script.js`, `node -e "..."`, `node --version`.
 * The module resolution paths are left at their defaults so `require` and
 * `node_modules` behave exactly as they do on any other platform.
 */
class NodeCommand(runtimeManager: RuntimeManager) : UserspaceCommand(runtimeManager) {

    override val name = "node"
    override val summary = "Node.js JavaScript runtime"

    override fun executablePath(): File? = installation.executable("node")

    override fun missingMessage() =
        "Node.js is not installed. Open Packages and install the Node runtime."

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        val environment = mapOf(
            // Keep Node's heap modest: a phone has far less RAM than a desktop, and
            // an unbounded V8 heap is the quickest way to be killed by the OS.
            "NODE_OPTIONS" to "--max-old-space-size=512",
        )
        return runProcess(ctx, args, onOutput, environment, stdin)
    }
}

/**
 * `npm`, run as `node <prefix>/lib/node_modules/npm/bin/npm-cli.js`.
 *
 * That is exactly how npm is invoked on every platform, which is why this works
 * without a wrapper script or a shell.
 *
 * Safety: npm runs dependency lifecycle scripts (`postinstall`) by default, and a
 * cloned repository is untrusted input. [install] therefore refuses to run until
 * the caller has confirmed, and supports `--ignore-scripts` for a safe install.
 */
class NpmCommand(runtimeManager: RuntimeManager) : UserspaceCommand(runtimeManager) {

    override val name = "npm"
    override val summary = "npm package manager"

    private val cliJs: File get() = File(runtimeManager.prefix, "lib/node_modules/npm/bin/npm-cli.js")

    override fun executablePath(): File? =
        if (cliJs.exists() && installation.executable("node") != null) {
            installation.executable("node")
        } else {
            null
        }

    override fun missingMessage() =
        "npm is not installed. Open Packages and install the Node runtime."

    /** True when the command line would run third-party lifecycle scripts. */
    fun runsLifecycleScripts(args: List<String>): Boolean {
        if (args.any { it == "--ignore-scripts" }) return false
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return false
        return sub in setOf("install", "i", "ci", "add", "update", "rebuild", "link")
    }

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        val node = installation.executable("node")
            ?: return ShellRunOutput("", "npm: ${missingMessage()}\n", 127)

        if (!cliJs.exists()) {
            return ShellRunOutput(
                "",
                "npm: the npm CLI was not found at lib/node_modules/npm/bin/npm-cli.js. Reinstall the Node runtime from Packages.\n",
                127,
            )
        }

        // npm writes into node_modules in the project directory, which is what makes
        // the resulting changes show up in Git status.
        val environment = runtimeManager.environmentFor(ctx.effectiveEnv())
            .toMutableMap()
            .apply {
                put("NPM_CONFIG_PREFIX", runtimeManager.prefix.absolutePath)
                put("NPM_CONFIG_CACHE", File(runtimeManager.prefix, ".npm-cache").absolutePath)
                put("NPM_CONFIG_USERCONFIG", File(runtimeManager.prefix, "etc/npmrc").absolutePath)
                put("npm_config_user_agent", "gitforge/1.0 node/${nodeVersionHint()}")
            }

        val outcome = NativeProcess.run(
            executable = node,
            args = listOf(cliJs.absolutePath) + args,
            workingDirectory = ctx.cwd,
            environment = environment,
            stdin = stdin,
            onOutput = onOutput,
        )

        if (outcome.timedOut) {
            onOutput("\nnpm: terminated after the time limit\n", TerminalLine.Stream.STDERR)
        }
        if (outcome.exitCode != 0 && outcome.stderr.isBlank() && outcome.stdout.isBlank()) {
            onOutput(
                "npm: the command failed with exit code ${outcome.exitCode}. " +
                    "Check the network connection and that package.json is valid.\n",
                TerminalLine.Stream.STDERR,
            )
        }
        return ShellRunOutput(outcome.stdout, outcome.stderr, outcome.exitCode)
    }

    private fun nodeVersionHint(): String = runCatching {
        File(runtimeManager.prefix, "bin/node").name
    }.getOrDefault("node")
}

/** `bash` is not shipped; this reports that clearly instead of failing obscurely. */
class ShellInterpreterCommand(runtimeManager: RuntimeManager) : UserspaceCommand(runtimeManager) {

    override val name = "bash"
    override val summary = "Not provided: GitForge implements its own shell"

    override fun executablePath(): File? = null

    override fun missingMessage() =
        "GitForge runs its own shell implementation, which already provides cd, ls, grep, " +
            "sed, awk and the other commands you need. A second bash is not installed."

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput = ShellRunOutput("", "bash: ${missingMessage()}\n", 127)
}

/** Reports the userspace versions, used by the Packages screen. */
class RuntimeProbe(private val runtimeManager: RuntimeManager) {

    suspend fun gitVersion(): String = "JGit 6.7.0 (bundled, pure Java)"

    suspend fun pythonVersion(): String? = probe("python3", listOf("--version"))

    suspend fun nodeVersion(): String? = probe("node", listOf("--version"))

    suspend fun npmVersion(): String? = probeNpm()

    suspend fun shellDescription(): String = "GitForge shell (built in)"

    private suspend fun probe(command: String, args: List<String>): String? {
        val installation = runtimeManager.installation()
        val executable = installation.executable(command) ?: return null
        val outcome = NativeProcess.capture(
            executable = executable,
            args = args,
            workingDirectory = runtimeManager.runtimeRoot,
            environment = runtimeManager.environmentFor(emptyMap()),
            timeoutSeconds = 15,
        )
        val text = (outcome.stdout + outcome.stderr).trim().lines().firstOrNull()
        return if (outcome.succeeded && !text.isNullOrBlank()) Redaction.redact(text) else null
    }

    private suspend fun probeNpm(): String? {
        val installation = runtimeManager.installation()
        val node = installation.executable("node") ?: return null
        val cli = File(runtimeManager.prefix, "lib/node_modules/npm/bin/npm-cli.js")
        if (!cli.exists()) return null
        val outcome = NativeProcess.capture(
            executable = node,
            args = listOf(cli.absolutePath, "--version"),
            workingDirectory = runtimeManager.runtimeRoot,
            environment = runtimeManager.environmentFor(emptyMap()),
            timeoutSeconds = 25,
        )
        val text = (outcome.stdout + outcome.stderr).trim().lines().firstOrNull()
        return if (outcome.succeeded && !text.isNullOrBlank()) Redaction.redact(text) else null
    }
}
