package com.projecthub.gitforge.terminal.shell

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.terminal.TerminalLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Executes command lines.
 *
 * Design:
 *  * builtins run in-process, so `cd` and `export` genuinely persist in a session;
 *  * external commands (git, python3, node, npm) come from the runtime and stream
 *    output through [onOutput] as it arrives;
 *  * a pipeline chains stages in memory, capped at [MAX_PIPELINE_BYTES] so a
 *    runaway producer cannot exhaust memory;
 *  * the active job is retained so the UI stop button cancels a real process.
 */
class ShellEngine(
    private val builtins: BuiltinRegistry,
    private val resolver: CommandResolver,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
) {

    @Volatile
    private var activeJob: Job? = null

    val isRunning: Boolean get() = activeJob?.isActive == true

    /** Cancels the running command. Returns true when something was cancelled. */
    suspend fun cancel(): Boolean {
        val job = activeJob ?: return false
        job.cancelAndJoin()
        activeJob = null
        return true
    }

    suspend fun execute(
        ctx: ShellContext,
        commandLine: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return ShellRunOutput("", "", 0)
        if (ctx.history.lastOrNull() != trimmed) ctx.history += trimmed

        // Track the caller's own coroutine job so cancel() interrupts real work.
        activeJob = currentCoroutineContext()[Job]

        val result = try {
            val tokens = ShellLexer.tokenize(trimmed)
            val env = ctx.effectiveEnv()
            val expandedTokens = tokens.map { token ->
                if (token is Token.Word) {
                    Token.Word(
                        ShellLexer.expand(token, env, ctx.lastExitCode, ctx.home.absolutePath),
                        token.quoted,
                        token.singleQuoted,
                    )
                } else {
                    token
                }
            }

            // Redirection targets are carried as raw strings and resolved per stage
            // so `> out.txt` lands in the current directory at execution time.
            val pipeline = ShellLexer.toPipeline(expandedTokens, trimmed) { File(it) }
            runPipeline(ctx, pipeline, onOutput)
        } catch (e: ShellSyntaxException) {
            ShellRunOutput("", "shell: ${e.message}\n", 2)
        } catch (e: kotlinx.coroutines.CancellationException) {
            ShellRunOutput("", "", 130, cancelled = true)
        } catch (e: Throwable) {
            ShellRunOutput("", "shell: ${e.message ?: "unexpected failure"}\n", 1)
        } finally {
            activeJob = null
        }

        ctx.lastExitCode = result.exitCode
        return result
    }

    private suspend fun runPipeline(
        ctx: ShellContext,
        pipeline: ShellPipeline,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        var stdin = ""
        var lastResult = ShellRunOutput("", "", 0)

        pipeline.stages.forEachIndexed { index, stage ->
            currentCoroutineContext().ensureActive()
            val isLast = index == pipeline.stages.lastIndex

            // `< file` feeds the stage; a missing file is a stage error, not silence.
            var stageStdin = stdin
            var stdinError: ShellRunOutput? = null
            stage.stdinFile?.let { raw ->
                val resolved = try {
                    ctx.resolve(raw.path)
                } catch (e: IOException) {
                    stdinError = ShellRunOutput("", "shell: ${e.message}\n", 1)
                    return@let
                }
                if (!resolved.isFile) {
                    stdinError = ShellRunOutput(
                        "",
                        "shell: ${raw.path}: No such file or directory\n",
                        1,
                    )
                } else {
                    stageStdin = CappedReader.read(resolved, MAX_PIPELINE_BYTES)
                }
            }
            if (stdinError != null) {
                lastResult = applyRedirections(ctx, stage, stdinError!!)
                stdin = ""
                return@forEachIndexed
            }

            // Each stream is redirected independently, so `cmd 2> err.txt` must still
            // print stdout. Only the streams that are not redirected go to the screen.
            // Non-final stderr is forwarded live so `middle 2>err | cat` still shows errors.
            val stdoutToTerminal = isLast && stage.stdoutFile == null
            val stderrToTerminal = stage.stderrFile == null
            val emit: (String, TerminalLine.Stream) -> Unit = { chunk, stream ->
                val allowed = when (stream) {
                    TerminalLine.Stream.STDERR -> stderrToTerminal
                    else -> stdoutToTerminal
                }
                if (allowed) onOutput(chunk, stream)
            }

            val outcome = runStage(ctx, stage, stageStdin, emit)
            val result = applyRedirections(ctx, stage, outcome.result)

            // Builtins run in-process and return their stdout/stderr instead of
            // streaming them, so without this their output would never be shown. The
            // streams that were redirected are already empty in [result].
            if (!outcome.streamedLive) {
                if (stdoutToTerminal && result.stdout.isNotEmpty()) {
                    onOutput(result.stdout, TerminalLine.Stream.STDOUT)
                }
                if (stderrToTerminal && result.stderr.isNotEmpty()) {
                    onOutput(result.stderr, TerminalLine.Stream.STDERR)
                }
            }

            val (cappedOut, outTruncated) = StreamLimiter.limit(result.stdout)
            val (cappedErr, errTruncated) = StreamLimiter.limit(result.stderr)
            if ((outTruncated || errTruncated) && isLast) {
                onOutput("\nshell: pipeline output truncated at 2 MB\n", TerminalLine.Stream.SYSTEM)
            }
            lastResult = result.copy(stdout = cappedOut, stderr = cappedErr)
            stdin = cappedOut
        }
        return lastResult
    }

    /** A stage's result plus whether its output already reached the terminal live. */
    private data class StageOutcome(val result: ShellRunOutput, val streamedLive: Boolean)

    private suspend fun runStage(
        ctx: ShellContext,
        stage: ShellStage,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): StageOutcome {
        if (stage.words.isEmpty()) return StageOutcome(ShellRunOutput("", "", 0), streamedLive = false)

        val words = expandGlobs(stage.words, ctx.cwd, stage.quoted)
        val aliased = resolver.resolveAlias(words)
        val name = aliased.first()
        val args = aliased.drop(1)

        builtins.find(name)?.let { builtin ->
            val result = try {
                builtin.run(ctx, args, stdin)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                // Path confinement and missing operands surface here as a normal
                // command error rather than an unhandled failure.
                ShellRunOutput("", "$name: ${e.message ?: "I/O error"}\n", 1)
            }
            return StageOutcome(result, streamedLive = false)
        }

        val external = resolver.external(name)
        if (external != null) {
            if (!external.available) {
                return StageOutcome(
                    ShellRunOutput("", "$name: ${external.unavailableReason ?: "not installed"}\n", 127),
                    streamedLive = false,
                )
            }
            val result = try {
                external.execute(ctx, args, stdin, onOutput)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("External command $name failed", e)
                ShellRunOutput("", "$name: ${e.message ?: "execution failed"}\n", 1)
            }
            return StageOutcome(result, streamedLive = true)
        }

        val hint = if (name.contains('/')) {
            "no such file or directory"
        } else {
            "command not found. Type 'help' for the built-in list, or open Packages to see installed runtimes."
        }
        return StageOutcome(ShellRunOutput("", "$name: $hint\n", 127), streamedLive = false)
    }

    /** Expands `*`/`?`/`[...]` in unquoted words against the working directory. */
    private fun expandGlobs(words: List<String>, cwd: File, quoted: List<Boolean> = emptyList()): List<String> =
        GlobExpander.expand(words, cwd, quoted)

    /** Applies `>` `>>` `2>` `2>>` for a stage, clearing only the streams it wrote. */
    private fun applyRedirections(
        ctx: ShellContext,
        stage: ShellStage,
        result: ShellRunOutput,
    ): ShellRunOutput {
        var stdout = result.stdout
        var stderr = result.stderr

        stage.stdoutFile?.let { target ->
            val resolved = try {
                ctx.resolveForWrite(target.path)
            } catch (e: IOException) {
                return ShellRunOutput(result.stdout, "shell: ${e.message}\n", 1)
            }
            resolved.parentFile?.mkdirs()
            if (stage.stdoutAppend) resolved.appendText(result.stdout) else resolved.writeText(result.stdout)
            // The text now lives in the file, so it must not also be printed.
            stdout = ""
        }

        stage.stderrFile?.let { target ->
            val resolved = try {
                ctx.resolveForWrite(target.path)
            } catch (e: IOException) {
                return ShellRunOutput(stdout, "shell: ${e.message}\n", 1)
            }
            resolved.parentFile?.mkdirs()
            if (stage.stderrAppend) resolved.appendText(result.stderr) else resolved.writeText(result.stderr)
            stderr = ""
        }

        return result.copy(stdout = stdout, stderr = stderr)
    }

    companion object {
        const val MAX_PIPELINE_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Truncates stream text so a runaway pipeline cannot exhaust memory. The caller is
 * told truncation happened so the UI can say so rather than silently losing data.
 */
object StreamLimiter {
    fun limit(text: String, limit: Int = ShellEngine.MAX_PIPELINE_BYTES): Pair<String, Boolean> =
        if (text.length <= limit) text to false else text.take(limit) to true
}

/** Reads stdin files with the same 2 MB cap as pipeline output. */
internal object CappedReader {
    fun read(file: File, limit: Int = ShellEngine.MAX_PIPELINE_BYTES): String {
        // Read incrementally so a huge file never materialises fully in memory.
        val sb = StringBuilder()
        file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(8192)
            var remaining = limit
            while (remaining > 0) {
                val read = reader.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                sb.append(buf, 0, read)
                remaining -= read
            }
        }
        return sb.toString()
    }
}
