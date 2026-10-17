package com.projecthub.gitforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.projecthub.gitforge.data.prefs.AppSettings
import com.projecthub.gitforge.di.AppContainer
import com.projecthub.gitforge.ui.components.ScreenTopBar
import com.projecthub.gitforge.ui.nav.Destination
import com.projecthub.gitforge.ui.screens.DiagnosticsScreen
import com.projecthub.gitforge.ui.screens.EditorScreen
import com.projecthub.gitforge.ui.screens.GitScreen
import com.projecthub.gitforge.ui.screens.HomeScreen
import com.projecthub.gitforge.ui.screens.PackagesScreen
import com.projecthub.gitforge.ui.screens.RepositoriesScreen
import com.projecthub.gitforge.ui.screens.SettingsScreen
import com.projecthub.gitforge.ui.screens.TerminalScreen
import com.projecthub.gitforge.ui.screens.UpdateScreen
import com.projecthub.gitforge.update.UpdateCheck
import kotlinx.coroutines.launch

/**
 * The application shell: navigation, adaptive layout and the shared ViewModels.
 *
 * Navigation shape follows Material 3 guidance rather than forcing seven
 * destinations into a phone bottom bar:
 *  * compact width  → bottom bar with the four primary destinations, the rest in a drawer;
 *  * medium/expanded → navigation rail with every destination.
 *
 * The terminal and repositories ViewModels are hoisted here so sessions and loaded
 * pages survive navigation between destinations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitForgeApp(
    container: AppContainer,
    settings: AppSettings,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val repositoriesViewModel: RepositoriesViewModel = viewModel { RepositoriesViewModel(container) }
    val terminalViewModel: TerminalViewModel = viewModel { TerminalViewModel(container) }
    val editorViewModel: EditorViewModel = viewModel { EditorViewModel(container) }
    val gitViewModel: GitViewModel = viewModel { GitViewModel(container) }
    val packagesViewModel: PackagesViewModel = viewModel { PackagesViewModel(container) }
    val updateViewModel: com.projecthub.gitforge.ui.viewmodel.UpdateViewModel =
        viewModel { com.projecthub.gitforge.ui.viewmodel.UpdateViewModel(container) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val current = Destination.fromRoute(currentRoute)

    val repositoriesState by repositoriesViewModel.state.collectAsStateWithLifecycle()
    val activeRepositoryId = repositoriesState.activeRepositoryId

    // Keep the active repository id in sync for screens that need it.
    LaunchedEffect(activeRepositoryId) {
        if (activeRepositoryId > 0 && settings.lastActiveRepositoryId != activeRepositoryId) {
            container.settingsRepository.setLastActiveRepository(activeRepositoryId)
        }
    }

    val configuration = LocalConfiguration.current
    val useRail = configuration.screenWidthDp >= 700

    val openUrl: (String) -> Unit = { url ->
        if (!openUrlInBrowser(context, url)) {
            coroutineScope.launch { snackbarHostState.showSnackbar("No browser is available to open $url") }
        }
    }

    val navigate: (String) -> Unit = { route ->
        coroutineScope.launch {
            drawerState.close()
            val primary = Destination.primary.any { it.route == route }
            if (primary) {
                navController.navigate(route) {
                    popUpTo(Destination.HOME.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } else {
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
    }

    // One silent check per app launch. The result is only surfaced when an update
    // exists, so a device that is offline or up to date is never interrupted.
    LaunchedEffect(Unit) {
        updateViewModel.checkForUpdate()
    }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect((updateState.check as? UpdateCheck.Available)?.release?.tagName) {
        val available = updateState.check as? UpdateCheck.Available ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "GitForge ${available.release.version} is available",
            actionLabel = "Update",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) navigate(Destination.UPDATE.route)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !useRail,
        drawerContent = {
            ModalDrawerSheet {
                DrawerHeader(
                    gitHubConnected = repositoriesState.gitHubConnected,
                    login = repositoriesState.gitHubLogin,
                )
                HorizontalDivider()
                Destination.all.forEach { destination ->
                    NavigationDrawerItem(
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                        selected = destination == current,
                        onClick = { navigate(destination.route) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    Destination.all.forEach { destination ->
                        NavigationRailItem(
                            icon = { Icon(destination.icon, contentDescription = destination.description) },
                            label = { Text(destination.label) },
                            selected = destination == current,
                            onClick = { navigate(destination.route) },
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    val canGoBack = navController.previousBackStackEntry != null
                    ScreenTopBar(
                        title = current.label,
                        onBack = if (canGoBack) ({ navController.navigateUp() }) else null,
                        actions = {
                            if (!useRail && !canGoBack) {
                                IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Open navigation drawer")
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar {
                            Destination.primary.forEach { destination ->
                                NavigationBarItem(
                                    icon = { Icon(destination.icon, contentDescription = destination.description) },
                                    label = { Text(destination.label) },
                                    selected = destination == current,
                                    onClick = { navigate(destination.route) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Destination.HOME.route,
                    ) {
                        composable(Destination.HOME.route) {
                            HomeScreen(
                                container = container,
                                repositoriesViewModel = repositoriesViewModel,
                                onNavigate = navigate,
                                onCreateLocalProject = { navigate(Destination.REPOSITORIES.route) },
                                onCloneRepository = { navigate(Destination.REPOSITORIES.route) },
                                onOpenRepository = { id ->
                                    repositoriesViewModel.openRepository(id)
                                    navigate(Destination.EDITOR.route)
                                },
                            )
                        }

                        composable(Destination.REPOSITORIES.route) {
                            RepositoriesScreen(
                                viewModel = repositoriesViewModel,
                                onOpenRepository = { id ->
                                    repositoriesViewModel.openRepository(id)
                                    navigate(Destination.EDITOR.route)
                                },
                                onGoToSettings = { navigate(Destination.SETTINGS.route) },
                            )
                        }

                        composable(Destination.EDITOR.route) {
                            EditorScreen(
                                container = container,
                                viewModel = editorViewModel,
                                activeRepositoryId = activeRepositoryId,
                                onGoToRepositories = { navigate(Destination.REPOSITORIES.route) },
                            )
                        }

                        composable(Destination.GIT.route) {
                            GitScreen(
                                viewModel = gitViewModel,
                                activeRepositoryId = activeRepositoryId,
                                onGoToRepositories = { navigate(Destination.REPOSITORIES.route) },
                            )
                        }

                        composable(Destination.TERMINAL.route) {
                            TerminalScreen(
                                container = container,
                                viewModel = terminalViewModel,
                                activeRepositoryId = activeRepositoryId,
                                onGoToPackages = { navigate(Destination.PACKAGES.route) },
                            )
                        }

                        composable(Destination.PACKAGES.route) {
                            PackagesScreen(
                                container = container,
                                viewModel = packagesViewModel,
                                activeRepositoryId = activeRepositoryId,
                                onRunInTerminal = { command ->
                                    // Bind the session to the repo first: TerminalScreen's
                                    // ensureSession runs after navigation and would otherwise
                                    // lose this race, executing npm in the previous cwd.
                                    if (activeRepositoryId > 0) {
                                        val root = repositoriesState.localRepositories
                                            .firstOrNull { it.entity.id == activeRepositoryId }
                                            ?.let {
                                                runCatching {
                                                    container.workspaceManager.resolveRelative(it.entity.localPath)
                                                }.getOrNull()
                                            }
                                        if (root != null && root.isDirectory) {
                                            terminalViewModel.ensureSession(root)
                                            terminalViewModel.setWorkingDirectory(root.absolutePath)
                                        }
                                    }
                                    navigate(Destination.TERMINAL.route)
                                    terminalViewModel.runCommand(command)
                                },
                            )
                        }

                        composable(Destination.SETTINGS.route) {
                            SettingsScreen(
                                container = container,
                                repositoriesViewModel = repositoriesViewModel,
                                onOpenUrl = openUrl,
                                onOpenDiagnostics = { navigate(Destination.DIAGNOSTICS.route) },
                                onOpenUpdate = { navigate(Destination.UPDATE.route) },
                                installedVersion = updateViewModel.state.value.currentVersion,
                            )
                        }

                        composable(Destination.DIAGNOSTICS.route) {
                            DiagnosticsScreen(container = container)
                        }

                        composable(Destination.UPDATE.route) {
                            UpdateScreen(
                                viewModel = updateViewModel,
                                onOpenReleasePage = openUrl,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(gitHubConnected: Boolean, login: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("GitForge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Code anywhere. Build everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (gitHubConnected) Icons.Default.Check else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (gitHubConnected) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (gitHubConnected) (login ?: "GitHub connected") else "GitHub not connected",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** Small helper that opens a URL in whatever browser the device provides. */
private fun openUrlInBrowser(context: android.content.Context, url: String): Boolean = runCatching {
    context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
    )
    true
}.getOrDefault(false)
