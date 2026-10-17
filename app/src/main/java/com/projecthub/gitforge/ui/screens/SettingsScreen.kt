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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.projecthub.gitforge.data.prefs.AppSettings
import com.projecthub.gitforge.data.prefs.TerminalFontSize
import com.projecthub.gitforge.data.prefs.ThemeMode
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.github.GitHubDeviceFlowAuth
import com.projecthub.gitforge.ui.RepositoriesViewModel
import com.projecthub.gitforge.ui.components.BannerTone
import com.projecthub.gitforge.ui.components.DestructiveConfirmDialog
import com.projecthub.gitforge.ui.components.InfoBanner
import com.projecthub.gitforge.ui.components.RowDivider
import com.projecthub.gitforge.ui.components.SectionHeader
import com.projecthub.gitforge.ui.components.StatusChip
import com.projecthub.gitforge.ui.theme.CodeTextStyle

/**
 * Settings: appearance, editor, terminal, Git identity, GitHub account, storage and
 * about.
 *
 * Every setting here is persisted through DataStore and takes effect immediately.
 * Security-sensitive items explain what the app does and does not store.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    repositoriesViewModel: RepositoriesViewModel,
    onOpenUrl: (String) -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    installedVersion: String = com.projecthub.gitforge.BuildConfig.VERSION_NAME,
) {
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = container.settingsRepository.snapshot(),
    )
    val reposState by repositoriesViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // The effective client ID: the value compiled into this build.
    val effectiveClientId = container.oauthClientId

    var showTokenDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(false) }
    var deviceFlowState by remember { mutableStateOf<DeviceFlowUiState?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ---------------------------------------------------------- appearance
        item { SectionHeader("Appearance") }
        item {
            SettingsCard {
                Column {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { scope.launch { container.settingsRepository.setThemeMode(mode) } },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwitchRow(
                        title = "Dynamic colour",
                        subtitle = "Use the system wallpaper palette on Android 12 and newer.",
                        checked = settings.useDynamicColor,
                        onCheckedChange = { scope.launch { container.settingsRepository.setDynamicColor(it) } },
                    )
                }
            }
        }

        // -------------------------------------------------------------- editor
        item { SectionHeader("Editor") }
        item {
            SettingsCard {
                Column {
                    var fontSizeDraft by remember(settings.editorFontSizeSp) { mutableStateOf(settings.editorFontSizeSp.toFloat()) }
                    SliderRow(
                        title = "Font size: ${fontSizeDraft.toInt()}sp",
                        value = fontSizeDraft,
                        range = AppSettings.MIN_EDITOR_FONT_SP.toFloat()..AppSettings.MAX_EDITOR_FONT_SP.toFloat(),
                        steps = AppSettings.MAX_EDITOR_FONT_SP - AppSettings.MIN_EDITOR_FONT_SP - 1,
                        onValueChange = { fontSizeDraft = it },
                        onValueChangeFinished = { scope.launch { container.settingsRepository.setEditorFontSize(fontSizeDraft.toInt()) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "Show line numbers",
                        subtitle = null,
                        checked = settings.editorShowLineNumbers,
                        onCheckedChange = { scope.launch { container.settingsRepository.setEditorLineNumbers(it) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "Soft wrap long lines",
                        subtitle = "Wrap instead of scrolling horizontally.",
                        checked = settings.editorSoftWrap,
                        onCheckedChange = { scope.launch { container.settingsRepository.setEditorSoftWrap(it) } },
                    )
                    RowDivider()
                    var tabSizeDraft by remember(settings.editorTabSize) { mutableStateOf(settings.editorTabSize.toFloat()) }
                    SliderRow(
                        title = "Tab size: ${tabSizeDraft.toInt()}",
                        value = tabSizeDraft,
                        range = 1f..8f,
                        steps = 6,
                        onValueChange = { tabSizeDraft = it },
                        onValueChangeFinished = { scope.launch { container.settingsRepository.setEditorTabSize(tabSizeDraft.toInt()) } },
                    )
                }
            }
        }

        // ------------------------------------------------------------ terminal
        item { SectionHeader("Terminal") }
        item {
            SettingsCard {
                Column {
                    Text("Font size", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalFontSize.entries.forEach { size ->
                            FilterChip(
                                selected = settings.terminalFontSize == size,
                                onClick = { scope.launch { container.settingsRepository.setTerminalFontSize(size) } },
                                label = { Text(size.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    RowDivider()
                    var scrollbackDraft by remember(settings.terminalScrollbackLines) { mutableStateOf(settings.terminalScrollbackLines.toFloat()) }
                    SliderRow(
                        title = "Scrollback: ${scrollbackDraft.toInt()} lines",
                        value = scrollbackDraft,
                        range = AppSettings.MIN_SCROLLBACK.toFloat()..AppSettings.MAX_SCROLLBACK.toFloat(),
                        steps = 0,
                        onValueChange = { scrollbackDraft = it },
                        onValueChangeFinished = { scope.launch { container.settingsRepository.setTerminalScrollback(scrollbackDraft.toInt()) } },
                    )
                    Text(
                        "Older output is dropped once the limit is reached, which keeps memory use bounded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ----------------------------------------------------------------- git
        item { SectionHeader("Git") }
        item {
            SettingsCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Commit identity", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${container.gitIdentity.first} <${container.gitIdentity.second}>",
                                style = CodeTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showIdentityDialog = true }) { Text("Change") }
                    }
                    RowDivider()
                    SwitchRow(
                        title = "Confirm destructive Git operations",
                        subtitle = "Ask before deleting branches or reverting commits.",
                        checked = settings.confirmDestructiveGitOperations,
                        onCheckedChange = { scope.launch { container.settingsRepository.setConfirmDestructive(it) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "Warn before running repository scripts",
                        subtitle = "Ask before npm install can execute lifecycle scripts.",
                        checked = settings.warnBeforeRunningRepositoryScripts,
                        onCheckedChange = { scope.launch { container.settingsRepository.setWarnRepoScripts(it) } },
                    )
                    RowDivider()
                    SwitchRow(
                        title = "Fetch on open",
                        subtitle = "Fetch from origin automatically when a repository is opened.",
                        checked = settings.autoFetchOnOpen,
                        onCheckedChange = { scope.launch { container.settingsRepository.setAutoFetch(it) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        message = "GitForge never force-pushes and never discards uncommitted changes without asking.",
                        tone = BannerTone.INFO,
                    )
                }
            }
        }

        // -------------------------------------------------------- github/security
        item { SectionHeader("GitHub and security") }
        item {
            SettingsCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (reposState.gitHubConnected) {
                                    reposState.gitHubLogin ?: "Connected"
                                } else {
                                    "Not connected"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (reposState.gitHubConnected) {
                                    "Token stored in the Android Keystore-encrypted credential store."
                                } else {
                                    "Connect to browse, clone, pull and push your repositories."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (reposState.gitHubConnected) {
                            StatusChip("connected", MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (!reposState.gitHubConnected) {
                        Button(
                            // The device flow cannot start without a client ID, so this is the
                            // one control that stays disabled until one is compiled in.
                            enabled = effectiveClientId != null,
                            onClick = { deviceFlowState = DeviceFlowUiState.Preparing },
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect with GitHub")
                        }
                        if (effectiveClientId == null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Disabled: this build has no GitHub client ID compiled in, so sign-in " +
                                    "cannot start. Use a personal access token below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Opens the GitHub verification page in your browser. Enter the code " +
                                    "shown and approve access; no extra Android permission is needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showTokenDialog = true }) {
                            Text("Use a personal access token instead")
                        }
                    } else {
                        OutlinedButton(onClick = { showDisconnectConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Disconnect and remove token")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Permissions requested", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    GitHubDeviceFlowAuth.SCOPE_EXPLANATIONS.forEach { (scopeName, explanation) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(scopeName, style = CodeTextStyle, modifier = Modifier.width(90.dp))
                            Text(
                                explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("How credentials are handled", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "The token is encrypted with a key held in the Android Keystore.",
                        "It is never written into a repository, a remote URL, a log or the terminal history.",
                        "It is never sent anywhere except github.com over HTTPS.",
                        "Signing out deletes it immediately.",
                    ).forEach { line ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The GitHub client ID is compiled into this build. It is public " +
                            "information, and the device flow needs no client secret, so there " +
                            "is nothing to configure here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // -------------------------------------------------------------- storage
        item { SectionHeader("Storage") }
        item {
            SettingsCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Workspace", style = MaterialTheme.typography.titleSmall)
                            MonoPath(container.workspaceManager.root.absolutePath)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val usage = reposState.storageUsage
                    Text(
                        text = usage?.let {
                            "${reposState.localRepositories.size} repositories · ${formatBytes(it.totalBytes)} used"
                        } ?: "Calculating…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Free space available to the app: " +
                            formatBytes(container.workspaceManager.root.usableSpace),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showCleanupConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clean temporary files")
                        }
                        OutlinedButton(onClick = { repositoriesViewModel.refreshLocal() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Recompute")
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Import and export use the Android Storage Access Framework, so GitForge needs no broad " +
                            "storage permission. Repositories stay available offline.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- about
        item { SectionHeader("About") }
        item {
            SettingsCard {
                Column {
                    Text("GitForge", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Code anywhere. Build everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    InlineLabel("Version", installedVersion)
                    InlineLabel("Build", "${com.projecthub.gitforge.BuildConfig.VERSION_CODE} (${com.projecthub.gitforge.BuildConfig.BUILD_TYPE})")
                    InlineLabel("Application ID", com.projecthub.gitforge.BuildConfig.APPLICATION_ID)
                    InlineLabel("Min SDK", "26 (Android 8.0)")
                    InlineLabel("Device ABI", container.runtimeManager.abiSummary())

                    Spacer(Modifier.height(12.dp))
                    Text("Third-party components", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    listOf(
                        "JGit" to "Eclipse Distribution License 1.0",
                        "OkHttp" to "Apache-2.0",
                        "kotlinx.serialization" to "Apache-2.0",
                        "Apache Commons Compress" to "Apache-2.0",
                        "XZ for Java" to "Public Domain",
                        "AndroidX, Jetpack Compose" to "Apache-2.0",
                        "Termux runtime packages" to "Downloaded at your request, not bundled",
                    ).forEach { (name, licence) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                licence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RowDivider()
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOpenUpdate) {
                        Text("Check for updates — install a newer build")
                    }
                    TextButton(onClick = onOpenDiagnostics) {
                        Text("Open Diagnostics — app log and crash reports")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- device flow

    deviceFlowState?.let { flowState ->
        DeviceFlowDialog(
            container = container,
            state = flowState,
            onStateChange = { deviceFlowState = it },
            onCopyCode = { code -> clipboard.setText(AnnotatedString(code)) },
            onOpenBrowser = onOpenUrl,
            onSuccess = {
                repositoriesViewModel.refreshGitHubState()
                repositoriesViewModel.loadRemoteRepositories(reset = true)
                deviceFlowState = null
            },
            onDismiss = { deviceFlowState = null },
        )
    }

    if (showTokenDialog) {
        TokenInputDialog(
            onDismiss = { showTokenDialog = false },
            onSave = { token ->
                scope.launch {
                    // Verify before persisting: a token GitHub rejects is worse than
                    // no token, and saving first would wipe a good token on flaky net.
                    val probe = com.projecthub.gitforge.github.GitHubApi(tokenProvider = { token })
                    val login = runCatching { probe.currentUser().login }.getOrNull()
                    if (login != null) {
                        container.credentialStore.save(token, login, null, "bearer")
                        repositoriesViewModel.refreshGitHubState()
                        repositoriesViewModel.loadRemoteRepositories(reset = true)
                    } else {
                        repositoriesViewModel.refreshGitHubState()
                    }
                    showTokenDialog = false
                }
            },
        )
    }

    if (showDisconnectConfirm) {
        DestructiveConfirmDialog(
            title = "Disconnect GitHub?",
            explanation = "The stored token is deleted from the encrypted credential store. Local repositories stay " +
                "on the device, but cloning, pulling and pushing will stop working until you connect again.",
            confirmLabel = "Disconnect",
            onConfirm = {
                repositoriesViewModel.disconnectGitHub()
                showDisconnectConfirm = false
            },
            onDismiss = { showDisconnectConfirm = false },
        )
    }

    if (showCleanupConfirm) {
        DestructiveConfirmDialog(
            title = "Clean temporary files?",
            explanation = "Deletes GitForge's scratch directory. Repositories and their Git history are not touched.",
            confirmLabel = "Clean",
            onConfirm = {
                container.workspaceManager.clearTemp()
                showCleanupConfirm = false
            },
            onDismiss = { showCleanupConfirm = false },
        )
    }

    if (showIdentityDialog) {
        IdentityDialog(
            initialName = settings.gitAuthorName,
            initialEmail = settings.gitAuthorEmail,
            onDismiss = { showIdentityDialog = false },
            onSave = { name, email ->
                scope.launch { container.settingsRepository.setGitAuthor(name, email) }
                showIdentityDialog = false
            },
        )
    }
}

// ------------------------------------------------------------------- sub-pieces

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
        )
    }
}

/** The state machine for the device-flow sign-in dialog. */
sealed interface DeviceFlowUiState {
    data object Preparing : DeviceFlowUiState
    data class Waiting(val userCode: String, val verificationUri: String, val remainingSeconds: Int) : DeviceFlowUiState
    data class Failed(val message: String) : DeviceFlowUiState
}

