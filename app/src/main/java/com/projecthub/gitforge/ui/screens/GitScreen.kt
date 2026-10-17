package com.projecthub.gitforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.gitforge.git.CommitInfo
import com.projecthub.gitforge.git.DiffLine
import com.projecthub.gitforge.git.FileStatus
import com.projecthub.gitforge.ui.GitSection
import com.projecthub.gitforge.ui.GitViewModel
import com.projecthub.gitforge.ui.components.BannerTone
import com.projecthub.gitforge.ui.components.DestructiveConfirmDialog
import com.projecthub.gitforge.ui.components.EmptyState
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.InlineProgress
import com.projecthub.gitforge.ui.components.RowDivider
import com.projecthub.gitforge.ui.components.SectionHeader
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.CodeTextStyle

/**
 * Git: status, staging, commits, diffs, branches, history and remotes.
 *
 * Diff lines carry a `+`/`-` prefix in the text itself, so the meaning survives for
 * anyone who cannot distinguish the colours.
 */
@Composable
fun GitScreen(
    viewModel: GitViewModel,
    activeRepositoryId: Long,
    onGoToRepositories: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var showBranchDialog by remember { mutableStateOf(false) }
    var branchToSwitch by remember { mutableStateOf<String?>(null) }
    var branchToMerge by remember { mutableStateOf<String?>(null) }
    var branchToRename by remember { mutableStateOf<String?>(null) }
    var commitDetail by remember { mutableStateOf<CommitInfo?>(null) }

    LaunchedEffect(activeRepositoryId, state.repositoryId) {
        if (activeRepositoryId > 0 && state.repositoryId != activeRepositoryId) {
            viewModel.openRepository(activeRepositoryId)
        }
    }

    if (activeRepositoryId <= 0 || state.repositoryRoot == null) {
        NoRepositorySelected(onGoToRepositories)
        return
    }

    Column(Modifier.fillMaxSize()) {
        RepoStatusHeader(
            branch = state.status?.branch ?: "…",
            ahead = state.status?.aheadCount ?: 0,
            behind = state.status?.behindCount ?: 0,
            stagedCount = state.stagedFiles.size,
            unstagedCount = state.unstagedFiles.size,
            untrackedCount = state.untrackedFiles.size,
            conflictingCount = state.conflictingFiles.size,
            onRefresh = viewModel::refresh,
            onFetch = viewModel::fetch,
            onPull = viewModel::pull,
            onPush = viewModel::push,
        )

        state.busyMessage?.let {
            InlineProgress(message = it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        state.errorMessage?.let {
            InfoBanner(
                message = it,
                tone = BannerTone.ERROR,
                actionLabel = "Dismiss",
                onAction = viewModel::clearError,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        state.message?.let {
            InfoBanner(
                message = it,
                tone = BannerTone.INFO,
                actionLabel = "OK",
                onAction = viewModel::clearMessage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        TabRow(selectedTabIndex = state.section.ordinal) {
            GitSection.entries.forEach { section ->
                Tab(
                    selected = state.section == section,
                    onClick = { viewModel.selectSection(section) },
                    text = { Text(section.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        when (state.section) {
            GitSection.CHANGES -> ChangesSection(
                state = state,
                onStage = viewModel::stageFile,
                onUnstage = viewModel::unstageFile,
                onStageAll = viewModel::stageAll,
                onUnstageAll = viewModel::unstageAll,
                onShowDiff = viewModel::showDiff,
                onCommitMessage = viewModel::setCommitMessage,
                onCommitDescription = viewModel::setCommitDescription,
                onCommit = viewModel::commit,
            )

            GitSection.HISTORY -> HistorySection(
                commits = state.commits,
                loading = state.loading,
                onOpen = { commitDetail = it },
                onRevert = viewModel::requestRevert,
                onLoadMore = viewModel::loadMoreHistory,
            )

            GitSection.BRANCHES -> BranchesSection(
                branches = state.branches,
                currentBranch = state.status?.branch.orEmpty(),
                onCreate = { showBranchDialog = true },
                onSwitch = { branchToSwitch = it },
                onMerge = { branchToMerge = it },
                onRename = { branchToRename = it },
                onDelete = viewModel::requestDeleteBranch,
            )

            GitSection.REMOTES -> RemotesSection(
                remotes = state.remotes,
                lastPush = state.lastPushOutcome,
                onRefresh = viewModel::loadRemotes,
            )
        }
    }

    state.selectedDiff?.let { diff ->
        DiffDialog(
            path = diff.relativePath,
            additions = diff.additions,
            deletions = diff.deletions,
            lines = diff.lines,
            onDismiss = viewModel::dismissDiff,
        )
    }

    if (showBranchDialog) {
        CreateBranchDialog(
            onDismiss = { showBranchDialog = false },
            onCreate = { name, checkout ->
                viewModel.createBranch(name, checkout)
                showBranchDialog = false
            },
        )
    }

    branchToSwitch?.let { name ->
        AlertDialog(
            onDismissRequest = { branchToSwitch = null },
            title = { Text("Switch to '$name'") },
            text = { Text("The working tree will be updated to match '$name'. Uncommitted changes block this operation.") },
            confirmButton = {
                TextButton(onClick = { viewModel.switchBranch(name); branchToSwitch = null }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { branchToSwitch = null }) { Text("Cancel") } },
        )
    }

    branchToMerge?.let { name ->
        AlertDialog(
            onDismissRequest = { branchToMerge = null },
            title = { Text("Merge '$name' into the current branch") },
            text = {
                Text(
                    "GitForge will merge '$name'. If the branches conflict, the merge stops and the conflicting " +
                        "files are listed so you can resolve them. Nothing is discarded.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.mergeBranch(name); branchToMerge = null }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { branchToMerge = null }) { Text("Cancel") } },
        )
    }

    branchToRename?.let { oldName ->
        var newName by remember { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { branchToRename = null },
            title = { Text("Rename branch") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New branch name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.renameBranch(oldName, newName.trim()); branchToRename = null },
                    enabled = newName.isNotBlank() && newName != oldName,
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { branchToRename = null }) { Text("Cancel") } },
        )
    }

    commitDetail?.let { commit ->
        AlertDialog(
            onDismissRequest = { commitDetail = null },
            title = { Text(commit.shortSha) },
            text = {
                Column {
                    InlineLabel("Author", "${commit.authorName} <${commit.authorEmail}>")
                    Spacer(Modifier.height(6.dp))
                    InlineLabel("Date", java.util.Date(commit.timestampSeconds * 1000).toString())
                    Spacer(Modifier.height(6.dp))
                    InlineLabel("Parents", commit.parentShas.joinToString(" ") { it.take(7) }.ifEmpty { "none (root)" })
                    Spacer(Modifier.height(12.dp))
                    Text(commit.message, style = CodeTextStyle)
                }
            },
            confirmButton = { TextButton(onClick = { commitDetail = null }) { Text("Close") } },
        )
    }

    state.pendingConfirmation?.let { pending ->
        DestructiveConfirmDialog(
            title = pending.title,
            explanation = pending.explanation,
            confirmLabel = pending.confirmLabel,
            detail = pending.detail,
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissPending,
        )
    }

    state.lastMergeOutcome?.conflictingPaths?.takeIf { it.isNotEmpty() }?.let { paths ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Merge conflicts") },
            text = {
                Column {
                    Text("These files contain conflicts. Resolve them and commit, or abort the merge to restore HEAD:")
                    Spacer(Modifier.height(8.dp))
                    paths.forEach { Text(it, style = CodeTextStyle) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.refresh() }) { Text("Refresh status") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.abortMerge() }) { Text("Abort merge") }
            },
        )
    }
}

@Composable
private fun RepoStatusHeader(
    branch: String,
    ahead: Int,
    behind: Int,
    stagedCount: Int,
    unstagedCount: Int,
    untrackedCount: Int,
    conflictingCount: Int,
    onRefresh: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Commit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(branch, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Git status")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("$stagedCount staged", MaterialTheme.colorScheme.secondary)
                StatusChip("$unstagedCount modified", MaterialTheme.colorScheme.primary)
                StatusChip("$untrackedCount untracked", MaterialTheme.colorScheme.outline)
                if (conflictingCount > 0) {
                    StatusChip("$conflictingCount conflicts", MaterialTheme.colorScheme.error)
                }
            }
            if (ahead > 0 || behind > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ahead > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(" $ahead ahead", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (behind > 0) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(" $behind behind", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFetch) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fetch")
                }
                OutlinedButton(onClick = onPull) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pull")
                }
                Button(onClick = onPush) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Push")
                }
            }
        }
    }
}

@Composable
private fun ChangesSection(
    state: com.projecthub.gitforge.ui.GitUiState,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onShowDiff: (String, Boolean) -> Unit,
    onCommitMessage: (String) -> Unit,
    onCommitDescription: (String) -> Unit,
    onCommit: () -> Unit,
) {
    if (state.status?.files.isNullOrEmpty()) {
        EmptyState(
            title = "Working tree clean",
            description = "There are no modified, untracked or staged files in this repository.",
            icon = Icons.Default.Check,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (state.stagedFiles.isNotEmpty()) {
            item {
                SectionHeader("Staged (${state.stagedFiles.size})") {
                    TextButton(onClick = onUnstageAll) { Text("Unstage all") }
                }
            }
            items(state.stagedFiles, key = { "staged-${it.relativePath}" }) { file ->
                ChangeRow(file, staged = true, onToggle = { onUnstage(file.relativePath) }, onDiff = { onShowDiff(file.relativePath, true) })
                RowDivider()
            }
        }

        if (state.conflictingFiles.isNotEmpty()) {
            item { SectionHeader("Conflicts (${state.conflictingFiles.size})") }
            items(state.conflictingFiles, key = { "conflict-${it.relativePath}" }) { file ->
                ChangeRow(file, staged = false, onToggle = { onStage(file.relativePath) }, onDiff = { onShowDiff(file.relativePath, false) })
                RowDivider()
            }
        }

        if (state.unstagedFiles.isNotEmpty()) {
            item {
                SectionHeader("Modified (${state.unstagedFiles.size})") {
                    TextButton(onClick = onStageAll) { Text("Stage all") }
                }
            }
            items(state.unstagedFiles, key = { "unstaged-${it.relativePath}" }) { file ->
                ChangeRow(file, staged = false, onToggle = { onStage(file.relativePath) }, onDiff = { onShowDiff(file.relativePath, false) })
                RowDivider()
            }
        }

        if (state.untrackedFiles.isNotEmpty()) {
            item { SectionHeader("Untracked (${state.untrackedFiles.size})") }
            items(state.untrackedFiles, key = { "untracked-${it.relativePath}" }) { file ->
                ChangeRow(file, staged = false, onToggle = { onStage(file.relativePath) }, onDiff = { onShowDiff(file.relativePath, false) })
                RowDivider()
            }
        }

        item {
            CommitPanel(
                stagedCount = state.stagedFiles.size,
                message = state.commitMessage,
                description = state.commitDescription,
                canCommit = state.canCommit,
                onMessageChange = onCommitMessage,
                onDescriptionChange = onCommitDescription,
                onCommit = onCommit,
            )
        }
    }
}

@Composable
private fun ChangeRow(
    file: FileStatus,
    staged: Boolean,
    onToggle: () -> Unit,
    onDiff: () -> Unit,
) {
    val color = statusColor(file)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label carries the meaning, so colour is never the only signal.
        StatusChip(label = file.statusLabel.trim().ifEmpty { "?" }, color = color)
        Spacer(Modifier.width(10.dp))
        Text(
            text = file.relativePath,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDiff) {
            Icon(Icons.Default.Difference, contentDescription = "Show diff for ${file.relativePath}", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (staged) Icons.Default.Undo else Icons.Default.Check,
                contentDescription = if (staged) "Unstage ${file.relativePath}" else "Stage ${file.relativePath}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun statusColor(file: FileStatus): Color = when (file.changeType) {
    com.projecthub.gitforge.git.ChangeType.ADDED,
    com.projecthub.gitforge.git.ChangeType.UNTRACKED,
    -> MaterialTheme.colorScheme.secondary
    com.projecthub.gitforge.git.ChangeType.DELETED -> MaterialTheme.colorScheme.error
    com.projecthub.gitforge.git.ChangeType.CONFLICTING -> MaterialTheme.colorScheme.error
    com.projecthub.gitforge.git.ChangeType.MODIFIED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun CommitPanel(
    stagedCount: Int,
    message: String,
    description: String,
    canCommit: Boolean,
    onMessageChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Commit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (stagedCount == 0) {
                    "Stage at least one file to enable committing."
                } else {
                    "$stagedCount file(s) staged"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                isError = message.isEmpty() && stagedCount > 0,
                supportingText = {
                    if (message.isEmpty() && stagedCount > 0) Text("A commit message is required.")
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = onDescriptionChange,
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCommit, enabled = canCommit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Commit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Commit staged changes")
            }
        }
    }
}

@Composable
private fun DiffDialog(
    path: String,
    additions: Int,
    deletions: Int,
    lines: List<DiffLine>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("+$additions", MaterialTheme.colorScheme.secondary)
                    StatusChip("-$deletions", MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line.text,
                            style = CodeTextStyle,
                            color = when (line.kind) {
                                DiffLine.Kind.ADDED -> Color(0xFF16A34A)
                                DiffLine.Kind.REMOVED -> Color(0xFFDC2626)
                                DiffLine.Kind.HEADER -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun HistorySection(
    commits: List<CommitInfo>,
    loading: Boolean,
    onOpen: (CommitInfo) -> Unit,
    onRevert: (CommitInfo) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (commits.isEmpty()) {
        if (loading) {
            com.projecthub.gitforge.ui.components.LoadingState("Loading history…")
        } else {
            EmptyState(
                title = "No commits yet",
                description = "This repository has no commit history. Stage and commit your first change to see it here.",
                icon = Icons.Default.History,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(commits, key = { it.sha }) { commit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        commit.message.lineSequence().first(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${commit.shortSha} · ${commit.authorName} · " +
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                .format(java.util.Date(commit.timestampSeconds * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onOpen(commit) }) { Text("Details") }
                IconButton(onClick = { onRevert(commit) }) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = "Revert commit ${commit.shortSha}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            RowDivider()
        }
        item {
            TextButton(
                onClick = onLoadMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) { Text("Load older commits") }
        }
    }
}

@Composable
private fun BranchesSection(
    branches: List<com.projecthub.gitforge.git.BranchInfo>,
    currentBranch: String,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
    onMerge: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onCreate) { Text("New branch") }
            OutlinedButton(onClick = { onSwitch(currentBranch) }) { Text("Switch…") }
        }

        if (branches.isEmpty()) {
            EmptyState(
                title = "No branches",
                description = "Create a branch to start working on a change.",
                icon = Icons.Default.Merge,
                actionLabel = "New branch",
                onAction = onCreate,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(branches, key = { "${it.isRemote}-${it.name}" }) { branch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                branch.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (branch.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (branch.isCurrent) {
                                    StatusChip("current", MaterialTheme.colorScheme.secondary)
                                }
                                if (branch.isRemote) {
                                    StatusChip("remote", MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        if (!branch.isRemote && !branch.isCurrent) {
                            TextButton(onClick = { onSwitch(branch.name) }) { Text("Switch") }
                            IconButton(onClick = { onMerge(branch.name) }) {
                                Icon(Icons.Default.Merge, contentDescription = "Merge ${branch.name}", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onRename(branch.name) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Rename ${branch.name}", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDelete(branch.name) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete ${branch.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    RowDivider()
                }
            }
        }
    }
}

@Composable
private fun RemotesSection(
    remotes: List<com.projecthub.gitforge.git.RemoteInfo>,
    lastPush: com.projecthub.gitforge.git.PushOutcome?,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remotes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh remotes") }
        }
        Spacer(Modifier.height(8.dp))

        if (remotes.isEmpty()) {
            Text(
                "No remotes configured. A remote is added automatically when you clone a repository.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            remotes.forEach { remote ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(remote.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("fetch: ${remote.fetchUrl}", style = MaterialTheme.typography.bodySmall)
                        if (remote.pushUrl != remote.fetchUrl) {
                            Text("push:  ${remote.pushUrl}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Credentials are never stored in the remote URL; GitForge supplies the token for each network call.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        lastPush?.let { outcome ->
            Spacer(Modifier.height(16.dp))
            Text("Last push", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Remote: ${outcome.remoteName}", style = MaterialTheme.typography.bodySmall)
            Text("Refs updated: ${outcome.updatedRefs.size}", style = MaterialTheme.typography.bodySmall)
            if (outcome.rejectedRefs.isNotEmpty()) {
                Text(
                    "Rejected: ${outcome.rejectedRefs.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            outcome.messages.forEach { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CreateBranchDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var checkout by remember { mutableStateOf(true) }
    val valid = name.isNotBlank() && org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$name")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New branch") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    isError = name.isNotBlank() && !valid,
                    supportingText = {
                        if (name.isNotBlank() && !valid) Text("That is not a valid Git branch name.")
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = checkout, onCheckedChange = { checkout = it })
                    Spacer(Modifier.width(12.dp))
                    Text("Switch to it after creating", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), checkout) }, enabled = valid) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
