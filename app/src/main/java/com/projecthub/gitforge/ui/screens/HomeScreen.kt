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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.ui.RepositoriesViewModel
import com.projecthub.gitforge.ui.components.EmptyState
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.RowDivider
import com.projecthub.gitforge.ui.components.SectionHeader
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.CodeTextStyle

/** Formats a byte count for display without pulling in a formatting library. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * Home: a compact dashboard.
 *
 * It shows only data the app actually has. There are no fabricated statistics: the
 * activity list is derived from the repositories' real Git status, and an empty
 * workspace gets a genuine onboarding state instead of placeholder projects.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    repositoriesViewModel: RepositoriesViewModel,
    onNavigate: (String) -> Unit,
    onCreateLocalProject: () -> Unit,
    onCloneRepository: () -> Unit,
    onOpenRepository: (Long) -> Unit,
) {
    val state by repositoriesViewModel.state.collectAsStateWithLifecycle()

    val recent = remember(state.localRepositories) {
        state.localRepositories.sortedByDescending { it.entity.lastOpenedAt }.take(5)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { BrandHeader(gitHubConnected = state.gitHubConnected, login = state.gitHubLogin) }

        if (!state.gitHubConnected) {
            item {
                InfoBanner(
                    message = "GitHub is not connected. Local projects and the terminal work offline; " +
                        "cloning, pulling and pushing need an account.",
                    tone = com.projecthub.gitforge.ui.components.BannerTone.INFO,
                    actionLabel = "Connect",
                    onAction = { onNavigate(com.projecthub.gitforge.ui.nav.Destination.SETTINGS.route) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        item {
            QuickActions(
                onCreateLocalProject = onCreateLocalProject,
                onCloneRepository = onCloneRepository,
                onOpenEditor = { onNavigate(com.projecthub.gitforge.ui.nav.Destination.EDITOR.route) },
                onOpenTerminal = { onNavigate(com.projecthub.gitforge.ui.nav.Destination.TERMINAL.route) },
            )
        }

        if (recent.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Welcome to GitForge", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Code anywhere. Build everything.\n\n" +
                                "Clone a GitHub repository or create a local project to get started. " +
                                "Then edit files, inspect diffs, commit, and run Git, Python or Node in the terminal.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onCloneRepository) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Clone repository")
                            }
                            OutlinedButton(onClick = onCreateLocalProject) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("New project")
                            }
                        }
                    }
                }
            }
        } else {
            item { SectionHeader("Recent projects") }
            items(recent, key = { it.entity.id }) { item ->
                RecentProjectRow(
                    name = item.entity.displayName,
                    ownerName = item.entity.ownerName,
                    branch = item.status?.branch ?: item.entity.currentBranch,
                    changedCount = item.status?.files?.size ?: 0,
                    existsOnDisk = item.existsOnDisk,
                    isLocalOnly = item.entity.isLocalOnly,
                    onClick = { onOpenRepository(item.entity.id) },
                )
                RowDivider()
            }
        }

        state.storageUsage?.let { usage ->
            item { SectionHeader("Storage") }
            item {
                StorageSummary(
                    workspaceBytes = usage.totalBytes,
                    freeBytes = usage.freeBytes,
                    repositoryCount = state.localRepositories.size,
                )
            }
        }
    }
}

@Composable
private fun BrandHeader(gitHubConnected: Boolean, login: String?) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("GitForge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Code anywhere. Build everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gitHubConnected) {
                    StatusChip(label = login ?: "Connected", color = MaterialTheme.colorScheme.secondary)
                } else {
                    StatusChip(label = "Offline", color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    onCreateLocalProject: () -> Unit,
    onCloneRepository: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                icon = Icons.Default.Download,
                label = "Clone",
                onClick = onCloneRepository,
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Default.Add,
                label = "New project",
                onClick = onCreateLocalProject,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                icon = Icons.Default.FolderOpen,
                label = "Editor",
                onClick = onOpenEditor,
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Default.Terminal,
                label = "Terminal",
                onClick = onOpenTerminal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecentProjectRow(
    name: String,
    ownerName: String,
    branch: String,
    changedCount: Int,
    existsOnDisk: Boolean,
    isLocalOnly: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isLocalOnly) Icons.Default.Folder else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = buildString {
                        if (ownerName != name) append(ownerName).append(" · ")
                        append(branch.ifBlank { "no branch" })
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!existsOnDisk) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = "Missing on disk",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            } else if (changedCount > 0) {
                StatusChip(label = "$changedCount changed", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun StorageSummary(workspaceBytes: Long, freeBytes: Long, repositoryCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Workspace", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "$repositoryCount repositories · ${formatBytes(workspaceBytes)} used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Free space available to the app: ${formatBytes(freeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small helper used by several screens to show a monospaced path. */
@Composable
internal fun MonoPath(path: String, modifier: Modifier = Modifier) {
    Text(
        text = path,
        style = CodeTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Shared empty state used when no repository is selected. */
@Composable
internal fun NoRepositorySelected(onGoToRepositories: () -> Unit) {
    EmptyState(
        title = "No repository selected",
        description = "Open or clone a repository first, then come back to this screen.",
        icon = Icons.Default.Folder,
        actionLabel = "Go to Repositories",
        onAction = onGoToRepositories,
    )
}

/** Renders a small key/value pair on a single line, used in dialogs. */
@Composable
internal fun InlineLabel(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** Simple text field dialog result holder used by several screens. */
internal class TextInputState(initial: String = "") {
    var value by mutableStateOf(initial)
}


