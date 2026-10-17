package com.projecthub.gitforge.terminal.shell

import java.io.File
import java.io.IOException

/** A shell builtin. Builtins run in-process and can therefore change [ShellContext]. */
interface BuiltinCommand {
    val name: String
    val summary: String
    suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput
}

/**
 * @param describeCommand resolves a command name to a location string for `which`,
 *   or null when the command is unknown. Injected so the registry stays free of a
 *   dependency on the runtime layer.
 */
class BuiltinRegistry(
    extra: List<BuiltinCommand> = emptyList(),
    describeCommand: (String) -> String? = { null },
) {

    private val whichCommand = object : BuiltinCommand {
        override val name = "which"
        override val summary = "Locate a command"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            if (args.isEmpty()) return BuiltinRegistry.err("which: missing argument")
            val out = StringBuilder()
            var code = 0
            for (name in args) {
                val location = describeCommand(name)
                if (location == null) {
                    code = 1
                    out.append("which: no $name in (builtins, runtimes)\n")
                } else {
                    out.append(location).append('\n')
                }
            }
            return ShellRunOutput(out.toString(), "", code)
        }
    }

    private val commands: Map<String, BuiltinCommand> =
        (CORE_BUILTINS + TEXT_BUILTINS + extra + whichCommand).associateBy { it.name }

    fun find(name: String): BuiltinCommand? = commands[name]

    fun all(): List<BuiltinCommand> = commands.values.sortedBy { it.name }

    fun names(): List<String> = commands.keys.sorted()

    companion object {
        /** Helpers shared by the builtin implementations. */
        internal fun ok(stdout: String = "") = ShellRunOutput(stdout, "", 0)

        internal fun err(message: String, code: Int = 1) =
            ShellRunOutput("", message.ensureTrailingNewline(), code)

        internal fun String.ensureTrailingNewline(): String =
            if (isEmpty() || endsWith("\n")) this else "$this\n"

        /** Upper bound for a single builtin file read so `cat hugefile` cannot OOM. */
        internal const val MAX_INPUT_BYTES = 2 * 1024 * 1024

        internal fun readInput(
            ctx: ShellContext,
            args: List<String>,
            stdin: String,
            limit: Int = MAX_INPUT_BYTES,
        ): String {
            val fileArgs = fileOperands(args)
            if (fileArgs.isEmpty()) return stdin.take(limit)
            val sb = StringBuilder()
            var remaining = limit
            for (path in fileArgs) {
                val file = ctx.resolve(path)
                if (!file.exists()) throw IOException("$path: No such file or directory")
                if (file.isDirectory) throw IOException("$path: Is a directory")
                file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(8192)
                    while (remaining > 0) {
                        val read = reader.read(buf, 0, minOf(buf.size, remaining))
                        if (read <= 0) break
                        sb.append(buf, 0, read)
                        remaining -= read
                    }
                }
                if (remaining <= 0) break
            }
            return sb.toString()
        }

        /** Options that consume the following argument as their value. */
        private val OPTIONS_WITH_VALUES =
            setOf("-n", "-c", "-d", "-f", "-e", "-k", "-m", "-o", "-s", "-t", "-C")

        /**
         * The positional operands of a command, with options and their values removed.
         * Passing the raw argument list to a file reader would otherwise make
         * `head -n 2 file` try to open a file called "2".
         */
        internal fun fileOperands(args: List<String>): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "--" -> {
                        i++
                        while (i < args.size) { result += args[i]; i++ }
                    }
                    arg.length > 1 && arg.startsWith("-") -> {
                        i += if (arg in OPTIONS_WITH_VALUES && i + 1 < args.size) 2 else 1
                    }
                    else -> { result += arg; i++ }
                }
            }
            return result
        }

        internal fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes"
            bytes < 1024 * 1024 -> "%.1fK".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1fM".format(bytes / (1024.0 * 1024))
            else -> "%.1fG".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

// --------------------------------------------------------------------- core set

