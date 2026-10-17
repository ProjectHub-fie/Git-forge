package com.projecthub.gitforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.gitforge.core.AppResult
import com.projecthub.gitforge.core.appResult
import com.projecthub.gitforge.data.db.RepositoryEntity
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.git.BranchInfo
import com.projecthub.gitforge.git.CommitInfo
import com.projecthub.gitforge.git.FileDiff
import com.projecthub.gitforge.git.FileStatus
import com.projecthub.gitforge.git.MergeOutcome
import com.projecthub.gitforge.git.MergeStatus
import com.projecthub.gitforge.git.PushOutcome
import com.projecthub.gitforge.git.RepoStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Which tab of the Git screen is showing. */
enum class GitSection { CHANGES, HISTORY, BRANCHES, REMOTES }

data class GitUiState(
    val repositoryId: Long = -1L,
    val repositoryName: String = "",
    val repositoryRoot: File? = null,
    val status: RepoStatus? = null,
    val loading: Boolean = false,
    val busyMessage: String? = null,
    val section: GitSection = GitSection.CHANGES,
    val selectedDiff: FileDiff? = null,
    val diffFor: String? = null,
    val commits: List<CommitInfo> = emptyList(),
    val commitsLoaded: Boolean = false,
    val branches: List<BranchInfo> = emptyList(),
    val remotes: List<com.projecthub.gitforge.git.RemoteInfo> = emptyList(),
    val commitMessage: String = "",
    val commitDescription: String = "",
    val lastPushOutcome: PushOutcome? = null,
    val lastMergeOutcome: MergeOutcome? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val pendingConfirmation: PendingGitAction? = null,
) {
    val stagedFiles: List<FileStatus> get() = status?.staged.orEmpty()
    val unstagedFiles: List<FileStatus> get() = status?.unstaged.orEmpty()
    val untrackedFiles: List<FileStatus> get() = status?.untracked.orEmpty()
    val conflictingFiles: List<FileStatus> get() = status?.conflicting.orEmpty()
    val canCommit: Boolean get() = stagedFiles.isNotEmpty() && commitMessage.isNotBlank()
}

/**
 * A destructive or remote-affecting action awaiting explicit confirmation.
 * The explanation is stored with it so the dialog always states the real risk.
 */
data class PendingGitAction(
    val title: String,
    val explanation: String,
    val confirmLabel: String,
    val detail: String?,
    val action: () -> Unit,
)

/**
 * Drives the Git screen: status, staging, commits, branches, history, remotes,
 * merge and push/pull.
 *
 * Every mutating operation is serialised by [com.projecthub.gitforge.git.GitEngine]'s
 * per-repository lock, so a commit started here cannot interleave with a checkout
 * started from the terminal. Destructive actions are never executed without
 * confirmation, and a push is never forced.
 */
class GitViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    fun openRepository(repositoryId: Long) {
        viewModelScope.launch {
            val entity = container.database.repositoryDao().byId(repositoryId) ?: run {
                _state.update { it.copy(errorMessage = "That repository is no longer registered.") }
                return@launch
            }
            val root = runCatching { container.workspaceManager.resolveRelative(entity.localPath) }.getOrNull()
            if (root == null || !root.isDirectory) {
                _state.update { it.copy(errorMessage = "The folder for ${entity.displayName} is missing on disk.") }
                return@launch
            }
            _state.update {
                it.copy(
                    repositoryId = repositoryId,
                    repositoryName = entity.displayName,
                    repositoryRoot = root,
                    commitsLoaded = false,
                    commits = emptyList(),
                    branches = emptyList(),
                    remotes = emptyList(),
                )
            }
            refresh()
        }
    }

    fun refresh() {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = appResult { container.gitEngine.status(root) }
            when (result) {
                is AppResult.Success -> {
                    container.database.repositoryDao().updateBranch(_state.value.repositoryId, result.data.branch)
                    _state.update { it.copy(loading = false, status = result.data) }
                }
                is AppResult.Failure -> _state.update { it.copy(loading = false, errorMessage = result.error.message) }
            }
        }
    }

    fun selectSection(section: GitSection) {
        _state.update { it.copy(section = section) }
        when (section) {
            GitSection.HISTORY -> if (!_state.value.commitsLoaded) loadHistory()
            GitSection.BRANCHES -> loadBranches()
            GitSection.REMOTES -> loadRemotes()
            GitSection.CHANGES -> refresh()
        }
    }

    // ------------------------------------------------------------------- staging

    fun stageFile(path: String) = mutate("Staged ${path.substringAfterLast('/')}") {
        container.gitEngine.stage(it, listOf(path))
    }

    fun unstageFile(path: String) = mutate("Unstaged ${path.substringAfterLast('/')}") {
        container.gitEngine.unstage(it, listOf(path))
    }

    fun stageAll() {
        val paths = (_state.value.stagedFiles + _state.value.unstagedFiles + _state.value.untrackedFiles)
            .map { it.relativePath }
            .distinct()
        if (paths.isEmpty()) {
            _state.update { it.copy(message = "There is nothing to stage.") }
            return
        }
        mutate("Staged ${paths.size} file(s)") { container.gitEngine.stage(it, paths) }
    }

    fun unstageAll() {
        val paths = _state.value.stagedFiles.map { it.relativePath }
        if (paths.isEmpty()) {
            _state.update { it.copy(message = "There is nothing to unstage.") }
            return
        }
        mutate("Unstaged ${paths.size} file(s)") { container.gitEngine.unstage(it, paths) }
    }

    // ------------------------------------------------------------------ diff view

    fun showDiff(path: String, staged: Boolean) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.gitEngine.diff(root, path, staged) }
            when (result) {
                is AppResult.Success -> {
                    val diff = result.data.firstOrNull()
                    _state.update {
                        it.copy(
                            selectedDiff = diff,
                            diffFor = path,
                            errorMessage = if (diff == null) "No textual differences to show for $path." else null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    fun dismissDiff() = _state.update { it.copy(selectedDiff = null, diffFor = null) }

    // -------------------------------------------------------------------- commit

    fun setCommitMessage(message: String) = _state.update { it.copy(commitMessage = message) }

    fun setCommitDescription(description: String) = _state.update { it.copy(commitDescription = description) }

    fun commit() {
        val current = _state.value
        val root = current.repositoryRoot ?: return
        if (current.commitMessage.isBlank()) {
            _state.update { it.copy(errorMessage = "Enter a commit message before committing.") }
            return
        }
        if (current.stagedFiles.isEmpty()) {
            _state.update { it.copy(errorMessage = "Stage at least one file before committing.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Committing…") }
            val (authorName, authorEmail) = container.gitIdentity
            val message = buildString {
                append(current.commitMessage.trim())
                if (current.commitDescription.isNotBlank()) {
                    append("\n\n").append(current.commitDescription.trim())
                }
            }
            val result = appResult {
                container.gitEngine.commit(root, message, authorName, authorEmail)
            }
            when (result) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            busyMessage = null,
                            commitMessage = "",
                            commitDescription = "",
                            message = "Committed ${result.data.shortSha}",
                            commitsLoaded = false,
                        )
                    }
                    refresh()
                    loadHistory()
                }
                is AppResult.Failure -> _state.update { it.copy(busyMessage = null, errorMessage = result.error.message) }
            }
        }
    }

    // ------------------------------------------------------------------ history

    fun loadHistory() {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val result = appResult { container.gitEngine.log(root, limit = 100) }
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, commits = result.data, commitsLoaded = true)
                }
                is AppResult.Failure -> _state.update { it.copy(loading = false, errorMessage = result.error.message) }
            }
        }
    }

    fun loadMoreHistory() {
        val root = _state.value.repositoryRoot ?: return
        val skip = _state.value.commits.size
        if (skip == 0) return loadHistory()
        viewModelScope.launch {
            val result = appResult { container.gitEngine.log(root, limit = 100, skip = skip) }
            if (result is AppResult.Success && result.data.isNotEmpty()) {
                _state.update { it.copy(commits = it.commits + result.data) }
            }
        }
    }

    // ----------------------------------------------------------------- branches

    fun loadBranches() {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.gitEngine.branches(root) }
            when (result) {
                is AppResult.Success -> _state.update { it.copy(branches = result.data) }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    fun createBranch(name: String, checkout: Boolean) {
        mutate("Created branch $name") { container.gitEngine.createBranch(it, name, checkout) }
    }

    fun renameBranch(oldName: String, newName: String) {
        mutate("Renamed $oldName to $newName") { container.gitEngine.renameBranch(it, oldName, newName) }
    }

    /**
     * Switches branch. Uncommitted changes block the switch: the engine refuses, and
     * the screen explains why rather than discarding work.
     */
    fun switchBranch(name: String) {
        val root = _state.value.repositoryRoot ?: return
        val dirty = _state.value.status?.hasUncommittedChanges == true
        if (dirty) {
            _state.update {
                it.copy(
                    pendingConfirmation = PendingGitAction(
                        title = "Uncommitted changes",
                        explanation = "Switching to '$name' is blocked because the working tree has uncommitted changes. " +
                            "Commit or discard them first.",
                        confirmLabel = "Close",
                        detail = _state.value.status?.files?.take(8)?.joinToString("\n") { "${it.statusLabel} ${it.relativePath}" },
                        action = {},
                    ),
                )
            }
            return
        }
        mutate("Switched to $name") { container.gitEngine.checkout(it, name) }
    }

    /** Deleting a branch is destructive, so it always goes through confirmation. */
    fun requestDeleteBranch(name: String) {
        val current = _state.value.status?.branch
        if (name == current) {
            _state.update { it.copy(errorMessage = "You cannot delete the branch you are currently on.") }
            return
        }
        _state.update {
            it.copy(
                pendingConfirmation = PendingGitAction(
                    title = "Delete branch '$name'",
                    explanation = "This deletes the local branch '$name'. Commits that exist only on this branch " +
                        "will no longer be reachable from any branch.",
                    confirmLabel = "Delete branch",
                    detail = name,
                    action = { mutate("Deleted branch $name") { dir -> container.gitEngine.deleteBranch(dir, name) } },
                ),
            )
        }
    }

    /** Aborts an in-progress merge, restoring the tree to HEAD. Offered while conflicts exist. */
    fun abortMerge() {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Aborting merge…") }
            val result = appResult { container.gitEngine.abortMerge(root) }
            when (result) {
                is AppResult.Success -> {
                    _state.update { it.copy(busyMessage = null, lastMergeOutcome = null, message = "Merge aborted.") }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(busyMessage = null, errorMessage = result.error.message) }
            }
        }
    }

    fun mergeBranch(name: String) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Merging $name…") }
            val result = appResult { container.gitEngine.merge(root, name) }
            when (result) {
                is AppResult.Success -> {
                    val outcome = result.data
                    val message = when (outcome.status) {
                        MergeStatus.ALREADY_UP_TO_DATE -> "Already up to date."
                        MergeStatus.FAST_FORWARD -> "Fast-forwarded to $name."
                        MergeStatus.MERGED -> "Merged $name."
                        MergeStatus.CONFLICTING -> "Merge conflicts in ${outcome.conflictingPaths.size} file(s). Resolve them, then commit."
                        MergeStatus.FAILED -> "The merge did not complete."
                    }
                    _state.update {
                        it.copy(
                            busyMessage = null,
                            lastMergeOutcome = outcome,
                            message = message,
                            errorMessage = if (outcome.status == MergeStatus.CONFLICTING) message else null,
                        )
                    }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(busyMessage = null, errorMessage = result.error.message) }
            }
        }
    }

    /** Revert creates a new commit; it never rewrites history. */
    fun requestRevert(commit: CommitInfo) {
        _state.update {
            it.copy(
                pendingConfirmation = PendingGitAction(
                    title = "Revert ${commit.shortSha}",
                    explanation = "This creates a new commit that undoes the changes from ${commit.shortSha}. " +
                        "Existing history is preserved.",
                    confirmLabel = "Revert commit",
                    detail = commit.message.lineSequence().first(),
                    action = { doRevert(commit) },
                ),
            )
        }
    }

    private fun doRevert(commit: CommitInfo) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Reverting…") }
            val (authorName, authorEmail) = container.gitIdentity
            val result = appResult { container.gitEngine.revert(root, commit.sha, authorName, authorEmail) }
            when (result) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(busyMessage = null, message = "Reverted as ${result.data.shortSha}", commitsLoaded = false)
                    }
                    refresh()
                    loadHistory()
                }
                is AppResult.Failure -> _state.update { it.copy(busyMessage = null, errorMessage = result.error.message) }
            }
        }
    }

    // ------------------------------------------------------------------ remotes

    fun loadRemotes() {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { container.gitEngine.remotes(root) }
            when (result) {
                is AppResult.Success -> _state.update { it.copy(remotes = result.data) }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    // ------------------------------------------------------------------ network

    fun fetch() = networkOperation("Fetching…", "Fetched from origin") { dir, token ->
        container.gitEngine.fetch(dir, token)
        null
    }

    fun pull() = networkOperation("Pulling…", "Pulled from origin") { dir, token ->
        container.gitEngine.pull(dir, token)
        null
    }

    /**
     * Pushes the current branch. The engine has no force option, so this can never
     * discard remote work; a rejected non-fast-forward push is reported verbatim.
     */
    fun push() {
        val token = container.credentialStore.token()
        if (token.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Connect a GitHub account before pushing.") }
            return
        }
        networkOperation("Pushing…", null) { dir, auth ->
            val outcome = container.gitEngine.push(dir, auth)
            if (outcome.rejectedRefs.isNotEmpty()) {
                throw com.projecthub.gitforge.git.GitOperationException(
                    com.projecthub.gitforge.core.ErrorKind.CONFLICT,
                    "The remote rejected the push for ${outcome.rejectedRefs.joinToString()}. " +
                        "Pull first, then push again. GitForge never force-pushes.",
                )
            }
            outcome
        }
    }

    private fun networkOperation(
        busy: String,
        successMessage: String?,
        block: suspend (File, String?) -> PushOutcome?,
    ) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = busy) }
            val token = container.credentialStore.token()
            val result = appResult { block(root, token) }
            when (result) {
                is AppResult.Success -> {
                    val outcome = result.data
                    _state.update {
                        it.copy(
                            busyMessage = null,
                            lastPushOutcome = outcome,
                            message = successMessage ?: outcome?.let { o ->
                                "Pushed to ${o.remoteName} (${o.updatedRefs.size} ref(s) updated)"
                            } ?: "Done",
                        )
                    }
                    refresh()
                }
                is AppResult.Failure -> _state.update { it.copy(busyMessage = null, errorMessage = result.error.message) }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a mutating Git operation, then refreshes status and the affected lists. */
    private fun mutate(successMessage: String, block: suspend (File) -> Unit) {
        val root = _state.value.repositoryRoot ?: return
        viewModelScope.launch {
            val result = appResult { block(root) }
            when (result) {
                is AppResult.Success -> {
                    _state.update { it.copy(message = successMessage) }
                    refresh()
                    if (_state.value.section == GitSection.BRANCHES) loadBranches()
                }
                is AppResult.Failure -> _state.update { it.copy(errorMessage = result.error.message) }
            }
        }
    }

    fun confirmPending() {
        val action = _state.value.pendingConfirmation
        _state.update { it.copy(pendingConfirmation = null) }
        action?.action?.invoke()
    }

    fun dismissPending() = _state.update { it.copy(pendingConfirmation = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun repositoryEntity(): RepositoryEntity? =
        _state.value.repositoryId.takeIf { it > 0 }?.let { id ->
            container.database.repositoryDao().let { _ ->
                // Read from the cached local list is not available here; the id is enough
                // for the screens that need it, and the entity is loaded on demand.
                null
            }
        }
}
