package com.projecthub.gitforge.di

import android.content.Context
import com.projecthub.gitforge.BuildConfig
import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.DefaultAppDispatchers
import com.projecthub.gitforge.data.db.GitForgeDatabase
import com.projecthub.gitforge.data.prefs.SettingsRepository
import com.projecthub.gitforge.github.GitHubApi
import com.projecthub.gitforge.github.GitHubDeviceFlowAuth
import com.projecthub.gitforge.git.GitEngine
import com.projecthub.gitforge.security.CredentialStore
import com.projecthub.gitforge.security.EncryptedCredentialStore
import com.projecthub.gitforge.security.UnavailableCredentialStore
import com.projecthub.gitforge.terminal.TerminalSessionManager
import com.projecthub.gitforge.terminal.runtime.GitCommand
import com.projecthub.gitforge.terminal.runtime.NodeCommand
import com.projecthub.gitforge.terminal.runtime.NpmCommand
import com.projecthub.gitforge.terminal.runtime.PipCommand
import com.projecthub.gitforge.terminal.runtime.PythonCommand
import com.projecthub.gitforge.terminal.runtime.RuntimeManager
import com.projecthub.gitforge.terminal.runtime.RuntimeProbe
import com.projecthub.gitforge.terminal.runtime.ShellInterpreterCommand
import com.projecthub.gitforge.terminal.shell.ExternalCommand
import com.projecthub.gitforge.update.ReleaseClient
import com.projecthub.gitforge.update.UpdateChannel
import com.projecthub.gitforge.update.UpdateManager
import com.projecthub.gitforge.workspace.FileOperations
import com.projecthub.gitforge.workspace.FileTreeReader
import com.projecthub.gitforge.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.io.File

/**
 * Manual dependency container.
 *
 * A hand-written container is deliberate: the graph is small and explicit, it adds
 * no annotation processor to the build, and it makes the lifetime of each object
 * obvious. Everything here is a process-wide singleton; per-screen state lives in
 * ViewModels.
 */
class AppContainer(private val context: Context) {

    val dispatchers = DefaultAppDispatchers

    /** Application-lifetime scope for background work that must outlive a screen. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob()) + dispatchers.io

    // ------------------------------------------------------------------ storage

    val database: GitForgeDatabase by lazy { GitForgeDatabase.create(context) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val workspaceManager: WorkspaceManager by lazy { WorkspaceManager(context.filesDir) }

    val fileOperations: FileOperations by lazy { FileOperations() }

    val fileTreeReader: FileTreeReader by lazy { FileTreeReader() }

    // ---------------------------------------------------------------- security

    /**
     * The credential store degrades instead of crashing. A device with an
     * unavailable or corrupt Keystore gets an app that runs and explains that
     * sign-in is disabled, rather than one that closes on launch — and the reason is
     * written to Diagnostics, where the user can read it without adb.
     */
    val credentialStore: CredentialStore by lazy {
        runCatching {
            val store = EncryptedCredentialStore(context)
            // Eager probe: EncryptedSharedPreferences/MasterKey are lazy, so a
            // constructor alone never throws. Force the Keystore open here.
            check(store.probe()) { "Encrypted credential store probe failed" }
            store as CredentialStore
        }
            .getOrElse { failure ->
                AppLog.e("Secure credential storage is unavailable on this device", failure)
                UnavailableCredentialStore(failure)
            }
    }

    // --------------------------------------------------------------------- git

    val gitEngine: GitEngine by lazy { GitEngine() }

    // ------------------------------------------------------------------ github

    val gitHubApi: GitHubApi by lazy { GitHubApi(tokenProvider = { credentialStore.token() }) }

    val deviceFlowAuth: GitHubDeviceFlowAuth by lazy {
        GitHubDeviceFlowAuth(clientIdProvider = { oauthClientId })
    }

    /**
     * The OAuth client ID is public information and safe in the APK: the device flow
     * exists precisely so that no client *secret* is needed.
     *
     * It is a build-time constant with no runtime override. The browser web flow would
     * need a client secret, which cannot be shipped in an APK, so the device flow is
     * the only secret-free option and there is nothing useful for a user to configure
     * here. Forks set their own value with
     * `-PGITFORGE_OAUTH_CLIENT_ID=...` or by editing the default in build.gradle.kts.
     */
    val oauthClientId: String? =
        BuildConfig.DEFAULT_OAUTH_CLIENT_ID.ifBlank { null }

    // ----------------------------------------------------------------- terminal

    val runtimeManager: RuntimeManager by lazy { RuntimeManager(context.filesDir) }

    val runtimeProbe: RuntimeProbe by lazy { RuntimeProbe(runtimeManager) }

    val terminalSessionManager: TerminalSessionManager by lazy {
        TerminalSessionManager(
            workspaceRoot = workspaceManager.root,
            homeDir = File(runtimeManager.runtimeRoot, "home").apply { mkdirs() },
            runtimeEnvironment = { runtimeManager.environmentFor(emptyMap()) },
            runtimeLabel = { "GitForge shell" },
            scrollbackLimit = { settingsRepository.snapshot().terminalScrollbackLines },
            scope = applicationScope,
        ).also { manager ->
            manager.runtimeCommands = buildRuntimeCommands()
        }
    }

    /**
     * The external commands the shell can reach.
     *
     * `git` is JGit-backed so the terminal and the Git screen share one engine and
     * can never disagree about the index. The rest come from the installed userspace.
     */
    fun buildRuntimeCommands(): List<ExternalCommand> = listOf(
        GitCommand(
            gitEngine = gitEngine,
            identityProvider = {
                val settings = settingsRepository.snapshot()
                commitIdentity(settings)
            },
            tokenProvider = { credentialStore.token() },
        ),
        NodeCommand(runtimeManager),
        NpmCommand(runtimeManager),
        PythonCommand(runtimeManager),
        PipCommand(runtimeManager),
        ShellInterpreterCommand(runtimeManager),
    )

    /**
     * Commit identity, configurable in Settings and used by both the Git UI and the
     * terminal. It is persisted, so a configured identity survives a restart.
     */
    fun commitIdentity(settings: com.projecthub.gitforge.data.prefs.AppSettings): Pair<String, String> =
        gitIdentity

    var gitIdentity: Pair<String, String>
        get() = settingsRepository.snapshot().let { it.gitAuthorName to it.gitAuthorEmail }
        set(value) {
            applicationScope.launch {
                runCatching { settingsRepository.setGitAuthor(value.first, value.second) }
                    .onFailure { AppLog.w("Could not save the commit identity", it) }
            }
        }

    // ------------------------------------------------------------------- update

    /**
     * Checks GitHub for a newer build and installs it.
     *
     * The channel follows the build type: a debug build looks for the debug APK and a
     * release build for the signed release APK, so the two never cross-install. The
     * token is optional — the releases endpoint is public — and is read per call, so
     * the update path works whether or not the user is signed in.
     */
    val updateManager: UpdateManager by lazy { UpdateManager(context) }

    val releaseClient: ReleaseClient by lazy {
        ReleaseClient(owner = BuildConfig.UPDATE_REPO_OWNER, repo = BuildConfig.UPDATE_REPO_NAME)
    }

    val updateChannel: UpdateChannel
        get() = UpdateChannel.forBuild(BuildConfig.DEBUG)

    val workspaceRoot: File get() = workspaceManager.root
}