private val CORE_BUILTINS: List<BuiltinCommand> = listOf(
    object : BuiltinCommand {
        override val name = "pwd"
        override val summary = "Print the current working directory"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String) =
            BuiltinRegistry.ok(ctx.cwd.absolutePath + "\n")
    },

    object : BuiltinCommand {
        override val name = "cd"
        override val summary = "Change the current working directory"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val target = when {
                args.isEmpty() -> ctx.home
                args[0] == "-" -> ctx.resolve(ctx.env["OLDPWD"] ?: ctx.home.absolutePath)
                else -> ctx.resolve(args[0])
            }
            if (!target.exists()) return BuiltinRegistry.err("cd: ${args.firstOrNull()}: No such file or directory")
            if (!target.isDirectory) return BuiltinRegistry.err("cd: ${args.firstOrNull()}: Not a directory")
            ctx.env["OLDPWD"] = ctx.cwd.absolutePath
            ctx.cwd = target.canonicalFile
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "ls"
        override val summary = "List directory contents (-l long, -a all, -R recursive, -h sizes)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val long = args.any { it.startsWith("-") && !it.startsWith("--") && it.contains('l') }
            val showAll = args.any { it.startsWith("-") && !it.startsWith("--") && it.contains('a') }
            val recursive = args.any { it.startsWith("-") && !it.startsWith("--") && it.contains('R') }
            val targets = args.filterNot { it.startsWith("-") }.ifEmpty { listOf(".") }

            val out = StringBuilder()
            for (target in targets) {
                val dir = ctx.resolve(target)
                if (!dir.exists()) return BuiltinRegistry.err("ls: $target: No such file or directory")
                if (dir.isFile) {
                    out.append(if (long) longLine(dir) else dir.name).append('\n')
                    continue
                }
                if (recursive) {
                    walk(dir, "", long, showAll, out)
                } else {
                    out.append(renderDirectory(dir, long, showAll))
                }
            }
            return BuiltinRegistry.ok(out.toString())
        }

        private fun renderDirectory(dir: File, long: Boolean, showAll: Boolean): String {
            val entries = dir.listFiles()?.toList().orEmpty()
                .filter { showAll || !it.name.startsWith(".") }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            val sb = StringBuilder()
            for (entry in entries) {
                sb.append(if (long) longLine(entry) else entry.name + (if (entry.isDirectory) "/" else "")).append('\n')
            }
            return sb.toString()
        }

        private fun walk(dir: File, prefix: String, long: Boolean, showAll: Boolean, out: StringBuilder) {
            val label = if (prefix.isEmpty()) "." else prefix
            out.append(label).append(":\n")
            out.append(renderDirectory(dir, long, showAll))
            val subdirs = dir.listFiles()?.filter { it.isDirectory && it.name != ".git" && (showAll || !it.name.startsWith(".")) }
                .orEmpty()
            for (sub in subdirs) {
                out.append('\n')
                walk(sub, "$prefix${sub.name}/", long, showAll, out)
            }
        }

        private fun longLine(file: File): String {
            val perms = buildString {
                append(if (file.isDirectory) 'd' else '-')
                append(if (file.canRead()) 'r' else '-')
                append(if (file.canWrite()) 'w' else '-')
                append(if (file.canExecute()) 'x' else '-')
                repeat(6) { append('-') }
            }
            val size = BuiltinRegistry.humanSize(if (file.isFile) file.length() else 0)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(file.lastModified()))
            return "%s %8s %s %s".format(perms, size, stamp, file.name)
        }
    },

    object : BuiltinCommand {
        override val name = "cat"
        override val summary = "Print file contents (-n number lines)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val number = args.any { it == "-n" }
            return try {
                val content = BuiltinRegistry.readInput(ctx, args, stdin)
                if (!number) {
                    BuiltinRegistry.ok(content)
                } else {
                    val numbered = content.split('\n').mapIndexed { index, line ->
                        "%6d\t%s".format(index + 1, line)
                    }.joinToString("\n")
                    BuiltinRegistry.ok(numbered)
                }
            } catch (e: IOException) {
                BuiltinRegistry.err("cat: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "mkdir"
        override val summary = "Create directories (-p create parents)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val parents = args.any { it == "-p" }
            val targets = args.filterNot { it.startsWith("-") }
            if (targets.isEmpty()) return BuiltinRegistry.err("mkdir: missing operand")
            for (target in targets) {
                val dir = ctx.resolve(target)
                val created = if (parents) dir.mkdirs() else dir.mkdir()
                if (!created && !dir.isDirectory) {
                    return BuiltinRegistry.err("mkdir: cannot create directory '$target'")
                }
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "touch"
        override val summary = "Create empty files or update timestamps"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val targets = args.filterNot { it.startsWith("-") }
            if (targets.isEmpty()) return BuiltinRegistry.err("touch: missing operand")
            for (target in targets) {
                val file = ctx.resolve(target)
                if (file.exists()) {
                    file.setLastModified(System.currentTimeMillis())
                } else {
                    file.parentFile?.mkdirs()
                    if (!file.createNewFile()) return BuiltinRegistry.err("touch: cannot create '$target'")
                }
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "rm"
        override val summary = "Remove files or directories (-r recursive, -f force)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val recursive = args.any { it.startsWith("-") && it.contains('r') }
            val force = args.any { it.startsWith("-") && it.contains('f') }
            val targets = args.filterNot { it.startsWith("-") }
            if (targets.isEmpty()) return if (force) BuiltinRegistry.ok() else BuiltinRegistry.err("rm: missing operand")

            for (target in targets) {
                val file = try {
                    ctx.resolveForWrite(target)
                } catch (e: IOException) {
                    return BuiltinRegistry.err("rm: ${e.message}")
                }
                if (!file.exists()) {
                    if (!force) return BuiltinRegistry.err("rm: cannot remove '$target': No such file or directory")
                    continue
                }
                if (file.isDirectory && !recursive) {
                    return BuiltinRegistry.err("rm: cannot remove '$target': Is a directory (use -r)")
                }
                val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (!removed && !force) return BuiltinRegistry.err("rm: cannot remove '$target'")
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "cp"
        override val summary = "Copy files (-r recursive)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val recursive = args.any { it.startsWith("-") && it.contains('r') }
            val positional = args.filterNot { it.startsWith("-") }
            if (positional.size < 2) return BuiltinRegistry.err("cp: missing destination operand")
            val destination = ctx.resolve(positional.last())
            val sources = positional.dropLast(1)

            for (source in sources) {
                val from = ctx.resolve(source)
                if (!from.exists()) return BuiltinRegistry.err("cp: cannot stat '$source': No such file or directory")
                if (from.isDirectory && !recursive) {
                    return BuiltinRegistry.err("cp: -r not specified; omitting directory '$source'")
                }
                val to = if (destination.isDirectory) File(destination, from.name) else destination
                try {
                    if (from.isDirectory) from.copyRecursively(to, overwrite = true)
                    else {
                        to.parentFile?.mkdirs()
                        from.copyTo(to, overwrite = true)
                    }
                } catch (e: IOException) {
                    return BuiltinRegistry.err("cp: ${e.message}")
                }
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "mv"
        override val summary = "Move or rename files"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val positional = args.filterNot { it.startsWith("-") }
            if (positional.size < 2) return BuiltinRegistry.err("mv: missing destination operand")
            val destination = ctx.resolve(positional.last())
            for (source in positional.dropLast(1)) {
                val from = ctx.resolve(source)
                if (!from.exists()) return BuiltinRegistry.err("mv: cannot stat '$source': No such file or directory")
                val to = if (destination.isDirectory) File(destination, from.name) else destination
                to.parentFile?.mkdirs()
                if (to.exists()) {
                    if (to.isDirectory) to.deleteRecursively() else to.delete()
                }
                if (!from.renameTo(to)) {
                    // Cross-directory rename can fail; fall back to copy then delete.
                    try {
                        if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
                        from.deleteRecursively()
                    } catch (e: IOException) {
                        return BuiltinRegistry.err("mv: ${e.message}")
                    }
                }
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "echo"
        override val summary = "Print arguments (-n no trailing newline)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val noNewline = args.firstOrNull() == "-n"
            val text = (if (noNewline) args.drop(1) else args).joinToString(" ")
            return BuiltinRegistry.ok(if (noNewline) text else "$text\n")
        }
    },

    object : BuiltinCommand {
        override val name = "printf"
        override val summary = "Print formatted output"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            if (args.isEmpty()) return BuiltinRegistry.err("printf: missing format")
            val format = args[0]
            val rest = args.drop(1)
            val expanded = format.replace("\\n", "\n").replace("\\t", "\t")
            val result = if (rest.isEmpty()) expanded else runCatching { expanded.format(*rest.toTypedArray()) }.getOrDefault(expanded)
            return BuiltinRegistry.ok(result)
        }
    },

    object : BuiltinCommand {
        override val name = "env"
        override val summary = "Show or set environment variables"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            if (args.isEmpty()) {
                val text = ctx.effectiveEnv().entries.sortedBy { it.key }
                    .joinToString("\n") { "${it.key}=${it.value}" }
                return BuiltinRegistry.ok(text + "\n")
            }
            // `env NAME=value command` runs the command with a temporary override.
            val assignments = args.takeWhile { it.contains('=') }
            if (assignments.isEmpty()) {
                val text = ctx.effectiveEnv().entries.sortedBy { it.key }
                    .joinToString("\n") { "${it.key}=${it.value}" }
                return BuiltinRegistry.ok(text + "\n")
            }
            val previous = mutableMapOf<String, String?>()
            assignments.forEach { assignment ->
                val key = assignment.substringBefore('=')
                previous[key] = ctx.env[key]
                ctx.env[key] = assignment.substringAfter('=')
            }
            try {
                if (args.size == assignments.size) {
                    return BuiltinRegistry.ok(assignments.joinToString("\n") + "\n")
                }
                return BuiltinRegistry.err("env: running a command with a temporary environment is not supported; use export instead")
            } finally {
                // `env A=1 cmd` must not leak overrides into the session.
                previous.forEach { (key, value) ->
                    if (value == null) ctx.env.remove(key) else ctx.env[key] = value
                }
            }
        }
    },

    object : BuiltinCommand {
        override val name = "export"
        override val summary = "Set an environment variable for this session"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            if (args.isEmpty()) {
                val text = ctx.effectiveEnv().entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
                return BuiltinRegistry.ok(text + "\n")
            }
            for (assignment in args) {
                val key = assignment.substringBefore('=')
                if (!key.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*"""))) {
                    return BuiltinRegistry.err("export: '$key' is not a valid variable name")
                }
                ctx.env[key] = assignment.substringAfter('=', "")
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "unset"
        override val summary = "Remove an environment variable"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            args.forEach { ctx.env.remove(it) }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "true"
        override val summary = "Return a successful exit code"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String) = BuiltinRegistry.ok()
    },

    object : BuiltinCommand {
        override val name = "false"
        override val summary = "Return a failure exit code"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String) = ShellRunOutput("", "", 1)
    },

    object : BuiltinCommand {
        override val name = "date"
        override val summary = "Print the current date and time"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val utc = args.any { it == "-u" }
            val zone = if (utc) java.util.TimeZone.getTimeZone("UTC") else java.util.TimeZone.getDefault()
            val format = java.text.SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy", java.util.Locale.US)
            format.timeZone = zone
            return BuiltinRegistry.ok(format.format(java.util.Date()) + "\n")
        }
    },

    object : BuiltinCommand {
        override val name = "sleep"
        override val summary = "Pause for the given number of seconds"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val seconds = args.firstOrNull()?.toDoubleOrNull() ?: return BuiltinRegistry.err("sleep: invalid duration")
            kotlinx.coroutines.delay((seconds * 1000).toLong())
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "whoami"
        override val summary = "Print the current Android user identifier"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String) =
            BuiltinRegistry.ok("u0_a${android.os.Process.myUid()}\n")
    },

    object : BuiltinCommand {
        override val name = "basename"
        override val summary = "Strip the directory from a path"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val path = args.firstOrNull() ?: return BuiltinRegistry.err("basename: missing operand")
            val suffix = args.getOrNull(1)
            var base = File(path).name
            if (suffix != null && base.endsWith(suffix)) base = base.removeSuffix(suffix)
            return BuiltinRegistry.ok("$base\n")
        }
    },

    object : BuiltinCommand {
        override val name = "dirname"
        override val summary = "Strip the last component from a path"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val path = args.firstOrNull() ?: return BuiltinRegistry.err("dirname: missing operand")
            return BuiltinRegistry.ok((File(path).parent ?: ".") + "\n")
        }
    },

    object : BuiltinCommand {
        override val name = "realpath"
        override val summary = "Print the canonical path"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val path = args.firstOrNull() ?: return BuiltinRegistry.err("realpath: missing operand")
            return BuiltinRegistry.ok(ctx.resolve(path).canonicalPath + "\n")
        }
    },

    object : BuiltinCommand {
        override val name = "readlink"
        override val summary = "Print the target of a symbolic link"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val path = args.firstOrNull() ?: return BuiltinRegistry.err("readlink: missing operand")
            val file = ctx.resolve(path)
            if (!file.exists()) return BuiltinRegistry.err("readlink: $path: No such file or directory")
            return BuiltinRegistry.ok(file.canonicalPath + "\n")
        }
    },

    object : BuiltinCommand {
        override val name = "stat"
        override val summary = "Display file metadata"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val path = args.firstOrNull() ?: return BuiltinRegistry.err("stat: missing operand")
            val file = ctx.resolve(path)
            if (!file.exists()) return BuiltinRegistry.err("stat: cannot stat '$path'")
            val text = buildString {
                append("  File: ${file.name}\n")
                append("  Path: ${file.absolutePath}\n")
                append("  Type: ${if (file.isDirectory) "directory" else "regular file"}\n")
                append("  Size: ${file.length()} bytes\n")
                append("  Modified: ${java.util.Date(file.lastModified())}\n")
                append("  Readable: ${file.canRead()}\n")
                append("  Writable: ${file.canWrite()}\n")
            }
            return BuiltinRegistry.ok(text)
        }
    },

    object : BuiltinCommand {
        override val name = "du"
        override val summary = "Estimate file space usage (-h human readable)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val human = args.any { it == "-h" }
            val targets = args.filterNot { it.startsWith("-") }.ifEmpty { listOf(".") }
            val out = StringBuilder()
            for (target in targets) {
                val file = ctx.resolve(target)
                if (!file.exists()) return BuiltinRegistry.err("du: cannot access '$target'")
                val size = if (file.isDirectory) directorySize(file) else file.length()
                out.append(if (human) BuiltinRegistry.humanSize(size) else (size / 1024).toString())
                    .append('\t').append(target).append('\n')
            }
            return BuiltinRegistry.ok(out.toString())
        }

        private fun directorySize(dir: File): Long {
            var total = 0L
            val stack = ArrayDeque<File>().apply { addLast(dir) }
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.listFiles()?.forEach { child ->
                    if (child.isDirectory) stack.addLast(child) else total += child.length()
                }
            }
            return total
        }
    },

    object : BuiltinCommand {
        override val name = "tree"
        override val summary = "Show a directory tree (-L depth)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val depthIndex = args.indexOf("-L")
            val maxDepth = if (depthIndex >= 0) args.getOrNull(depthIndex + 1)?.toIntOrNull() ?: 3 else 3
            val target = args.lastOrNull { !it.startsWith("-") && it != maxDepth.toString() } ?: "."
            val root = ctx.resolve(target)
            if (!root.isDirectory) return BuiltinRegistry.err("tree: '$target' is not a directory")

            val out = StringBuilder("${root.name.ifEmpty { "." }}\n")
            var directories = 0
            var files = 0

            fun walk(dir: File, prefix: String, depth: Int) {
                if (depth > maxDepth) return
                val children = dir.listFiles()?.filter { it.name != ".git" }
                    ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                    .orEmpty()
                children.forEachIndexed { index, child ->
                    val last = index == children.lastIndex
                    out.append(prefix).append(if (last) "└── " else "├── ").append(child.name).append('\n')
                    if (child.isDirectory) {
                        directories++
                        walk(child, prefix + if (last) "    " else "│   ", depth + 1)
                    } else {
                        files++
                    }
                }
            }
            walk(root, "", 1)
            out.append("\n$directories directories, $files files\n")
            return BuiltinRegistry.ok(out.toString())
        }
    },

    object : BuiltinCommand {
        override val name = "clear"
        override val summary = "Clear the terminal output"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String) =
            ShellRunOutput("\u001B[2J\u001B[H", "", 0)
    },

    object : BuiltinCommand {
        override val name = "exit"
        override val summary = "Close this terminal session"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val code = args.firstOrNull()?.toIntOrNull() ?: 0
            return ShellRunOutput("", "", code)
        }
    },

    object : BuiltinCommand {
        override val name = "help"
        override val summary = "List the commands this shell provides"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val registry = BuiltinRegistry()
            val out = StringBuilder("GitForge shell\n\n")
            out.append("Built-in commands:\n")
            registry.all().forEach { out.append("  %-10s %s\n".format(it.name, it.summary)) }
            out.append('\n').append("Also available: git, python3, node, npm and any executable the runtime provides.\n")
            out.append("Type 'packages' to see which runtimes are installed.\n")
            return BuiltinRegistry.ok(out.toString())
        }
    },
)
