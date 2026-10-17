package com.projecthub.gitforge.terminal.shell

import com.projecthub.gitforge.core.Redaction
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The text-processing utilities a developer expects from a shell.
 *
 * These are genuine implementations over the real filesystem. They are not a
 * toy subset: `grep -rn`, `sed s///g`, `awk '{print $2}'`, `sort | uniq -c` and
 * friends all work, which is what makes the terminal useful for real work.
 */
internal val TEXT_BUILTINS: List<BuiltinCommand> = listOf(
    object : BuiltinCommand {
        override val name = "grep"
        override val summary = "Search text (-i ignore case, -n line numbers, -r recursive, -v invert, -c count, -l files)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val flags = args.filter { it.startsWith("-") && it != "--" }.joinToString("")
            val ignoreCase = flags.contains('i')
            val lineNumbers = flags.contains('n')
            val recursive = flags.contains('r') || flags.contains('R')
            val invert = flags.contains('v')
            val countOnly = flags.contains('c')
            val filesOnly = flags.contains('l')

            val positional = args.filterNot { it.startsWith("-") }
            if (positional.isEmpty()) return BuiltinRegistry.err("grep: missing pattern")
            val pattern = positional.first()
            val targets = positional.drop(1)

            val regex = try {
                Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            } catch (e: Exception) {
                return BuiltinRegistry.err("grep: invalid pattern: ${e.message}")
            }

            val out = StringBuilder()
            var matched = false
            var exitCode = 1

            fun scan(file: File, label: String) {
                // Cap the scan so a huge log cannot OOM the shell; note truncation.
                val lines = runCatching {
                    file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
                        val acc = mutableListOf<String>()
                        var chars = 0
                        val buf = CharArray(8192)
                        val cur = StringBuilder()
                        var truncated = false
                        fun flushLine() {
                            acc += cur.toString()
                            cur.setLength(0)
                        }
                        while (true) {
                            val n = reader.read(buf)
                            if (n <= 0) break
                            var k = 0
                            while (k < n) {
                                if (chars >= BuiltinRegistry.MAX_INPUT_BYTES) {
                                    truncated = true
                                    break
                                }
                                val c = buf[k++]
                                chars++
                                if (c == '\n') flushLine() else cur.append(c)
                            }
                            if (truncated) break
                        }
                        if (cur.isNotEmpty()) acc += cur.toString()
                        if (acc.size > 200_000) acc.subList(200_000, acc.size).clear()
                        acc
                    }
                }.getOrNull() ?: return
                var hits = 0
                lines.forEachIndexed { index, line ->
                    val isMatch = regex.containsMatchIn(line) != invert
                    if (isMatch) {
                        hits++
                        matched = true
                        when {
                            filesOnly -> Unit
                            countOnly -> Unit
                            lineNumbers -> out.append("$label:${index + 1}:$line\n")
                            else -> out.append(if (label.isEmpty()) "$line\n" else "$label:$line\n")
                        }
                    }
                }
                if (hits > 0) {
                    exitCode = 0
                    if (filesOnly) out.append("$label\n")
                }
                // `grep -c` reports 0 for a file with no match, same as GNU grep.
                if (countOnly) out.append(if (label.isEmpty()) "$hits\n" else "$label:$hits\n")
            }

            if (targets.isEmpty()) {
                // Reading from stdin: behave like a filter.
                var hits = 0
                stdin.lines().forEachIndexed { index, line ->
                    val isMatch = regex.containsMatchIn(line) != invert
                    if (isMatch) {
                        hits++
                        matched = true
                        exitCode = 0
                        if (countOnly || filesOnly) Unit
                        else if (lineNumbers) out.append("${index + 1}:$line\n")
                        else out.append("$line\n")
                    }
                }
                if (countOnly) out.append("$hits\n")
            } else {
                for (target in targets) {
                    val file = ctx.resolve(target)
                    when {
                        file.isFile -> scan(file, if (targets.size > 1) target else "")
                        file.isDirectory && recursive -> {
                            walk(file).forEach { child ->
                                scan(child, child.absolutePath.removePrefix(ctx.cwd.absolutePath).trimStart('/'))
                            }
                        }
                        file.isDirectory -> return BuiltinRegistry.err("grep: $target: Is a directory")
                        else -> return BuiltinRegistry.err("grep: $target: No such file or directory", 2)
                    }
                }
            }
            return ShellRunOutput(out.toString(), "", exitCode)
        }

        private fun walk(dir: File): List<File> {
            val files = mutableListOf<File>()
            val stack = ArrayDeque<File>().apply { addLast(dir) }
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        if (child.name != ".git") stack.addLast(child)
                    } else {
                        files += child
                    }
                }
            }
            return files
        }
    },

    object : BuiltinCommand {
        override val name = "find"
        override val summary = "Find files (-name pattern, -type f|d, -maxdepth n)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val start = args.firstOrNull { !it.startsWith("-") } ?: "."
            val namePattern = args.getOrNull(args.indexOf("-name") + 1)?.takeIf { args.contains("-name") }
            val typeFilter = args.getOrNull(args.indexOf("-type") + 1)?.takeIf { args.contains("-type") }
            val maxDepth = args.getOrNull(args.indexOf("-maxdepth") + 1)?.toIntOrNull()
                ?.takeIf { args.contains("-maxdepth") } ?: Int.MAX_VALUE

            val root = ctx.resolve(start)
            if (!root.exists()) return BuiltinRegistry.err("find: '$start': No such file or directory")

            val matcher = namePattern?.let { globToRegex(it) }
            val out = StringBuilder()

            fun visit(file: File, depth: Int) {
                if (depth > maxDepth) return
                val typeOk = when (typeFilter) {
                    "f" -> file.isFile
                    "d" -> file.isDirectory
                    else -> true
                }
                val nameOk = matcher?.matches(file.name) ?: true
                if (typeOk && nameOk) {
                    val display = if (start == ".") "./" + file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                    else file.absolutePath
                    out.append(display.ifEmpty { "." }).append('\n')
                }
                if (file.isDirectory && depth < maxDepth) {
                    file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                        if (child.name != ".git") visit(child, depth + 1)
                    }
                }
            }
            visit(root, 0)
            return BuiltinRegistry.ok(out.toString())
        }

        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append(Regex.escape(c.toString()))
                }
            }
            return Regex(sb.append('$').toString())
        }
    },

    object : BuiltinCommand {
        override val name = "head"
        override val summary = "Print the first lines (-n count)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val count = extractCount(args, defaultCount = 10)
            return try {
                val lines = BuiltinRegistry.readInput(ctx, args, stdin).trimEnd('\n').lines()
                BuiltinRegistry.ok(lines.take(count).joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
            } catch (e: IOException) {
                BuiltinRegistry.err("head: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "tail"
        override val summary = "Print the last lines (-n count)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val count = extractCount(args, defaultCount = 10)
            return try {
                val lines = BuiltinRegistry.readInput(ctx, args, stdin).trimEnd('\n').lines()
                BuiltinRegistry.ok(lines.takeLast(count).joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
            } catch (e: IOException) {
                BuiltinRegistry.err("tail: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "sort"
        override val summary = "Sort lines (-r reverse, -n numeric, -u unique)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val reverse = args.any { it.startsWith("-") && it.contains('r') }
            val numeric = args.any { it.startsWith("-") && it.contains('n') }
            val unique = args.any { it.startsWith("-") && it.contains('u') }
            return try {
                var lines = BuiltinRegistry.readInput(ctx, args, stdin).lines()
                // .lines() on a trailing newline yields a final empty string;
                // drop it so sorted output does not start with a blank line.
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
                val sorted = if (numeric) {
                    lines.sortedBy { it.trim().substringBefore(' ').toDoubleOrNull() ?: Double.MAX_VALUE }
                } else {
                    lines.sorted()
                }
                val finalLines = if (unique) sorted.distinct() else sorted
                BuiltinRegistry.ok((if (reverse) finalLines.reversed() else finalLines).joinToString("\n") + "\n")
            } catch (e: IOException) {
                BuiltinRegistry.err("sort: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "uniq"
        override val summary = "Collapse repeated lines (-c prefix counts)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val withCounts = args.any { it.startsWith("-") && it.contains('c') }
            return try {
                val lines = BuiltinRegistry.readInput(ctx, args, stdin).lines()
                val out = StringBuilder()
                var previous: String? = null
                var count = 0
                for (line in lines) {
                    if (line == previous) {
                        count++
                    } else {
                        if (previous != null) {
                            val prev: String = previous
                            out.append(
                                if (withCounts) {
                                    "%7d %s\n".format(count, prev)
                                } else {
                                    "$prev\n"
                                },
                            )
                        }
                        previous = line
                        count = 1
                    }
                }
                if (previous != null) {
                    val prev: String = previous
                    out.append(
                        if (withCounts) {
                            "%7d %s\n".format(count, prev)
                        } else {
                            "$prev\n"
                        },
                    )
                }
                BuiltinRegistry.ok(out.toString())
            } catch (e: IOException) {
                BuiltinRegistry.err("uniq: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "wc"
        override val summary = "Count lines, words and bytes (-l -w -c)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val onlyLines = args.any { it == "-l" }
            val onlyWords = args.any { it == "-w" }
            val onlyChars = args.any { it == "-c" }
            return try {
                val text = BuiltinRegistry.readInput(ctx, args, stdin)
                val lines = text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith("\n")) 1 else 0
                val words = text.split(Regex("""\s+""")).count { it.isNotEmpty() }
                val chars = text.length
                val output = when {
                    onlyLines -> "$lines\n"
                    onlyWords -> "$words\n"
                    onlyChars -> "$chars\n"
                    else -> "%7d %7d %7d\n".format(lines, words, chars)
                }
                BuiltinRegistry.ok(output)
            } catch (e: IOException) {
                BuiltinRegistry.err("wc: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "cut"
        override val summary = "Select fields (-d delimiter, -f fields, -c characters)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val delimiter = args.getOrNull(args.indexOf("-d") + 1)?.takeIf { args.contains("-d") }
                ?.removeSurrounding("\"") ?: "\t"
            val fieldsArg = args.getOrNull(args.indexOf("-f") + 1)?.takeIf { args.contains("-f") }
            val charsArg = args.getOrNull(args.indexOf("-c") + 1)?.takeIf { args.contains("-c") }

            if (fieldsArg == null && charsArg == null) {
                return BuiltinRegistry.err("cut: one of -f or -c is required")
            }

            return try {
                val lines = BuiltinRegistry.readInput(ctx, args, stdin).lines()
                val out = StringBuilder()
                for (line in lines) {
                    if (fieldsArg != null) {
                        val parts = line.split(delimiter)
                        val selected = parseFieldSpec(fieldsArg).mapNotNull { index ->
                            parts.getOrNull(index - 1)
                        }
                        out.append(selected.joinToString(delimiter)).append('\n')
                    } else {
                        val spec = charsArg!!
                        val indices = parseFieldSpec(spec)
                        out.append(indices.mapNotNull { line.getOrNull(it - 1) }.joinToString("")).append('\n')
                    }
                }
                BuiltinRegistry.ok(out.toString())
            } catch (e: IOException) {
                BuiltinRegistry.err("cut: ${e.message}")
            }
        }

        private fun parseFieldSpec(spec: String): List<Int> {
            val result = mutableListOf<Int>()
            for (part in spec.split(',')) {
                if (part.contains('-')) {
                    val (from, to) = part.split('-', limit = 2)
                    val start = from.toIntOrNull() ?: 1
                    // Open-ended `2-` means "to end of line"; cap at Int.MAX so
                    // callers selecting via getOrNull simply stop at the last field.
                    val end = to.toIntOrNull() ?: Int.MAX_VALUE
                    if (end >= start) {
                        val capped = minOf(end, start + 4096)
                        for (i in start..capped) result += i
                    }
                } else {
                    part.toIntOrNull()?.let { result += it }
                }
            }
            return result
        }
    },

    object : BuiltinCommand {
        override val name = "sed"
        override val summary = "Stream edit (supports s/regex/replacement/[gi])"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val expression = args.firstOrNull { !it.startsWith("-") }
                ?: return BuiltinRegistry.err("sed: missing script")
            if (!expression.startsWith("s")) {
                return BuiltinRegistry.err("sed: only the 's' command is supported, for example: sed 's/old/new/g'")
            }
            val delimiter = expression.getOrNull(1) ?: '/'
            val parts = splitUnescaped(expression.substring(2), delimiter)
            if (parts.size < 2) return BuiltinRegistry.err("sed: malformed substitution")
            val pattern = parts[0]
            val replacement = parts[1]
            val flags = parts.getOrNull(2).orEmpty()
            val ignoreCase = flags.contains('i')
            val global = flags.contains('g')

            return try {
                // The script is the first positional argument; only what follows it
                // are file operands.
                val operands = BuiltinRegistry.fileOperands(args).drop(1)
                val text = BuiltinRegistry.readInput(ctx, operands, stdin)
                val regex = try {
                    Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                } catch (e: Exception) {
                    return BuiltinRegistry.err("sed: invalid pattern: ${e.message}")
                }
                val result = if (global) {
                    regex.replace(text) { match -> expandReplacement(replacement, match) }
                } else {
                    val first = regex.find(text)
                    if (first == null) {
                        text
                    } else {
                        text.replaceRange(first.range, expandReplacement(replacement, first))
                    }
                }
                BuiltinRegistry.ok(result)
            } catch (e: IOException) {
                BuiltinRegistry.err("sed: ${e.message}")
            }
        }

        private fun splitUnescaped(input: String, delimiter: Char): List<String> {
            val parts = mutableListOf<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < input.length) {
                if (input[i] == '\\' && i + 1 < input.length) {
                    sb.append(input[i + 1]); i += 2; continue
                }
                if (input[i] == delimiter) { parts += sb.toString(); sb.setLength(0); i++; continue }
                sb.append(input[i]); i++
            }
            parts += sb.toString()
            return parts
        }

        private fun expandReplacement(replacement: String, match: MatchResult): String {
            var result = replacement
            for (group in match.groupValues.indices.reversed()) {
                result = result.replace("\\$group", match.groupValues[group])
            }
            result = result.replace("\\n", "\n").replace("\\t", "\t")
            return result
        }
    },

    object : BuiltinCommand {
        override val name = "awk"
        override val summary = "Field extraction, e.g. awk '{print $1, $3}' (print/NF/NR supported)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val script = args.firstOrNull { !it.startsWith("-") }
                ?: return BuiltinRegistry.err("awk: missing program")
            val program = script.removeSurrounding("'").trim().removeSurrounding("{").removeSurrounding("}").trim()
            if (!program.startsWith("print")) {
                return BuiltinRegistry.err("awk: only 'print' programs are supported, for example: awk '{print \$1, \$2}'")
            }
            val fieldsToPrint = program.removePrefix("print").trim()

            return try {
                val operands = BuiltinRegistry.fileOperands(args).drop(1)
                val lines = BuiltinRegistry.readInput(ctx, operands, stdin).lines()
                val out = StringBuilder()
                lines.forEachIndexed { index, line ->
                    val fields = line.trim().split(Regex("""\s+"""))
                    val pieces = fieldsToPrint.split(',').map { it.trim() }
                    val rendered = pieces.joinToString(" ") { piece ->
                        when {
                            piece == "\$0" -> line
                            piece == "NF" -> fields.size.toString()
                            piece == "NR" -> (index + 1).toString()
                            piece.startsWith("$") -> {
                                val number = piece.removePrefix("$").toIntOrNull()
                                number?.let { fields.getOrNull(it - 1) }.orEmpty()
                            }
                            piece.startsWith("\"") -> piece.removeSurrounding("\"")
                            else -> piece
                        }
                    }
                    out.append(rendered).append('\n')
                }
                BuiltinRegistry.ok(out.toString())
            } catch (e: IOException) {
                BuiltinRegistry.err("awk: ${e.message}")
            }
        }
    },

    object : BuiltinCommand {
        override val name = "diff"
        override val summary = "Compare two files line by line"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val positional = args.filterNot { it.startsWith("-") }
            if (positional.size < 2) return BuiltinRegistry.err("diff: two file operands are required")
            val left = ctx.resolve(positional[0])
            val right = ctx.resolve(positional[1])
            if (!left.exists()) return BuiltinRegistry.err("diff: ${positional[0]}: No such file or directory", 2)
            if (!right.exists()) return BuiltinRegistry.err("diff: ${positional[1]}: No such file or directory", 2)

            if (left.length() > BuiltinRegistry.MAX_INPUT_BYTES || right.length() > BuiltinRegistry.MAX_INPUT_BYTES) {
                return BuiltinRegistry.err("diff: file too large for a line-by-line diff")
            }
            val leftLines = left.readLines()
            val rightLines = right.readLines()
            val out = StringBuilder()
            val max = maxOf(leftLines.size, rightLines.size)
            var differences = 0
            for (i in 0 until max) {
                val l = leftLines.getOrNull(i)
                val r = rightLines.getOrNull(i)
                if (l != r) {
                    differences++
                    if (l != null) out.append("${i + 1}d${i}< $l\n")
                    if (r != null) out.append("${i + 1}a${i + 1}> $r\n")
                }
            }
            if (differences == 0) return ShellRunOutput("", "", 0)
            return ShellRunOutput(out.toString(), "", 1)
        }
    },

    object : BuiltinCommand {
        override val name = "curl"
        override val summary = "Transfer a URL (-o output, -s silent, -L follow redirects, -I headers only, -H header)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val url = args.lastOrNull { it.startsWith("http://") || it.startsWith("https://") }
                ?: return BuiltinRegistry.err("curl: no URL specified")
            val silent = args.any { it == "-s" || it == "--silent" }
            val headersOnly = args.any { it == "-I" || it == "--head" }
            val outputFile = args.getOrNull(args.indexOf("-o") + 1)?.takeIf { args.contains("-o") }
            val extraHeaders = mutableListOf<String>()
            args.forEachIndexed { index, arg ->
                if (arg == "-H") args.getOrNull(index + 1)?.let { extraHeaders += it }
            }

            return try {
                val client = okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val builder = okhttp3.Request.Builder().url(url).get()
                extraHeaders.forEach { header ->
                    val name = header.substringBefore(':').trim()
                    val value = header.substringAfter(':', "").trim()
                    if (name.isNotEmpty()) builder.header(name, value)
                }

                client.newCall(builder.build()).execute().use { response ->
                    if (headersOnly) {
                        val sb = StringBuilder()
                        sb.append("HTTP ${response.code} ${response.message}\n")
                        response.headers.forEach { (name, value) -> sb.append("$name: $value\n") }
                        return BuiltinRegistry.ok(sb.toString())
                    }
                    // Stream with a cap: .string() would materialise binary bodies
                    // fully in memory and corrupt them through charset decoding.
                    val capBytes = 32L * 1024 * 1024
                    if (outputFile != null) {
                        val target = try {
                            ctx.resolveForWrite(outputFile)
                        } catch (e: IOException) {
                            return BuiltinRegistry.err("curl: ${e.message}")
                        }
                        target.parentFile?.mkdirs()
                        val body = response.body ?: return BuiltinRegistry.err("curl: empty response body", 22)
                        var written = 0L
                        target.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    if (written + n > capBytes) {
                                        return BuiltinRegistry.err("curl: response exceeds 32 MB cap", 22)
                                    }
                                    out.write(buf, 0, n)
                                    written += n
                                }
                            }
                        }
                        if (!response.isSuccessful && !silent) {
                            return ShellRunOutput("", "curl: HTTP ${response.code} ${response.message}\n", 22)
                        }
                        val message = if (silent) "" else "Downloaded ${BuiltinRegistry.humanSize(written)} to $outputFile\n"
                        BuiltinRegistry.ok(message)
                    } else {
                        val body = response.body ?: return BuiltinRegistry.err("curl: empty response body", 22)
                        val sb = StringBuilder()
                        var total = 0L
                        body.byteStream().buffered().reader(Charsets.UTF_8).use { reader ->
                            val buf = CharArray(16 * 1024)
                            while (true) {
                                val n = reader.read(buf)
                                if (n <= 0) break
                                if (total + n > 2 * 1024 * 1024) {
                                    sb.append(buf, 0, (2 * 1024 * 1024 - total).toInt())
                                    total = 2 * 1024 * 1024
                                    break
                                }
                                sb.append(buf, 0, n)
                                total += n
                            }
                        }
                        val text = sb.toString()
                        if (!response.isSuccessful && !silent) {
                            return ShellRunOutput(text, "curl: HTTP ${response.code} ${response.message}\n", 22)
                        }
                        BuiltinRegistry.ok(text)
                    }
                }
            } catch (e: Exception) {
                BuiltinRegistry.err("curl: ${Redaction.redact(e.message ?: "request failed")}", 7)
            }
        }
    },

    object : BuiltinCommand {
        override val name = "gzip"
        override val summary = "Compress files (-d decompress, -k keep original)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val decompress = args.any { it.startsWith("-") && it.contains('d') }
            val keep = args.any { it.startsWith("-") && it.contains('k') }
            val targets = args.filterNot { it.startsWith("-") }
            if (targets.isEmpty()) return BuiltinRegistry.err("gzip: missing file operand")

            for (target in targets) {
                val file = ctx.resolve(target)
                if (!file.exists()) return BuiltinRegistry.err("gzip: $target: No such file or directory")
                if (decompress && !file.name.endsWith(".gz")) {
                    return BuiltinRegistry.err("gzip: $target: unknown suffix -- ignored")
                }
                try {
                    if (decompress) {
                        val output = File(file.parentFile, file.name.removeSuffix(".gz"))
                        if (output.canonicalFile == file.canonicalFile) {
                            return BuiltinRegistry.err("gzip: $target: refusing to overwrite input")
                        }
                        GZIPInputStream(file.inputStream()).use { input ->
                            output.outputStream().use { input.copyTo(it) }
                        }
                        if (!keep) file.delete()
                    } else {
                        val output = File(file.parentFile, "${file.name}.gz")
                        GZIPOutputStream(output.outputStream()).use { gz -> file.inputStream().use { it.copyTo(gz) } }
                        if (!keep) file.delete()
                    }
                } catch (e: IOException) {
                    return BuiltinRegistry.err("gzip: ${e.message}")
                }
            }
            return BuiltinRegistry.ok()
        }
    },

    object : BuiltinCommand {
        override val name = "tar"
        override val summary = "Archive files (-c create, -x extract, -f file, -z gzip, -t list)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val flags = args.filter { it.startsWith("-") }.joinToString("")
            val archiveName = args.getOrNull(args.indexOf("-f") + 1)?.takeIf { args.contains("-f") }
                ?: return BuiltinRegistry.err("tar: -f archive is required")
            val archive = ctx.resolve(archiveName)
            val gzip = flags.contains('z')
            val operands = args.dropWhile { it.startsWith("-") || it == archiveName }
                .filterNot { it.startsWith("-") }

            return try {
                when {
                    flags.contains('c') -> {
                        if (operands.isEmpty()) return BuiltinRegistry.err("tar: no files to archive")
                        openArchiveOutput(archive, gzip).use { out ->
                            ZipOutputStream(out).use { zip ->
                                for (operand in operands) {
                                    val source = ctx.resolve(operand)
                                    if (!source.exists()) return BuiltinRegistry.err("tar: $operand: No such file or directory")
                                    addToZip(zip, source, source.name)
                                }
                            }
                        }
                        BuiltinRegistry.ok()
                    }

                    flags.contains('x') -> {
                        if (!archive.exists()) return BuiltinRegistry.err("tar: $archiveName: No such file or directory")
                        openArchiveInput(archive, gzip).use { input ->
                            ZipInputStream(input).use { zip ->
                                var entry: ZipEntry? = zip.nextEntry
                                while (entry != null) {
                                    val current = entry
                                    val target = File(ctx.cwd, current.name).canonicalFile
                                    val cwdCanonical = ctx.cwd.canonicalPath
                                    if (target.path != cwdCanonical && !target.path.startsWith(cwdCanonical + File.separator)) {
                                        return BuiltinRegistry.err("tar: refusing to extract '${current.name}' outside the working directory")
                                    }
                                    if (current.isDirectory) {
                                        target.mkdirs()
                                    } else {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().use { zip.copyTo(it) }
                                    }
                                    entry = zip.nextEntry
                                }
                            }
                        }
                        BuiltinRegistry.ok()
                    }

                    flags.contains('t') -> {
                        openArchiveInput(archive, gzip).use { input ->
                            ZipInputStream(input).use { zip ->
                                val out = StringBuilder()
                                var entry = zip.nextEntry
                                while (entry != null) {
                                    out.append(entry.name).append('\n')
                                    entry = zip.nextEntry
                                }
                                BuiltinRegistry.ok(out.toString())
                            }
                        }
                    }

                    else -> BuiltinRegistry.err("tar: specify one of -c, -x or -t")
                }
            } catch (e: IOException) {
                BuiltinRegistry.err("tar: ${e.message}")
            }
        }

        private fun openArchiveOutput(file: File, gzip: Boolean) =
            if (gzip) GZIPOutputStream(file.outputStream()) else file.outputStream()

        private fun openArchiveInput(file: File, gzip: Boolean) =
            if (gzip) GZIPInputStream(file.inputStream()) else file.inputStream()

        private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
            if (file.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
                file.listFiles()?.forEach { child -> addToZip(zip, child, "$entryName/${child.name}") }
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    },

    object : BuiltinCommand {
        override val name = "unzip"
        override val summary = "Extract a zip archive (-l list, -d destination)"
        override suspend fun run(ctx: ShellContext, args: List<String>, stdin: String): ShellRunOutput {
            val archiveName = args.firstOrNull { !it.startsWith("-") }
                ?: return BuiltinRegistry.err("unzip: missing archive operand")
            val archive = ctx.resolve(archiveName)
            if (!archive.exists()) return BuiltinRegistry.err("unzip: $archiveName: No such file or directory")
            val listOnly = args.any { it == "-l" }
            val destination = args.getOrNull(args.indexOf("-d") + 1)?.takeIf { args.contains("-d") }
                ?.let { ctx.resolve(it) } ?: ctx.cwd

            return try {
                ZipInputStream(archive.inputStream()).use { zip ->
                    val out = StringBuilder()
                    var entry: ZipEntry? = zip.nextEntry
                    while (true) {
                        val current = entry ?: break
                        if (listOnly) {
                            out.append("%10d  %s\n".format(current.size.coerceAtLeast(0), current.name))
                        } else {
                            val target = File(destination, current.name).canonicalFile
                            val destCanonical = destination.canonicalPath
                            if (target.path != destCanonical && !target.path.startsWith(destCanonical + File.separator)) {
                                return BuiltinRegistry.err("unzip: refusing to extract '${current.name}' outside the destination")
                            }
                            if (current.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zip.copyTo(it) }
                                out.append("  inflating: ${current.name}\n")
                            }
                        }
                        entry = zip.nextEntry
                    }
                    BuiltinRegistry.ok(out.toString())
                }
            } catch (e: IOException) {
                BuiltinRegistry.err("unzip: ${e.message}")
            }
        }
    },
)

/** Reads `-n 5`, `-n5` or a bare trailing number for head/tail. */
private fun extractCount(args: List<String>, defaultCount: Int): Int {
    val nIndex = args.indexOf("-n")
    if (nIndex >= 0) return args.getOrNull(nIndex + 1)?.toIntOrNull() ?: defaultCount
    args.firstOrNull { it.startsWith("-n") && it.length > 2 }?.removePrefix("-n")?.toIntOrNull()?.let { return it }
    return defaultCount
}
