package com.projecthub.gitforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.AppResult
import com.projecthub.gitforge.core.ErrorKind
import com.projecthub.gitforge.core.appResult
import com.projecthub.gitforge.core.errorOrNull
import com.projecthub.gitforge.core.getOrNull
import com.projecthub.gitforge.data.db.RepositoryEntity
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.github.GitHubRepository
import com.projecthub.gitforge.github.Paged
import com.projecthub.gitforge.git.GitOperationException
import com.projecthub.gitforge.git.RepoStatus
import com.projecthub.gitforge.workspace.WorkspaceUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** A repository row combining local state with the last known Git status. */
data class RepositoryListItem(
    val entity: RepositoryEntity,
    val status: RepoStatus?,
    val existsOnDisk: Boolean,
) {
    val isCloned: Boolean get() = existsOnDisk && !entity.isLocalOnly
}

data class RepositoriesUiState(
    val loadingLocal: Boolean = true,
    val localRepositories: List<RepositoryListItem> = emptyList(),
    val remoteRepositories: List<GitHubRepository> = emptyList(),
    val remotePage: Int = 1,
    val remoteHasMore: Boolean = false,
    val loadingRemote: Boolean = false,
    val remoteError: String? = null,
    val remoteErrorKind: ErrorKind? = null,
    val searchQuery: String = "",
    val filter: RepositoryFilter = RepositoryFilter.ALL,
    val gitHubConnected: Boolean = false,
    val gitHubLogin: String? = null,
    val cloneProgress: String? = null,
    val cloneFraction: Float? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val storageUsage: WorkspaceUsage? = null,
    val activeRepositoryId: Long = -1L,
)

enum class RepositoryFilter { ALL, LOCAL, REMOTE, PUBLIC, PRIVATE }

/**
 * Drives the Repositories screen: local workspaces, GitHub repositories, cloning,
 * searching, filtering and paging.
 *
 * Remote failures are classified so the screen can offer the right recovery action
 * (reconnect for auth, wait for rate limits, retry for network) instead of a generic
 * error string.
 */
class RepositoriesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RepositoriesUiState())
    val state: StateFlow<RepositoriesUiState> = _state.asStateFlow()

    private var remoteJob: Job? = null
    private var cloneJob: Job? = null
    private var searchJob: Job? = null

    init {
        refreshLocal()
        refreshGitHubState()
    }

    fun refreshLocal() {
        viewModelScope.launch {
            _state.update { it.copy(loadingLocal = true) }
            val entities = container.database.repositoryDao().all()
            // Filesystem and JGit reads run on the IO dispatcher so a large
            // workspace never blocks Main; the engine confines its own blocking
            // calls to IO as well.
            val items = withContext(Dispatchers.IO) {
                entities.map { entity ->
                    val dir = runCatching { container.workspaceManager.resolveRelative(entity.localPath) }.getOrNull()
                    val exists = dir?.isDirectory == true
                    val status = if (exists && !entity.isLocalOnly) {
                        runCatching { container.gitEngine.status(dir!!) }.getOrNull()
                    } else {
                        null
                    }
                    RepositoryListItem(entity, status, exists)
                }
            }
            _state.update {
                it.copy(
                    loadingLocal = false,
                    localRepositories = items,
                    storageUsage = runCatching { container.workspaceManager.usage() }.getOrNull(),
                    activeRepositoryId = container.settingsRepository.snapshot().lastActiveRepositoryId,
                )
            }
        }
    }

    fun refreshGitHubState() {
        _state.update {
            it.copy(
                gitHubConnected = container.credentialStore.hasToken(),
                gitHubLogin = container.credentialStore.login(),
            )
        }
    }

    /** Loads the first page of the signed-in user's repositories. */
    fun loadRemoteRepositories(reset: Boolean = true) {
        if (!container.credentialStore.hasToken()) {
            _state.update {
                it.copy(
                    remoteError = "Connect a GitHub account to browse your repositories.",
                    remoteErrorKind = ErrorKind.AUTH,
                    remoteRepositories = emptyList(),
                )
            }
            return
        }
        remoteJob?.cancel()
        remoteJob = viewModelScope.launch {
            val page = if (reset) 1 else _state.value.remotePage + 1
            _state.update { it.copy(loadingRemote = true, remoteError = null, remoteErrorKind = null) }
            val result = appResult {
                val query = _state.value.searchQuery.trim()
                if (query.isEmpty()) {
                    container.gitHubApi.repositories(page = page, perPage = 30)
                } else {
                    container.gitHubApi.searchRepositories(query, page = page, perPage = 30)
                }
            }
            when (result) {
                is AppResult.Success -> {
                    val paged: Paged<GitHubRepository> = result.data
                    _state.update { current ->
                        current.copy(
                            loadingRemote = false,
                            remoteRepositories = if (reset) paged.items else current.remoteRepositories + paged.items,
                            remotePage = page,
                            remoteHasMore = paged.hasMore,
                        )
                    }
                }
                is AppResult.Failure -> {
                    val kind = classify(result.error.message)
                    _state.update {
                        it.copy(
                            loadingRemote = false,
                            remoteError = result.error.message,
                            remoteErrorKind = kind,
                        )
                    }
                }
            }
        }
    }

    fun loadMoreRemote() {
        if (_state.value.remoteHasMore && !_state.value.loadingRemote) loadRemoteRepositories(reset = false)
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        // Local list filters synchronously; only hit the network when remote
        // results are visible: a token exists and the filter is not LOCAL.
        if (_state.value.filter == RepositoryFilter.LOCAL || !container.credentialStore.hasToken()) return
        // Debounce so typing does not fire a request per keystroke.
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            loadRemoteRepositories(reset = true)
        }
    }

    fun onFilterChange(filter: RepositoryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /** Filtered local list for the current filter selection. */
    fun visibleLocalRepositories(): List<RepositoryListItem> {
        val current = _state.value
        val query = current.searchQuery.trim().lowercase()
        return current.localRepositories.filter { item ->
            val matchesFilter = when (current.filter) {
                RepositoryFilter.ALL, RepositoryFilter.LOCAL -> true
                RepositoryFilter.REMOTE -> false
                RepositoryFilter.PUBLIC -> false
                RepositoryFilter.PRIVATE -> !item.entity.isLocalOnly
            }
            val matchesQuery = query.isEmpty() ||
                item.entity.displayName.lowercase().contains(query) ||
                item.entity.ownerName.lowercase().contains(query)
            matchesFilter && matchesQuery
        }
    }

    /** Filtered remote list. Local-only filters hide it entirely. */
    fun visibleRemoteRepositories(): List<GitHubRepository> {
        val current = _state.value
        if (current.filter == RepositoryFilter.LOCAL) return emptyList()
        return current.remoteRepositories.filter { repo ->
            when (current.filter) {
                RepositoryFilter.PUBLIC -> !repo.private
                RepositoryFilter.PRIVATE -> repo.private
                else -> true
            }
        }
    }

    /**
     * Clones [repository] into app-private storage and registers it locally.
     *
     * The clone is rejected up front when the target folder already exists, so a
     * second clone can never overwrite an existing working tree.
     */
    fun cloneRepository(repository: GitHubRepository) {
        if (cloneJob?.isActive == true) {
            _state.update { it.copy(message = "A clone is already running.") }
            return
        }
        val token = container.credentialStore.token()
        if (token.isNullOrBlank()) {
            _state.update { it.copy(errorMessage = "Connect a GitHub account before cloning.") }
            return
        }

        cloneJob = viewModelScope.launch {
            val owner = repository.ownerLogin
            val name = repository.name
            val target = container.workspaceManager.githubRepositoryDir(owner, name)
            val ownerName = "$owner/$name"

            if (target.exists() && target.listFiles()?.isNotEmpty() == true) {
                _state.update {
                    it.copy(errorMessage = "$ownerName is already cloned. Open it instead of cloning again.")
                }
                return@launch
            }

            _state.update { it.copy(cloneProgress = "Starting clone of $ownerName", cloneFraction = null) }
            val result = appResult {
                container.gitEngine.clone(
                    url = repository.cloneUrl ?: "https://github.com/$ownerName.git",
                    targetDir = target,
                    token = token,
                    onProgress = { progress ->
                        _state.update {
                            it.copy(
                                cloneProgress = "${progress.phase} ${progress.completed}/${progress.total}",
                                cloneFraction = progress.fraction,
                            )
                        }
                    },
                )
            }

            when (result) {
                is AppResult.Success -> {
                    val branch = runCatching { container.gitEngine.status(target).branch }
                        .getOrDefault(repository.defaultBranch)
                    val id = container.database.repositoryDao().upsert(
                        RepositoryEntity(
                            ownerName = ownerName,
                            displayName = name,
                            localPath = container.workspaceManager.relativePathOf(target),
                            remoteUrl = "https://github.com/$ownerName.git",
                            defaultBranch = repository.defaultBranch,
                            currentBranch = branch,
                            isLocalOnly = false,
                            lastOpenedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            sizeBytes = runCatching { container.workspaceManager.directorySize(target) }.getOrDefault(0),
                        ),
                    )
                    container.settingsRepository.setLastActiveRepository(id)
                    _state.update {
                        it.copy(
                            cloneProgress = null,
                            cloneFraction = null,
                            message = "Cloned $ownerName",
                            activeRepositoryId = id,
                        )
                    }
                    refreshLocal()
                }
                is AppResult.Failure -> {
                    _state.update {
                        it.copy(
                            cloneProgress = null,
                            cloneFraction = null,
                            errorMessage = result.error.message,
                        )
                    }
                }
            }
        }
    }

    /** Creates an empty local repository, then registers it. */
    fun createLocalRepository(name: String, initialiseGit: Boolean = true) {
        viewModelScope.launch {
            val result = appResult {
                if (!com.projecthub.gitforge.core.PathSafety.isSafeRepoFolderName(name)) {
                    throw IOException("'$name' is not a valid repository name.")
                }
                val dir = container.workspaceManager.localRepositoryDir(name)
                if (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
                    throw IOException("A local project called '$name' already exists.")
                }
                container.workspaceManager.ensureDir(dir)
                if (initialiseGit) container.gitEngine.init(dir)
                dir
            }

            when (result) {
                is AppResult.Success -> {
                    val dir = result.data
                    val id = container.database.repositoryDao().upsert(
                        RepositoryEntity(
                            ownerName = name,
                            displayName = name,
                            localPath = container.workspaceManager.relativePathOf(dir),
                            remoteUrl = null,
                            defaultBranch = if (initialiseGit) "main" else "",
                            currentBranch = if (initialiseGit) "main" else "",
                            isLocalOnly = true,
                            lastOpenedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    container.settingsRepository.setLastActiveRepository(id)
                    _state.update { it.copy(message = "Created local project '$name'", activeRepositoryId = id) }
                    refreshLocal()
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(errorMessage = result.error.message) }
                }
            }
        }
    }

    fun openRepository(id: Long) {
        viewModelScope.launch {
            container.database.repositoryDao().touch(id, System.currentTimeMillis())
            container.settingsRepository.setLastActiveRepository(id)
            _state.update { it.copy(activeRepositoryId = id) }
            refreshLocal()
        }
    }

    /**
     * Deletes a local working tree.
     *
     * Only called after an explicit confirmation dialog that names the repository,
     * and the folder is re-validated as being inside the app workspace before the
     * delete so a bad path in the database cannot remove anything else.
     */
    fun deleteRepository(item: RepositoryListItem) {
        viewModelScope.launch {
            val result = appResult {
                val dir = container.workspaceManager.resolveRelative(item.entity.localPath)
                if (!com.projecthub.gitforge.core.PathSafety.isInside(container.workspaceRoot, dir)) {
                    throw IOException("Refusing to delete a folder outside the GitForge workspace.")
                }
                dir.deleteRecursively()
                container.database.repositoryDao().delete(item.entity.id)
                if (container.settingsRepository.snapshot().lastActiveRepositoryId == item.entity.id) {
                    container.settingsRepository.setLastActiveRepository(-1L)
                }
            }
            if (result is AppResult.Failure) {
                _state.update { it.copy(errorMessage = result.error.message) }
            } else {
                _state.update { it.copy(message = "Deleted ${item.entity.displayName}") }
                refreshLocal()
            }
        }
    }

    /** Refreshes Git status for one repository, used after a pull or a terminal edit. */
    fun refreshStatus(id: Long) {
        viewModelScope.launch {
            val entity = container.database.repositoryDao().byId(id) ?: return@launch
            val dir = runCatching { container.workspaceManager.resolveRelative(entity.localPath) }.getOrNull() ?: return@launch
            if (!dir.isDirectory) return@launch
            val status = runCatching { container.gitEngine.status(dir) }.getOrNull() ?: return@launch
            container.database.repositoryDao().updateBranch(id, status.branch)
            _state.update { current ->
                current.copy(
                    localRepositories = current.localRepositories.map { item ->
                        if (item.entity.id == id) item.copy(status = status) else item
                    },
                )
            }
        }
    }

    fun openRepositoryDirectory(id: Long): File? {
        val entity = container.database.repositoryDao().let { _ ->
            _state.value.localRepositories.firstOrNull { it.entity.id == id }?.entity
        } ?: return null
        return runCatching { container.workspaceManager.resolveRelative(entity.localPath) }.getOrNull()
    }

    fun currentRepository(): RepositoryListItem? {
        val id = _state.value.activeRepositoryId
        return _state.value.localRepositories.firstOrNull { it.entity.id == id }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun disconnectGitHub() {
        container.credentialStore.clear()
        _state.update {
            it.copy(
                gitHubConnected = false,
                gitHubLogin = null,
                remoteRepositories = emptyList(),
                message = "Disconnected from GitHub.",
            )
        }
    }

    private fun classify(message: String): ErrorKind = when {
        message.contains("credential", ignoreCase = true) -> ErrorKind.AUTH
        message.contains("rate limit", ignoreCase = true) -> ErrorKind.RATE_LIMIT
        message.contains("connection", ignoreCase = true) -> ErrorKind.NETWORK
        else -> ErrorKind.UNKNOWN
    }

    /** Surfaces a Git failure without letting it crash the screen. */
    fun reportGitFailure(throwable: Throwable) {
        val message = if (throwable is GitOperationException) throwable.message else throwable.message
        AppLog.w("Git operation failed in repositories screen", throwable)
        _state.update { it.copy(errorMessage = message ?: "The Git operation failed.") }
    }

    override fun onCleared() {
        remoteJob?.cancel()
        cloneJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }
}
