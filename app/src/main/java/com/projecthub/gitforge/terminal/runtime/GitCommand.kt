package com.projecthub.gitforge.terminal.runtime

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.Redaction
import com.projecthub.gitforge.terminal.TerminalLine
import com.projecthub.gitforge.terminal.shell.ExternalCommand
import com.projecthub.gitforge.terminal.shell.ShellContext
import com.projecthub.gitforge.terminal.shell.ShellRunOutput
import java.io.File

/**
 * `git`, implemented through the app's single JGit engine.
 *
 * Why JGit rather than a native git binary: Android ships no git, and shipping one
 * would mean either bundling a large native toolchain or depending on a second
 * userspace. JGit is a maintained, pure-Java implementation with an Apache-2.0
 * licence that runs on Android without native code, which keeps the APK small and
 * the build reproducible.
 *
 * Because the terminal and the Git UI both go through this one engine, the index
 * and working tree always agree: `git status` in the terminal shows exactly what
 * the Git screen shows. Two independent Git implementations are never allowed to
 * touch the same working tree.
 */
class GitCommand(
    private val gitEngine: com.projecthub.gitforge.git.GitEngine,
    private val identityProvider: () -> Pair<String, String>,
    /**
     * Supplies the HTTPS credential for network calls. Defaults to null (no
     * credential) so existing constructions keep compiling; AppContainer must
     * pass `{ credentialStore.token() }` so fetch/pull/clone authenticate.
     */
    private val tokenProvider: () -> String? = { null },
) : ExternalCommand {

    override val name = "git"
    override val summary = "Version control (JGit-backed, same engine as the Git screen)"
    override val available: Boolean = true
    override val unavailableReason: String? = null

    override suspend fun execute(
        ctx: ShellContext,
        args: List<String>,
        stdin: String,
        onOutput: (String, TerminalLine.Stream) -> Unit,
    ): ShellRunOutput {
        val out = StringBuilder()
        val err = StringBuilder()

        fun emit(text: String, stream: TerminalLine.Stream = TerminalLine.Stream.STDOUT) {
            if (text.isEmpty()) return
            if (stream == TerminalLine.Stream.STDERR) err.append(text) else out.append(text)
            onOutput(text, stream)
        }

        if (args.isEmpty()) {
            emit(usage(), TerminalLine.Stream.STDERR)
            return ShellRunOutput(out.toString(), err.toString(), 1)
        }

        // `git -C <dir>` is the documented way to run against another tree.
        var workingDir = ctx.cwd
        val effectiveArgs = args.toMutableList()
        if (effectiveArgs.firstOrNull() == "-C") {
            val target = effectiveArgs.getOrNull(1)
            if (target == null) {
                emit("git: -C requires a directory\n", TerminalLine.Stream.STDERR)
                return ShellRunOutput("", "git: -C requires a directory\n", 129)
            }
            workingDir = ctx.resolve(target)
            effectiveArgs.removeAt(0)
            effectiveArgs.removeAt(0)
        }
        if (effectiveArgs.firstOrNull() == "--version") {
            emit("git version ${versionString()}\n")
            return ShellRunOutput(out.toString(), "", 0)
        }

        val root = com.projecthub.gitforge.terminal.shell.CommandResolver.findRepositoryRoot(workingDir)
        if (root == null && effectiveArgs.firstOrNull() != "init" && effectiveArgs.firstOrNull() != "clone") {
            val message = "fatal: not a git repository (or any of the parent directories): .git\n"
            emit(message, TerminalLine.Stream.STDERR)
            return ShellRunOutput("", message, 128)
        }
        val repositoryDir = root ?: workingDir

        return try {
            val exitCode = dispatch(repositoryDir, effectiveArgs, ctx, { text, stream -> emit(text, stream) })
            ShellRunOutput(out.toString(), err.toString(), exitCode)
        } catch (e: com.projecthub.gitforge.git.GitOperationException) {
            val message = "fatal: ${Redaction.redact(e.message)}\n"
            emit(message, TerminalLine.Stream.STDERR)
            ShellRunOutput(out.toString(), err.toString(), 128)
        } catch (e: Throwable) {
            AppLog.w("git command failed", e)
            val message = "fatal: ${Redaction.redact(e.message ?: "unexpected error")}\n"
            emit(message, TerminalLine.Stream.STDERR)
            ShellRunOutput(out.toString(), err.toString(), 128)
        }
    }

    private suspend fun dispatch(
        repositoryDir: File,
        args: List<String>,
        ctx: ShellContext,
        emitRaw: (String, TerminalLine.Stream) -> Unit,
    ): Int {
        // Inside the dispatcher most output is stdout, so this local helper keeps the
        // individual command implementations readable.
        fun emit(text: String, stream: TerminalLine.Stream = TerminalLine.Stream.STDOUT) =
            emitRaw(text, stream)

        val subcommand = args.first()
        val rest = args.drop(1)

        return when (subcommand) {
            "init" -> {
                val target = rest.firstOrNull()?.let { ctx.resolve(it) } ?: repositoryDir
                gitEngine.init(target)
                emit("Initialized empty Git repository in ${target.absolutePath}/.git/\n")
                0
            }

            "status" -> {
                val status = gitEngine.status(repositoryDir)
                val short = rest.any { it == "--short" || it == "-s" }
                val porcelain = rest.any { it == "--porcelain" }
                emit(renderStatus(status, short || porcelain))
                0
            }

            "log" -> {
                val limit = rest.getOrNull(rest.indexOf("-n") + 1)?.toIntOrNull()?.takeIf { rest.contains("-n") } ?: 20
                val oneline = rest.any { it == "--oneline" }
                val commits = gitEngine.log(repositoryDir, limit = limit)
                if (commits.isEmpty()) {
                    emit("fatal: your current branch does not have any commits yet\n", TerminalLine.Stream.STDERR)
                    128
                } else {
                    emit(renderLog(commits, oneline))
                    0
                }
            }

            "diff" -> {
                val staged = rest.any { it == "--cached" || it == "--staged" }
                val path = rest.lastOrNull { !it.startsWith("-") }
                val diffs = gitEngine.diff(repositoryDir, path, staged)
                if (diffs.isEmpty()) {
                    0
                } else {
                    emit(renderDiff(diffs))
                    0
                }
            }

            "add" -> {
                val paths = rest.filterNot { it.startsWith("-") }
                if (paths.isEmpty()) {
                    emit("Nothing specified, nothing added.\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    gitEngine.stage(repositoryDir, paths)
                    0
                }
            }

            "reset" -> {
                val paths = rest.filterNot { it.startsWith("-") }
                if (paths.isEmpty()) {
                    emit("git reset without paths is not supported in this build; specify files to unstage.\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    gitEngine.unstage(repositoryDir, paths)
                    0
                }
            }

            "commit" -> {
                val messageIndex = rest.indexOfFirst { it == "-m" || it == "--message" }
                val message = if (messageIndex >= 0) {
                    rest.getOrNull(messageIndex + 1)?.removeSurrounding("\"")?.removeSurrounding("'")
                } else {
                    null
                }
                if (message.isNullOrBlank()) {
                    emit("fatal: no commit message supplied. Use: git commit -m \"message\"\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    val (authorName, authorEmail) = identityProvider()
                    val amend = rest.any { it == "--amend" }
                    val commit = gitEngine.commit(repositoryDir, message, authorName, authorEmail, amend)
                    emit("[${gitEngine.status(repositoryDir).branch} ${commit.shortSha}] ${commit.message.lineSequence().first()}\n")
                    0
                }
            }

            "branch" -> {
                val flags = rest.filter { it.startsWith("-") }
                val names = rest.filterNot { it.startsWith("-") }
                when {
                    names.isEmpty() -> {
                        val branches = gitEngine.branches(repositoryDir).filterNot { it.isRemote }
                        emit(branches.joinToString("\n") { (if (it.isCurrent) "* " else "  ") + it.name } + "\n")
                        0
                    }
                    flags.contains("-d") || flags.contains("-D") -> {
                        gitEngine.deleteBranch(repositoryDir, names.first(), force = flags.contains("-D"))
                        emit("Deleted branch ${names.first()}\n")
                        0
                    }
                    flags.contains("-m") -> {
                        if (names.size < 2) {
                            emit("fatal: branch -m requires old and new names\n", TerminalLine.Stream.STDERR)
                            1
                        } else {
                            gitEngine.renameBranch(repositoryDir, names[0], names[1])
                            0
                        }
                    }
                    else -> {
                        gitEngine.createBranch(repositoryDir, names.first(), checkout = false)
                        0
                    }
                }
            }

            "checkout", "switch" -> {
                val name = rest.lastOrNull { !it.startsWith("-") }
                if (name == null) {
                    emit("fatal: no branch specified\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    val create = rest.any { it == "-b" || it == "-c" }
                    if (create) {
                        gitEngine.createBranch(repositoryDir, name, checkout = true)
                    } else {
                        gitEngine.checkout(repositoryDir, name)
                    }
                    emit("Switched to branch '$name'\n")
                    0
                }
            }

            "remote" -> {
                val remotes = gitEngine.remotes(repositoryDir)
                if (remotes.isEmpty()) {
                    0
                } else {
                    emit(remotes.joinToString("\n") { "${it.name}\t${it.fetchUrl}" } + "\n")
                    0
                }
            }

            "fetch" -> {
                gitEngine.fetch(repositoryDir, tokenProvider())
                emit("Fetched from origin\n")
                0
            }

            "pull" -> {
                gitEngine.pull(repositoryDir, tokenProvider())
                emit("Already up to date or merged successfully\n")
                0
            }

            "push" -> {
                emit(
                    "To push from the terminal, use the Git screen so GitForge can attach your credential securely.\n",
                    TerminalLine.Stream.STDERR,
                )
                1
            }

            "rev-parse" -> {
                val what = rest.firstOrNull() ?: "HEAD"
                gitEngine.open(repositoryDir).use { git ->
                    val repo = git.repository
                    val resolved = when (what) {
                        "--abbrev-ref" -> null
                        "--show-toplevel" -> repositoryDir.absolutePath
                        else -> repo.resolve(what)?.name
                    }
                    val output = if (rest.contains("--abbrev-ref")) {
                        val ref = rest.getOrNull(rest.indexOf("--abbrev-ref") + 1) ?: "HEAD"
                        if (ref == "HEAD") gitEngine.currentBranch(repo) else repo.resolve(ref)?.name
                    } else {
                        resolved
                    }
                    if (output == null) {
                        emit("fatal: ambiguous argument '$what'\n", TerminalLine.Stream.STDERR)
                        128
                    } else {
                        emit("$output\n")
                        0
                    }
                }
            }

            "config" -> {
                val (authorName, authorEmail) = identityProvider()
                val key = rest.lastOrNull()
                val value = when (key) {
                    "user.name" -> authorName
                    "user.email" -> authorEmail
                    else -> null
                }
                if (value == null) {
                    emit("git config: only user.name and user.email are managed by GitForge Settings\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    emit("$value\n")
                    0
                }
            }

            "clone" -> {
                val url = rest.firstOrNull { !it.startsWith("-") }
                if (url == null) {
                    emit("fatal: repository URL required\n", TerminalLine.Stream.STDERR)
                    1
                } else {
                    val destination = rest.getOrNull(rest.indexOf(url) + 1)?.takeIf { !it.startsWith("-") }
                    val target = destination?.let { ctx.resolve(it) } ?: ctx.resolve(url.substringAfterLast('/').removeSuffix(".git"))
                    gitEngine.clone(url, target, token = null) { progress ->
                        if (progress.total > 0) {
                            emit("\r${progress.phase}: ${progress.completed}/${progress.total}")
                        }
                    }
                    emit("\nCloned into ${target.name}\n")
                    0
                }
            }

            "help", "--help" -> {
                emit(usage())
                0
            }

            else -> {
                emit("git: '$subcommand' is not supported by the JGit-backed engine.\n", TerminalLine.Stream.STDERR)
                emit("Run 'git help' to list the supported subcommands.\n", TerminalLine.Stream.STDERR)
                1
            }
        }
    }

    private fun renderStatus(status: com.projecthub.gitforge.git.RepoStatus, short: Boolean): String {
        val sb = StringBuilder()
        if (!short) {
            sb.append("On branch ${status.branch}\n")
            if (status.aheadCount > 0 || status.behindCount > 0) {
                sb.append("Your branch is ")
                if (status.aheadCount > 0) sb.append("ahead of 'origin/${status.branch}' by ${status.aheadCount} commit(s)")
                if (status.aheadCount > 0 && status.behindCount > 0) sb.append(" and ")
                if (status.behindCount > 0) sb.append("behind by ${status.behindCount} commit(s)")
                sb.append(".\n")
            }
            if (status.files.isEmpty()) {
                sb.append("\nnothing to commit, working tree clean\n")
                return sb.toString()
            }
            sb.append("\nChanges:\n")
        }

        status.staged.forEach { sb.append(if (short) "A  ${it.relativePath}\n" else "\tstaged:   ${it.relativePath}\n") }
        status.unstaged.forEach { sb.append(if (short) " M ${it.relativePath}\n" else "\tmodified: ${it.relativePath}\n") }
        status.untracked.forEach { sb.append(if (short) "?? ${it.relativePath}\n" else "\tuntracked: ${it.relativePath}\n") }
        status.conflicting.forEach { sb.append(if (short) "UU ${it.relativePath}\n" else "\tconflict: ${it.relativePath}\n") }
        return sb.toString()
    }

    private fun renderLog(commits: List<com.projecthub.gitforge.git.CommitInfo>, oneline: Boolean): String =
        commits.joinToString("\n") { commit ->
            if (oneline) {
                "${commit.shortSha} ${commit.message.lineSequence().first()}"
            } else {
                buildString {
                    append("commit ${commit.sha}\n")
                    append("Author: ${commit.authorName} <${commit.authorEmail}>\n")
                    append("Date:   ${java.util.Date(commit.timestampSeconds * 1000)}\n\n")
                    append(commit.message.lineSequence().joinToString("\n") { "    $it" })
                    append("\n")
                }
            }
        } + "\n"

    private fun renderDiff(diffs: List<com.projecthub.gitforge.git.FileDiff>): String {
        val sb = StringBuilder()
        for (diff in diffs) {
            sb.append("diff --git a/${diff.oldPath ?: diff.relativePath} b/${diff.newPath ?: diff.relativePath}\n")
            diff.lines.forEach { line ->
                sb.append(line.text).append('\n')
            }
        }
        return sb.toString()
    }

    private fun versionString(): String = try {
        org.eclipse.jgit.internal.JGitText.get().toString().let { "2.0 (JGit 6.7.0)" }
    } catch (_: Throwable) {
        "2.0 (JGit 6.7.0)"
    }

    private fun usage(): String = """
        usage: git <subcommand> [options]

        Supported in this build (JGit-backed, no native git binary):
          init [dir]              create a repository
          status [--short]        show working-tree state
          log [-n N] [--oneline]  show history
          diff [--cached] [path]  show changes
          add <path>...           stage files
          reset <path>...         unstage files
          commit -m "message"     commit staged changes
          branch [-d|-m] [names]  list, delete or rename branches
          checkout|switch <name>  switch branch (-b to create)
          remote                  list remotes
          fetch | pull            sync from origin
          rev-parse <rev>         resolve a revision
          config user.name|user.email
          clone <url> [dir]       clone a repository

        Push is intentionally not available from the terminal: use the Git screen so
        GitForge can attach your credential without writing it into the shell.
    """.trimIndent() + "\n"
}
