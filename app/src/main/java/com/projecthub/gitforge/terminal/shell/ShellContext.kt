package com.projecthub.gitforge.terminal.shell

import com.projecthub.gitforge.core.PathSafety
import java.io.File
import java.io.IOException

/** Mutable state of one shell session: working directory, environment, history. */
class ShellContext(
    var cwd: File,
    val env: MutableMap<String, String>,
    val home: File,
    val workspaceRoot: File,
    /** Directories the shell may touch. Anything else is refused, not silently allowed. */
    val allowedRoots: List<File> = listOf(workspaceRoot, home),
) {
    var lastExitCode: Int = 0
    val history: MutableList<String> = mutableListOf()

    fun setEnv(key: String, value: String) {
        env[key] = value
    }

    /**
     * Resolves a possibly relative path against the current directory and refuses
     * anything outside [allowedRoots]. The terminal therefore cannot read or write
     * the rest of the device, which matches the app's minimal permission model.
     */
    @Throws(IOException::class)
    fun resolve(path: String): File {
        if (path.isEmpty()) return cwd
        // Only a leading `~` refers to home; a `~` elsewhere is a literal name.
        val expanded = when {
            path == "~" -> home.absolutePath
            path.startsWith("~/") -> home.absolutePath + path.removePrefix("~")
            else -> path
        }
        val file = File(expanded)
        val resolved = (if (file.isAbsolute) file else File(cwd, expanded)).canonicalFile
        if (allowedRoots.isNotEmpty() &&
            allowedRoots.none { PathSafety.isInside(it.canonicalFile, resolved) }
        ) {
            throw IOException("Refusing to access outside the GitForge workspace: $path")
        }
        return resolved
    }

    /**
     * Resolves a path for redirection, refusing to escape the workspace root.
     * A terminal must not be able to overwrite arbitrary device files, so writes
     * are confined to the app's private tree.
     */
    fun resolveForWrite(path: String): File = resolve(path)

    /** Environment map plus the shell variables the app maintains itself. */
    fun effectiveEnv(): Map<String, String> = env.toMutableMap().apply {
        put("PWD", cwd.absolutePath)
        put("HOME", home.absolutePath)
        put("OLDPWD", get("OLDPWD") ?: home.absolutePath)
        put("?", lastExitCode.toString())
    }
}

/** Result of running one command or pipeline. */
data class ShellRunOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val cancelled: Boolean = false,
)

/**
 * Expands `*`, `?` and `[...]` in unquoted arguments against the filesystem.
 * Without this, `ls *.kt` would look for a literal file named `*.kt`, which is a
 * common source of confusion in a hand-rolled shell.
 */
object GlobExpander {

    fun expand(args: List<String>, cwd: File, quoted: List<Boolean> = emptyList()): List<String> {
        val result = mutableListOf<String>()
        for ((index, arg) in args.withIndex()) {
            // Quoted words never glob; `[..]` below is documented as supported.
            if (quoted.getOrNull(index) == true ||
                (!arg.contains('*') && !arg.contains('?') && !arg.contains('['))
            ) {
                result += arg
                continue
            }
            val matches = match(arg, cwd)
            if (matches.isEmpty()) result += arg else result += matches
        }
        return result
    }

    private fun match(pattern: String, cwd: File): List<String> {
        val dirPart = pattern.substringBeforeLast('/', "")
        val namePart = pattern.substringAfterLast('/', pattern)
        val dir = if (dirPart.isEmpty()) cwd else File(cwd, dirPart)
        if (!dir.isDirectory) return emptyList()

        val regex = globToRegex(namePart)
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.name != ".git" }
            ?.filter { namePart.startsWith(".") || !it.name.startsWith(".") }
            ?.filter { regex.matches(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { if (dirPart.isEmpty()) it.name else "$dirPart/${it.name}" }
            ?.toList()
            ?: emptyList()
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var j = 0
        while (j < glob.length) {
            val c = glob[j]
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '[' -> {
                    val end = glob.indexOf(']', j + 1)
                    if (end == -1) {
                        sb.append(Regex.escape("["))
                        j++
                        continue
                    }
                    val body = glob.substring(j + 1, end)
                    val negated = body.startsWith("!") || body.startsWith("^")
                    val chars = if (negated) body.drop(1) else body
                    sb.append(if (negated) "[^" else "[")
                    for (ch in chars) sb.append(Regex.escape(ch.toString()))
                    sb.append("]")
                    j = end + 1
                    continue
                }
                '.' -> sb.append("\\.")
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append(Regex.escape(c.toString()))
            }
            j++
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}