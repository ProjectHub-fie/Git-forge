package com.projecthub.gitforge.terminal.runtime

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.Redaction
import com.projecthub.gitforge.terminal.TerminalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of running a native process. */
data class ProcessOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Runs a real external process and streams its output.
 *
 * Every runtime in GitForge goes through this single implementation so the
 * behaviour is identical for `git`, `python3`, `node` and `npm`:
 *  * stdout and stderr are read on separate threads, which prevents the classic
 *    deadlock where a child blocks writing to a full pipe;
 *  * the process is destroyed on coroutine cancellation, so Stop in the terminal
 *    genuinely terminates the child;
 *  * output is delivered incrementally through [onOutput] rather than buffered
 *    until exit, which is what makes long installs usable;
 *  * the environment is passed explicitly, so credentials never leak in via the
 *    inherited environment and are never echoed into the scrollback.
 */
object NativeProcess {

    suspend fun run(
        executable: File,
        args: List<String>,
        workingDirectory: File,
        environment: Map<String, String>,
        timeoutSeconds: Long = 0,
        stdin: String = "",
        stdinLimitBytes: Int = 2 * 1024 * 1024,
        outputLimitChars: Int = 2 * 1024 * 1024,
        onOutput: (String, TerminalLine.Stream) -> Unit = { _, _ -> },
    ): ProcessOutcome = withContext(Dispatchers.IO) {
        val command = ProcessInvocation.command(executable, args, android.os.Build.VERSION.SDK_INT)
        AppLog.d("exec: ${Redaction.redact(command.joinToString(" "))} (cwd=${workingDirectory.name})")

        val builder = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(false)

        val env = builder.environment()
        env.clear()
        env.putAll(environment)
        env["PWD"] = workingDirectory.absolutePath

        val process = try {
            builder.start()
        } catch (e: Exception) {
            return@withContext ProcessOutcome(
                exitCode = 127,
                stdout = "",
                stderr = "${executable.name}: cannot execute: ${Redaction.redact(e.message ?: "unknown error")}\n",
            )
        }

        // Feed pipeline stdin (already capped by the caller) then close so the
        // child sees EOF instead of hanging forever on an open pipe.
        if (stdin.isNotEmpty()) {
            val bytes = stdin.toByteArray(Charsets.UTF_8).take(stdinLimitBytes).toByteArray()
            runCatching {
                process.outputStream.use { it.write(bytes) }
            }
        } else {
            runCatching { process.outputStream.close() }
        }

        val stdoutBuilder = CappedAppend(outputLimitChars)
        val stderrBuilder = CappedAppend(outputLimitChars)
        val cancelled = AtomicBoolean(false)

        val stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        val chunk = String(buffer, 0, read)
                        stdoutBuilder.append(chunk)
                        onOutput(chunk, TerminalLine.Stream.STDOUT)
                    }
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) AppLog.w("stdout reader stopped", t)
            }
        }.apply { name = "gitforge-stdout"; isDaemon = true }

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        val chunk = String(buffer, 0, read)
                        stderrBuilder.append(chunk)
                        onOutput(chunk, TerminalLine.Stream.STDERR)
                    }
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) AppLog.w("stderr reader stopped", t)
            }
        }.apply { name = "gitforge-stderr"; isDaemon = true }

        stdoutThread.start()
        stderrThread.start()

        var timedOut = false
        try {
            val finished = if (timeoutSeconds > 0) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                // Wait in slices so coroutine cancellation is observed promptly.
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (process.waitFor(250, TimeUnit.MILLISECONDS)) break
                }
                true
            }
            if (!finished) {
                timedOut = true
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled.set(true)
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            stdoutThread.interrupt()
            stderrThread.interrupt()
            throw e
        } finally {
            stdoutThread.join(2000)
            stderrThread.join(2000)
            if (process.isAlive) process.destroyForcibly()
        }

        ProcessOutcome(
            exitCode = if (timedOut) 124 else process.exitValue(),
            stdout = stdoutBuilder.content(),
            stderr = stderrBuilder.content(),
            timedOut = timedOut || stdoutBuilder.truncated || stderrBuilder.truncated,
        )
    }

    /** Bounded appender: keeps the first [limit] chars so a runaway process cannot OOM. */
    private class CappedAppend(private val limit: Int) {
        private val sb = StringBuilder()
        var truncated = false
            private set

        @kotlin.jvm.Synchronized
        fun append(chunk: String) {
            if (sb.length >= limit) {
                truncated = true
                return
            }
            val room = limit - sb.length
            if (chunk.length > room) {
                sb.append(chunk, 0, room)
                truncated = true
            } else {
                sb.append(chunk)
            }
        }

        @kotlin.jvm.Synchronized
        fun content(): String = sb.toString()
    }

    /**
     * Runs a process and returns only its output. Used for capability probing such
     * as `node --version`, where streaming to the UI would be noise.
     */
    suspend fun capture(
        executable: File,
        args: List<String>,
        workingDirectory: File,
        environment: Map<String, String>,
        timeoutSeconds: Long = 20,
    ): ProcessOutcome = run(executable, args, workingDirectory, environment, timeoutSeconds = timeoutSeconds)
}