/**
 * The browser-based device flow.
 *
 * Once GitHub issues a code the browser is opened straight away, so the user lands on
 * the verification page with the code already on the clipboard. The dialog then stays
 * up while polling continues, so progress and errors remain visible without needing any
 * permission to draw over other apps.
 */
@Composable
private fun DeviceFlowDialog(
    container: AppContainer,
    state: DeviceFlowUiState,
    onStateChange: (DeviceFlowUiState) -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Opens the browser once per flow, when the code first arrives. Keyed on the
    // user code so a recomposition does not reopen the browser.
    val waiting = state as? DeviceFlowUiState.Waiting
    androidx.compose.runtime.LaunchedEffect(waiting?.userCode) {
        if (waiting != null) {
            onCopyCode(waiting.userCode)
            onOpenBrowser(waiting.verificationUri)
        }
    }

    // Starts the flow and polls exactly once per dialog instance. Keyed on Unit —
    // never on `state` — so the Preparing -> Waiting transition cannot cancel the
    // poll it just started.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (state !is DeviceFlowUiState.Preparing) return@LaunchedEffect
        try {
            val deviceCode = container.deviceFlowAuth.start()
            onStateChange(
                DeviceFlowUiState.Waiting(
                    userCode = deviceCode.userCode,
                    verificationUri = deviceCode.verificationUri,
                    remainingSeconds = deviceCode.expiresInSeconds,
                ),
            )
            val token = container.deviceFlowAuth.awaitToken(
                deviceCode = deviceCode.deviceCode,
                intervalSeconds = deviceCode.intervalSeconds,
                expiresInSeconds = deviceCode.expiresInSeconds,
            )
            container.credentialStore.save(
                token = token.accessToken,
                login = runCatching { container.gitHubApi.currentUser().login }.getOrNull(),
                scopes = token.scope,
                tokenType = token.tokenType,
            )
            onSuccess()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onStateChange(
                DeviceFlowUiState.Failed(e.message ?: "GitHub sign-in failed."),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = {
            Text(
                when (state) {
                    is DeviceFlowUiState.Preparing -> "Starting GitHub sign-in…"
                    is DeviceFlowUiState.Waiting -> "Enter this code on GitHub"
                    is DeviceFlowUiState.Failed -> "Sign-in failed"
                },
            )
        },
        text = {
            when (state) {
                is DeviceFlowUiState.Preparing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Requesting a device code from GitHub…")
                    }
                }

                is DeviceFlowUiState.Waiting -> {
                    Column {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                state.userCode,
                                style = CodeTextStyle.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(
                                        26f,
                                        androidx.compose.ui.unit.TextUnitType.Sp,
                                    ),
                                ),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Your browser has opened ${state.verificationUri} and the code is on " +
                                "your clipboard. Paste it there and approve access for GitForge.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Waiting for approval… the code expires in about ${state.remainingSeconds / 60} minutes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onCopyCode(state.userCode) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy code")
                            }
                            Button(onClick = { onOpenBrowser(state.verificationUri) }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open browser")
                            }
                        }
                    }
                }

                is DeviceFlowUiState.Failed -> {
                    Text(state.message)
                }
            }
        },
        confirmButton = {
            if (state is DeviceFlowUiState.Failed) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TokenInputDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Personal access token") },
        text = {
            Column {
                Text(
                    "Create a fine-grained token on GitHub with repository contents read/write access. " +
                        "The token is encrypted on this device and never leaves it except to github.com.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This value is not logged, not echoed to the terminal, and not written into any Git remote.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(token.trim()) }, enabled = token.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdentityDialog(
    initialName: String,
    initialEmail: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var email by remember { mutableStateOf(initialEmail) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit identity") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Used as the author and committer on commits you create, from both the Git screen and the terminal.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifBlank { "GitForge User" }, email.trim().ifBlank { "user@gitforge.local" }) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

