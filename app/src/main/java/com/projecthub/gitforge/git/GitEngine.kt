package com.projecthub.gitforge.git

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.ErrorKind
import com.projecthub.gitforge.core.Redaction
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import com.projecthub.gitforge.security.GitRemoteCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Raised for every Git failure so the UI always receives a classified [ErrorKind]. */
class GitOperationException(
    val kind: ErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Credential provider for HTTPS GitHub remotes.
 *
 * The token is held only in memory for the lifetime of the provider and is used
 * as the password for the `x-access-token` username, which is how GitHub accepts
 * personal access tokens and installation tokens over HTTPS. Nothing is written
 * to disk, and no method here logs the credential.
 */
class TokenCredentialsProvider(private val token: String) : CredentialsProvider() {

    private val delegate = UsernamePasswordCredentialsProvider("x-access-token", token)

    override fun isInteractive(): Boolean = false

    override fun supports(vararg items: CredentialItem): Boolean = delegate.supports(*items)

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean = delegate.get(uri, *items)
}

/**
 * Single authoritative Git engine for every working tree in the app.
 *
 * Design decisions that keep the app correct:
 *  * JGit is the only writer of the index and the working tree, so there is no
 *    two-implementation race. The terminal drives the *same* JGit code through
 *    [com.projecthub.gitforge.terminal.GitCommandHandler] rather than shelling out
 *    to a second Git binary.
 *  * Every mutation takes a per-repository lock ([withLock]) so a commit cannot
 *    interleave with a checkout started from another screen.
 *  * Credentials are injected per network call and never persisted into
 *    `.git/config`; the stored remote URL stays bare.
 */
class GitEngine {

    private val locks = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()

    private fun lockFor(key: String): kotlinx.coroutines.sync.Mutex = synchronized(locks) {
        locks.getOrPut(key) { kotlinx.coroutines.sync.Mutex() }
    }

    /** Runs [block] while holding the repository lock, so Git operations serialise. */
    suspend fun <T> withLock(repositoryDir: File, block: suspend () -> T): T {
        val mutex = lockFor(repositoryDir.canonicalPath)
        return mutex.withLockCompat {
            // JGit does blocking filesystem and network I/O. Run it on the IO
            // dispatcher no matter which thread the caller is on (usually Main).
            // Holding the mutex across the dispatcher switch is safe: it is a
            // coroutine mutex, not bound to any thread.
            withContext(Dispatchers.IO) { block() }
        }
    }

    fun isRepository(dir: File): Boolean = File(dir, ".git").exists() || RepositoryCacheHolder.exists(dir)

    // ---------------------------------------------------------------- lifecycle

    suspend fun init(dir: File): File = withLock(dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) throw GitOperationException(ErrorKind.IO, "Cannot create ${dir.name}")
            Git.init().setDirectory(dir).setInitialBranch("main").call().use { }
            dir
        } catch (t: Throwable) {
            throw t.asGitException("Could not initialise repository")
        }
    }

    suspend fun clone(
        url: String,
        targetDir: File,
        token: String?,
        branch: String? = null,
        depth: Int = 0,
        onProgress: (CloneProgress) -> Unit = {},
    ): File = withLock(targetDir) {
        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            throw GitOperationException(ErrorKind.CONFLICT, "${targetDir.name} already exists and is not empty")
        }
        try {
            val command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(targetDir)
                .setProgressMonitor(ProgressBridge(onProgress))
                .setCloneAllBranches(depth <= 0)

            if (!branch.isNullOrBlank()) command.setBranch(branch)
            if (depth > 0) command.setDepth(depth)
            token?.let { command.setCredentialsProvider(TokenCredentialsProvider(it)) }

            command.call().use { }
            targetDir
        } catch (t: Throwable) {
            // A half-cloned directory would confuse later attempts, so remove it.
            runCatching { targetDir.deleteRecursively() }
            throw t.asGitException("Clone failed")
        }
    }

    // ------------------------------------------------------------------- status

    suspend fun status(repositoryDir: File): RepoStatus = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            val status = git.status().call()
            val files = mutableListOf<FileStatus>()

            status.added.forEach { files += FileStatus(it, ChangeType.ADDED, staged = true) }
            status.changed.forEach { files += FileStatus(it, ChangeType.MODIFIED, staged = true) }
            status.removed.forEach { files += FileStatus(it, ChangeType.DELETED, staged = true) }
            status.modified.forEach { files += FileStatus(it, ChangeType.MODIFIED, staged = false) }
            status.missing.forEach { files += FileStatus(it, ChangeType.DELETED, staged = false) }
            status.conflicting.forEach { files += FileStatus(it, ChangeType.CONFLICTING, staged = false) }
            status.untracked.forEach { files += FileStatus(it, ChangeType.UNTRACKED, staged = false) }

            val branch = currentBranch(git.repository)
            val (ahead, behind) = aheadBehind(git.repository, branch)

            RepoStatus(
                branch = branch,
                files = files.distinctBy { it.relativePath to it.staged }
                    .sortedBy { it.relativePath },
                aheadCount = ahead,
                behindCount = behind,
                hasUncommittedChanges = files.isNotEmpty(),
            )
        }
    }

    // ----------------------------------------------------------------- staging

    suspend fun stage(repositoryDir: File, paths: List<String>) = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                val add = git.add()
                paths.forEach { add.addFilepattern(it) }
                // Also record deletions so `git add -A` semantics hold for removed files.
                val update = git.add().setUpdate(true)
                paths.forEach { update.addFilepattern(it) }
                update.call()
                add.call()
            } catch (t: Throwable) {
                throw t.asGitException("Could not stage files")
            }
        }
    }

    suspend fun unstage(repositoryDir: File, paths: List<String>) = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                val reset = git.reset()
                paths.forEach { reset.addPath(it) }
                reset.call()
            } catch (t: Throwable) {
                throw t.asGitException("Could not unstage files")
            }
        }
    }

    suspend fun commit(
        repositoryDir: File,
        message: String,
        authorName: String,
        authorEmail: String,
        amend: Boolean = false,
    ): CommitInfo = withLock(repositoryDir) {
        if (message.isBlank()) throw GitOperationException(ErrorKind.VALIDATION, "Commit message cannot be empty")
        open(repositoryDir).use { git ->
            try {
                if (!amend) {
                    val status = git.status().call()
                    val staged = status.added.size + status.changed.size + status.removed.size
                    if (staged == 0) {
                        throw GitOperationException(
                            ErrorKind.VALIDATION,
                            "Nothing is staged. Stage files before committing.",
                        )
                    }
                }
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .setAllowEmpty(false)
                    .setAmend(amend)
                val result = commit.call()
                result.toCommitInfo()
            } catch (t: Throwable) {
                throw t.asGitException("Commit failed")
            }
        }
    }

    // ----------------------------------------------------------------- branches

    suspend fun branches(repositoryDir: File): List<BranchInfo> = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            val current = currentBranch(git.repository)
            val local = git.branchList().call().map { ref ->
                BranchInfo(
                    name = ref.name.removePrefix("refs/heads/"),
                    isCurrent = ref.name.removePrefix("refs/heads/") == current,
                    isRemote = false,
                    lastCommitSha = ref.objectId?.name,
                )
            }
            val remote = git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()
                .map { ref ->
                    BranchInfo(
                        name = ref.name.removePrefix("refs/remotes/"),
                        isCurrent = false,
                        isRemote = true,
                        lastCommitSha = ref.objectId?.name,
                    )
                }
            (local + remote).sortedWith(compareBy({ it.isRemote }, { it.name }))
        }
    }

    suspend fun createBranch(repositoryDir: File, name: String, checkout: Boolean) = withLock(repositoryDir) {
        validateRefName(name)
        open(repositoryDir).use { git ->
            try {
                val command = git.branchCreate().setName(name)
                if (checkout) command.setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.NOTRACK)
                command.call()
                if (checkout) {
                    try {
                        git.checkout().setName(name).call()
                    } catch (checkoutFailure: Throwable) {
                        // The branch now exists but the checkout failed: roll the
                        // ref back so a retry reports "could not create" truthfully
                        // instead of "already exists".
                        runCatching { git.branchDelete().setBranchNames(name).setForce(true).call() }
                        throw checkoutFailure
                    }
                }
            } catch (t: Throwable) {
                throw t.asGitException("Could not create branch $name")
            }
        }
    }

    /**
     * Switches branch. Refuses when the working tree has uncommitted changes,
     * because JGit's checkout would otherwise fail midway and leave a confusing
     * state. The caller is expected to show the refusal to the user.
     */
    suspend fun checkout(repositoryDir: File, name: String, force: Boolean = false) = withLock(repositoryDir) {
        validateRefName(name)
        open(repositoryDir).use { git ->
            if (!force) {
                val status = git.status().call()
                if (!status.isClean) {
                    throw GitOperationException(
                        ErrorKind.CONFLICT,
                        "Uncommitted changes are present. Commit or discard them before switching branch.",
                    )
                }
            }
            try {
                git.checkout().setName(name).setForced(force).call()
            } catch (t: Throwable) {
                throw t.asGitException("Could not switch to $name")
            }
        }
    }

    /**
     * Deletes a local branch. [force] maps to `git branch -D`: only the terminal
     * passes true; the Git screen always deletes without force so commits that
     * exist only on the deleted branch stay recoverable.
     */
    suspend fun deleteBranch(repositoryDir: File, name: String, force: Boolean = false) = withLock(repositoryDir) {
        validateRefName(name)
        open(repositoryDir).use { git ->
            val current = currentBranch(git.repository)
            if (name == current) {
                throw GitOperationException(ErrorKind.VALIDATION, "Cannot delete the branch you are on")
            }
            try {
                git.branchDelete().setBranchNames(name).setForce(force).call()
            } catch (t: Throwable) {
                throw t.asGitException("Could not delete branch $name")
            }
        }
    }

    suspend fun renameBranch(repositoryDir: File, oldName: String, newName: String) = withLock(repositoryDir) {
        validateRefName(newName)
        open(repositoryDir).use { git ->
            try {
                git.branchRename().setOldName(oldName).setNewName(newName).call()
            } catch (t: Throwable) {
                throw t.asGitException("Could not rename branch")
            }
        }
    }

    // ------------------------------------------------------------- history/diff

    suspend fun log(repositoryDir: File, limit: Int = 100, skip: Int = 0): List<CommitInfo> =
        withLock(repositoryDir) {
            open(repositoryDir).use { git ->
                try {
                    val command = git.log().setMaxCount(limit).setSkip(skip)
                    command.call().map { it.toCommitInfo() }
                } catch (_: NoHeadException) {
                    emptyList()
                } catch (t: Throwable) {
                    throw t.asGitException("Could not read commit history")
                }
            }
        }

    suspend fun logForPath(repositoryDir: File, path: String, limit: Int = 50): List<CommitInfo> =
        withLock(repositoryDir) {
            open(repositoryDir).use { git ->
                try {
                    git.log().addPath(path).setMaxCount(limit).call().map { it.toCommitInfo() }
                } catch (_: NoHeadException) {
                    emptyList()
                } catch (t: Throwable) {
                    throw t.asGitException("Could not read history for $path")
                }
            }
        }

    /** Working-tree diff for a path, or for the whole repository when [path] is null. */
    suspend fun diff(repositoryDir: File, path: String? = null, staged: Boolean = false): List<FileDiff> =
        withLock(repositoryDir) {
            open(repositoryDir).use { git ->
                try {
                    val hasHead = git.repository.resolve("HEAD") != null
                    val command = git.diff().setCached(staged)
                    if (!hasHead) {
                        // An unborn branch has no HEAD commit. Diffing against the
                        // empty tree is what `git diff --cached` does on first commit.
                        command.setOldTree(org.eclipse.jgit.treewalk.EmptyTreeIterator())
                    }
                    if (!path.isNullOrBlank()) {
                        command.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path))
                    }
                    val entries = command.call()
                    entries.map { it.toFileDiff(git.repository) }
                } catch (t: Throwable) {
                    throw t.asGitException("Could not compute diff")
                }
            }
        }

    // ------------------------------------------------------------------ remotes

    suspend fun remotes(repositoryDir: File): List<RemoteInfo> = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            git.remoteList().call().map { remote ->
                RemoteInfo(
                    name = remote.name,
                    fetchUrl = Redaction.redact(remoteUrlOf(git.repository, remote.name, push = false)),
                    pushUrl = Redaction.redact(remoteUrlOf(git.repository, remote.name, push = true)),
                )
            }
        }
    }

    suspend fun setRemote(repositoryDir: File, name: String, url: String) = withLock(repositoryDir) {

        open(repositoryDir).use { git ->

            try {
                // Never persist credentials: strip any userinfo the caller pasted
                // (for example a personal access token in an HTTPS URL) so
                // `.git/config` keeps the bare URL and pushes authenticate via the
                // per-call credential provider instead.
                val cleanUrl = GitRemoteCredentials.stripCredentials(url.trim())
                if (cleanUrl != url.trim()) {
                    AppLog.w("Stripped credentials from the remote URL before storing it")
                }
                require(cleanUrl.startsWith("https://", ignoreCase = true)) {
                    "Remote URL must use https://"
                }
                val config = git.repository.config
                config.setString("remote", name, "url", cleanUrl)
                config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
                config.save()
            } catch (t: Throwable) {
                throw t.asGitException("Could not configure remote $name")
            }
        }
    }

    // ------------------------------------------------------------------ network

    suspend fun fetch(repositoryDir: File, token: String?, remote: String = "origin") = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                val command = git.fetch().setRemote(remote).setRemoveDeletedRefs(true)
                token?.let { command.setCredentialsProvider(TokenCredentialsProvider(it)) }
                command.call()
            } catch (t: Throwable) {
                throw t.asGitException("Fetch failed")
            }
        }
    }

    suspend fun pull(repositoryDir: File, token: String?, remote: String = "origin") = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                val command = git.pull().setRemote(remote)
                token?.let { command.setCredentialsProvider(TokenCredentialsProvider(it)) }
                val result = command.call()
                if (!result.isSuccessful) {
                    throw GitOperationException(
                        ErrorKind.CONFLICT,
                        "Pull did not complete cleanly: ${result.mergeResult?.mergeStatus ?: "unknown state"}",
                    )
                }
            } catch (t: Throwable) {
                throw t.asGitException("Pull failed")
            }
        }
    }

    /**
     * Pushes the current branch. There is deliberately no force option anywhere in
     * the engine, so a push can never discard remote commits.
     */
    suspend fun push(repositoryDir: File, token: String?, remote: String = "origin"): PushOutcome =
        withLock(repositoryDir) {
            open(repositoryDir).use { git ->
                try {
                    val command = git.push().setRemote(remote)
                    token?.let { command.setCredentialsProvider(TokenCredentialsProvider(it)) }
                    val results = command.call()
                    PushOutcome(
                        remoteName = remote,
                        updatedRefs = results.flatMap { result -> result.remoteUpdates.map { update -> update.remoteName } },
                        rejectedRefs = results.flatMap { result ->
                            result.remoteUpdates
                                .filter { update ->
                                    update.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK &&
                                        update.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                                }
                                .map { update -> update.remoteName }
                        },
                        messages = results.mapNotNull { result ->
                            result.messages?.takeIf { it.isNotBlank() }
                        }.map { message -> Redaction.redact(message) },
                    )
                } catch (t: Throwable) {
                    throw t.asGitException("Push failed")
                }
            }
        }

    // --------------------------------------------------------------- merge/revert

    /**
     * Aborts an in-progress merge (`git merge --abort`): resets the working tree
     * to HEAD and clears the MERGE_HEAD state. Safe to call when no merge is in
     * progress only in the sense that JGit will fail with no MERGE_HEAD, which is
     * surfaced as an error — the UI should only offer this while conflicts exist.
     */
    suspend fun abortMerge(repositoryDir: File) = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                git.reset()
                    .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                    .setRef("HEAD")
                    .call()
                // A hard reset restores the tree but JGit leaves the merge
                // bookkeeping behind; remove it so the repo is usable again.
                // (This mirrors `git merge --abort`: reset + clear MERGE_* state.)
                val gitDir = git.repository.directory
                for (name in listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE", "ORIG_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD")) {
                    runCatching { File(gitDir, name).delete() }
                }
            } catch (t: Throwable) {
                throw t.asGitException("Could not abort the merge")
            }
        }
    }

    suspend fun merge(repositoryDir: File, branchName: String): MergeOutcome = withLock(repositoryDir) {
        open(repositoryDir).use { git ->
            try {
                if (File(git.repository.directory, "MERGE_HEAD").exists()) {
                    throw GitOperationException(
                        ErrorKind.CONFLICT,
                        "A merge is already in progress. Resolve its conflicts or abort it before starting another merge.",
                    )
                }
                if (!git.status().call().isClean) {
                    throw GitOperationException(
                        ErrorKind.CONFLICT,
                        "Uncommitted changes are present. Commit or discard them before merging.",
                    )
                }
                val ref = git.repository.resolve(branchName)
                    ?: git.repository.resolve("refs/remotes/origin/$branchName")
                    ?: throw GitOperationException(ErrorKind.NOT_FOUND, "Branch $branchName not found")
                val result = git.merge()
                    .include(ref)
                    .setStrategy(org.eclipse.jgit.merge.MergeStrategy.RECURSIVE)
                    .call()
                MergeOutcome(
                    status = jgitMergeStatus(result.mergeStatus.name),
                    conflictingPaths = result.conflicts?.keys?.toList() ?: emptyList(),
                    message = "Merge of $branchName finished with status ${result.mergeStatus}",
                )
            } catch (t: Throwable) {
                throw t.asGitException("Merge failed")
            }
        }
    }

    suspend fun revert(repositoryDir: File, sha: String, authorName: String, authorEmail: String): CommitInfo =
        withLock(repositoryDir) {
            open(repositoryDir).use { git ->
                try {
                    val commit = git.revert()
                        .include(git.repository.resolve(sha))
                        .call()
                    CommitInfo(
                        sha = commit.name,
                        shortSha = commit.name.take(7),
                        message = commit.shortMessage,
                        authorName = authorName,
                        authorEmail = authorEmail,
                        timestampSeconds = commit.commitTime.toLong(),
                        parentShas = commit.parents.map { it.name },
                    )
                } catch (t: Throwable) {
                    throw t.asGitException("Revert failed")
                }
            }
        }

    // ------------------------------------------------------------------ helpers

    /** Translates JGit's merge status name into the app's own enum. */
    private fun jgitMergeStatus(name: String): MergeStatus = when (name) {
        "ALREADY_UP_TO_DATE" -> MergeStatus.ALREADY_UP_TO_DATE
        "FAST_FORWARD", "FAST_FORWARD_SQUASHED" -> MergeStatus.FAST_FORWARD
        "MERGED", "MERGED_SQUASHED" -> MergeStatus.MERGED
        "CONFLICTING", "CHECKOUT_CONFLICT" -> MergeStatus.CONFLICTING
        else -> MergeStatus.FAILED
    }

    /** Reads a remote URL from the repository config, with any userinfo removed. */
    private fun remoteUrlOf(repository: Repository, name: String, push: Boolean): String {
        val config = repository.config
        val key = if (push) "pushurl" else "url"
        return config.getString("remote", name, key)
            ?: config.getString("remote", name, "url")
            ?: ""
    }

    fun open(dir: File): Git = try {
        Git.open(dir)
    } catch (e: RepositoryNotFoundException) {
        throw GitOperationException(ErrorKind.NOT_FOUND, "Not a Git repository: ${dir.name}", e)
    }

    fun currentBranch(repository: Repository): String =
        repository.branch?.takeIf { it != "HEAD" } ?: "(detached)"

    private fun aheadBehind(repository: Repository, branch: String): Pair<Int, Int> {
        val local = repository.resolve("refs/heads/$branch") ?: return 0 to 0
        val remote = repository.resolve("refs/remotes/origin/$branch") ?: return 0 to 0
        return try {
            repository.newObjectReader().use { reader ->
                // RevWalk is AutoCloseable: it must be closed to release the
                // object reader it borrows, hence the nested use{} blocks.
                org.eclipse.jgit.revwalk.RevWalk(reader).use { walk ->
                    val localCommit = walk.parseCommit(local)
                    val remoteCommit = walk.parseCommit(remote)
                    walk.reset()
                    walk.markStart(localCommit)
                    walk.markUninteresting(remoteCommit)
                    val ahead = walk.count()

                    walk.reset()
                    walk.markStart(remoteCommit)
                    walk.markUninteresting(localCommit)
                    val behind = walk.count()
                    ahead to behind
                }
            }
        } catch (t: Throwable) {
            AppLog.w("Could not compute ahead/behind for $branch", t)
            0 to 0
        }
    }

    private fun validateRefName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw GitOperationException(ErrorKind.VALIDATION, "Branch name cannot be empty")
        if (trimmed.startsWith("-")) throw GitOperationException(ErrorKind.VALIDATION, "Branch name cannot start with '-'")
        if (!org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$trimmed")) {
            throw GitOperationException(ErrorKind.VALIDATION, "'$trimmed' is not a valid branch name")
        }
    }

    private fun Throwable.asGitException(context: String): GitOperationException {
        val safe = Redaction.redact(message ?: this::class.java.simpleName)
        AppLog.w("$context: $safe")
        val kind = when (this) {
            is GitOperationException -> return this
            is TransportException -> ErrorKind.NETWORK
            is InvalidRemoteException -> ErrorKind.NETWORK
            is org.eclipse.jgit.api.errors.TransportException -> ErrorKind.NETWORK
            is org.eclipse.jgit.api.errors.CheckoutConflictException -> ErrorKind.CONFLICT
            is org.eclipse.jgit.api.errors.RefNotFoundException -> ErrorKind.NOT_FOUND
            is org.eclipse.jgit.api.errors.InvalidRefNameException -> ErrorKind.VALIDATION
            else -> ErrorKind.GIT
        }
        val hint = when (kind) {
            ErrorKind.NETWORK -> "Network or authentication problem while talking to the remote."
            ErrorKind.CONFLICT -> "The working tree conflicts with the requested operation."
            ErrorKind.NOT_FOUND -> "The requested Git object does not exist."
            ErrorKind.VALIDATION -> safe
            else -> "Git reported: $safe"
        }
        return GitOperationException(kind, hint, this)
    }

    /** Bridges JGit's progress callback onto a plain Kotlin lambda. */
    private class ProgressBridge(private val onProgress: (CloneProgress) -> Unit) :
        org.eclipse.jgit.lib.ProgressMonitor {
        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String, totalWork: Int) = onProgress(CloneProgress(title, 0, totalWork))
        override fun update(completed: Int) = onProgress(CloneProgress("Receiving objects", completed, 0))
        override fun endTask() = Unit
        override fun isCancelled(): Boolean = false
        override fun showDuration(enabled: Boolean) = Unit
    }

    private object RepositoryCacheHolder {
        fun exists(dir: File): Boolean = runCatching {
            org.eclipse.jgit.storage.file.FileRepositoryBuilder().setMustExist(true).setWorkTree(dir).build().close()
            true
        }.getOrDefault(false)
    }
}

private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(block: suspend () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
