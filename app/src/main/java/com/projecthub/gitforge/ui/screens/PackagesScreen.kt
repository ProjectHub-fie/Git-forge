package com.projecthub.gitforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.terminal.runtime.RuntimeGroup
import com.projecthub.gitforge.terminal.runtime.RuntimePackageRegistry
import com.projecthub.gitforge.ui.PackagesViewModel
import com.projecthub.gitforge.ui.components.BannerTone
import com.projecthub.gitforge.ui.components.DestructiveConfirmDialog
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.RowDivider
import com.projecthub.gitforge.ui.components.SectionHeader
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.CodeTextStyle

/**
 * Packages: runtime installation and npm shortcuts.
 *
 * Versions are always probed from the installed binaries, so the screen cannot show
 * a version for a runtime that is not there. When a runtime is absent the screen
 * explains exactly how to install it and what the download costs.
 */
@Composable
fun PackagesScreen(
    container: AppContainer,
    viewModel: PackagesViewModel,
    activeRepositoryId: Long,
    onRunInTerminal: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The repository path is loaded asynchronously because it needs a database read.
    var repoPath by remember(activeRepositoryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeRepositoryId) {
        repoPath = if (activeRepositoryId > 0) {
            val entity = container.database.repositoryDao().byId(activeRepositoryId)
            entity?.let {
                runCatching { container.workspaceManager.resolveRelative(it.localPath).absolutePath }.getOrNull()
            }
        } else {
            null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        if (!state.abiSupported) {
            item {
                InfoBanner(
                    message = "This device reports ${state.abiSummary}. The userspace packages are built for " +
                        "${RuntimePackageRegistry.SUPPORTED_ABI}, so they cannot run here. The editor, Git engine " +
                        "and shell builtins still work.",
                    tone = BannerTone.WARNING,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        state.errorMessage?.let {
            item {
                InfoBanner(
                    message = it,
                    tone = BannerTone.ERROR,
                    actionLabel = "Dismiss",
                    onAction = viewModel::clearError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        state.message?.let {
            item {
                InfoBanner(
                    message = it,
                    tone = BannerTone.INFO,
                    actionLabel = "OK",
                    onAction = viewModel::clearMessage,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        item { SectionHeader("Installed runtimes") }
        item {
            RuntimeCard(
                title = "Git",
                icon = Icons.Default.Code,
                installed = true,
                version = state.gitVersion,
                detail = "JGit, pure Java. Used by both the Git screen and the terminal so they never disagree.",
            )
        }
        item {
            RuntimeCard(
                title = "Shell",
                icon = Icons.Default.Terminal,
                installed = true,
                version = state.shellDescription,
                detail = "Built-in shell with cd, ls, grep, sed, awk, find, sort, tar and more. No root required.",
            )
        }
        item {
            RuntimeCard(
                title = "Node.js",
                icon = Icons.Default.Extension,
                installed = state.nodeInstalled,
                version = state.nodeVersion,
                detail = if (state.nodeInstalled) {
                    "Runs JavaScript, npm scripts and project-local executables."
                } else {
                    "Not installed. Adds real Node.js ${RuntimePackageRegistry.nodePackages.first().version} " +
                        "and npm ${RuntimePackageRegistry.nodePackages[1].version}."
                },
            )
        }
        item {
            RuntimeCard(
                title = "npm",
                icon = Icons.Default.Extension,
                installed = state.npmInstalled,
                version = state.npmVersion,
                detail = if (state.npmInstalled) {
                    "Installs packages into the project's node_modules, so changes appear in Git status."
                } else {
                    "Installed together with Node.js."
                },
            )
        }
        item {
            RuntimeCard(
                title = "Python 3",
                icon = Icons.Default.Extension,
                installed = state.pythonInstalled,
                version = state.pythonVersion,
                detail = if (state.pythonInstalled) {
                    "CPython with pip. Pure-Python packages install; native extensions need a compiler and are not supported."
                } else {
                    "Not installed. Adds CPython ${RuntimePackageRegistry.pythonPackages.first().version} and pip."
                },
            )
        }

        item { SectionHeader("Install") }
        item {
            InstallCard(
                state = state,
                onInstallNode = {
                    viewModel.requestInstall(setOf(RuntimeGroup.NODE))
                },
                onInstallPython = {
                    viewModel.requestInstall(setOf(RuntimeGroup.PYTHON))
                },
                onInstallBoth = {
                    viewModel.requestInstall(setOf(RuntimeGroup.NODE, RuntimeGroup.PYTHON))
                },
                onUninstall = viewModel::requestUninstall,
            )
        }

        if (state.npmInstalled) {
            item { SectionHeader("npm in the selected project") }
            item {
                NpmShortcutsCard(
                    projectPath = repoPath,
                    onRun = onRunInTerminal,
                )
            }
        }

        item { SectionHeader("Licences") }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Packages are downloaded from the official Termux mirror at your request and are not " +
                            "bundled in this APK. Their SHA-256 checksums are pinned and verified before unpacking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    RuntimePackageRegistry.licenceSummary().forEach { (name, licence) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                licence,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RowDivider()
                    }
                }
            }
        }

        item { SectionHeader("Known limitations") }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    listOf(
                        "No root and no system-wide installation: everything lives in app-private storage and is removed when the app is uninstalled.",
                        "arm64-v8a only. Other ABIs are reported above and cannot run these packages.",
                        "npm packages with native addons need a compiler, which this userspace does not include. Pure-JavaScript packages work.",
                        "Python packages that build C extensions from source will fail for the same reason; pure-Python wheels install normally.",
                        "Android blocks execution of files in some shared storage locations. Repositories and runtimes live in app storage, where execution is permitted.",
                    ).forEach { line ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("•", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.pendingInstall != null) {
        InstallConfirmDialog(
            container = container,
            groups = state.pendingInstall!!,
            onConfirm = viewModel::confirmInstall,
            onDismiss = viewModel::cancelInstallRequest,
        )
    }

    if (state.pendingUninstall) {
        DestructiveConfirmDialog(
            title = "Remove the userspace?",
            explanation = "This deletes the installed Node.js, npm, Python and their libraries from app storage. " +
                "Your repositories are not affected. Any installed npm or pip packages must be reinstalled.",
            confirmLabel = "Remove userspace",
            onConfirm = viewModel::confirmUninstall,
            onDismiss = viewModel::cancelUninstall,
        )
    }
}

@Composable
private fun RuntimeCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    installed: Boolean,
    version: String?,
    detail: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (installed) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Installed",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        StatusChip("installed", MaterialTheme.colorScheme.secondary)
                    } else {
                        StatusChip("not installed", MaterialTheme.colorScheme.outline)
                    }
                }
                if (!version.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(version, style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstallCard(
    state: com.projecthub.gitforge.ui.PackagesUiState,
    onInstallNode: () -> Unit,
    onInstallPython: () -> Unit,
    onInstallBoth: () -> Unit,
    onUninstall: () -> Unit,
) {
    val nodeBytes = RuntimePackageRegistry.totalDownloadBytes(RuntimePackageRegistry.nodePackages)
    val pythonBytes = RuntimePackageRegistry.totalDownloadBytes(RuntimePackageRegistry.pythonPackages)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (state.installing) {
                Text(state.progress?.phase ?: "Installing…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        val p = state.progress
                        if (p != null) {
                            append("${p.completedPackages}/${p.totalPackages} packages · ")
                            append("${formatBytes(p.bytesDownloaded)} of ${formatBytes(p.bytesTotal)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Downloads are verified against pinned SHA-256 checksums before they are unpacked.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Node.js + npm (${formatBytes(nodeBytes)} download)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = onInstallNode, enabled = state.abiSupported) { Text("Install Node.js and npm") }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Python 3 + pip (${formatBytes(pythonBytes)} download)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = onInstallPython, enabled = state.abiSupported) { Text("Install Python 3") }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onInstallBoth, enabled = state.abiSupported) {
                    Text("Install both")
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Total storage used: ${formatBytes(state.usageBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.nodeInstalled || state.pythonInstalled) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onUninstall) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove the userspace", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun NpmShortcutsCard(
    projectPath: String?,
    onRun: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "These run npm in the selected repository's directory, so package.json, package-lock.json and " +
                    "node_modules are created there and show up in Git status.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val shortcuts = listOf(
                "npm install" to "Install dependencies from package.json",
                "npm install --ignore-scripts" to "Install without running lifecycle scripts",
                "npm install <package>" to "Add a dependency",
                "npm install -D <package>" to "Add a development dependency",
                "npm uninstall <package>" to "Remove a dependency",
                "npm run build" to "Run the build script",
                "npm run test" to "Run the test script",
                "npm ls --depth=0" to "List installed top-level packages",
            )

            shortcuts.forEach { (command, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(command, style = CodeTextStyle)
                        Text(
                            description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRun(command) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run: $command")
                    }
                }
                RowDivider()
            }

            Spacer(Modifier.height(8.dp))
            InfoBanner(
                message = "npm install can execute lifecycle scripts from the repository. Use the " +
                    "--ignore-scripts variant if you have not reviewed that code.",
                tone = BannerTone.WARNING,
            )

            if (projectPath != null) {
                Spacer(Modifier.height(8.dp))
                MonoPath(projectPath)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Select a repository on the Repositories screen to target it directly.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    container: AppContainer,
    groups: Set<RuntimeGroup>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Quote the manager's own de-duplicated list: it already prepends the shared
    // commonPackages once, so counting them again here would inflate the dialog.
    val packages = container.runtimeManager.packagesFor(groups)
    val total = packages.sumOf { it.sizeBytes }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Memory, contentDescription = null) },
        title = { Text("Install userspace packages") },
        text = {
            Column {
                Text(
                    "GitForge will download ${packages.size} packages (${formatBytes(total)}) from the official " +
                        "Termux mirror and unpack them into app-private storage. No root access is used.",
                )
                Spacer(Modifier.height(12.dp))
                Text("Packages:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Column(Modifier.height(180.dp)) {
                    LazyColumn {
                        items(packages) { pkg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(pkg.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(
                                    "${pkg.version} · ${formatBytes(pkg.sizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Each download is verified against a pinned SHA-256 checksum before unpacking.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
