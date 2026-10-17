package com.projecthub.gitforge.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.projecthub.gitforge.BuildConfig
import com.projecthub.gitforge.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** What the updater is currently doing, so the UI can show real progress. */
sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data class Downloading(val fileName: String, val bytesRead: Long, val totalBytes: Long) : UpdateProgress
    data class Verifying(val fileName: String) : UpdateProgress
    data object ReadyToInstall : UpdateProgress
}

/** Outcome of staging a downloaded APK. */
sealed interface InstallResult {
    /** The download verified and the APK was written to app-private storage. */
    data class Staged(val file: File) : InstallResult

    /**
     * The download succeeded but the signing certificate differs from the installed
     * app's, so installing it is impossible and would fail at the system level. This
     * is reported rather than attempted.
     */
    data class SignatureMismatch(val expected: String, val actual: String) : InstallResult

    /** The bytes did not match the advertised SHA-256. */
    data class ChecksumMismatch(val expected: String, val actual: String) : InstallResult

    data class Failed(val message: String) : InstallResult
}

/**
 * Downloads a release APK, verifies it, and hands it to the system installer.
 *
 * Two guarantees are enforced before the user is ever shown an install prompt:
 *
 *  1. **Integrity** — when the release advertises a SHA-256, the bytes on disk must
 *     match it, so a truncated or tampered download is rejected.
 *  2. **Continuity** — the downloaded APK must be signed by the same certificate as
 *     the installed app. Android enforces this at install time, but failing early
 *     turns an opaque "App not installed" into an explanation. A build signed with a
 *     different key cannot update this app, and the updater says so.
 *
 * The APK is stored in app-private cache, never in shared storage, so no storage
 * permission is involved and no other app can read it before installation.
 */
class UpdateManager(private val context: Context) {

    private val installDir: File
        get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * Downloads and verifies [asset], returning the staged file or the reason it was
     * rejected. Safe to call from a background coroutine.
     */
    suspend fun stage(
        asset: UpdateAsset,
        onProgress: (UpdateProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        // Release assets come from GitHub (browser_download_url); only HTTPS is
        // ever legitimate. Reject anything else before touching disk or network.
        val scheme = runCatching { android.net.Uri.parse(asset.url).scheme }.getOrNull()
        if (!scheme.equals("https", ignoreCase = true)) {
            return@withContext InstallResult.Failed("Refusing to download from a non-HTTPS URL.")
        }
        val safeName = asset.name.substringAfterLast('/').substringAfterLast('\\')
        if (safeName.isBlank() || !safeName.matches(Regex("[A-Za-z0-9._-]+"))) {
            return@withContext InstallResult.Failed("Refusing to stage an asset with an unsafe file name.")
        }
        val target = File(installDir, safeName)
        // Canonical-path containment: a crafted name must never escape the sandbox.
        val dirCanonical = runCatching { installDir.canonicalPath }.getOrNull()
        val targetCanonical = runCatching { target.canonicalPath }.getOrNull()
        if (dirCanonical == null || targetCanonical == null ||
            !(targetCanonical == dirCanonical || targetCanonical.startsWith("$dirCanonical${File.separatorChar}"))
        ) {
            return@withContext InstallResult.Failed("Refusing to stage an asset outside the update directory.")
        }
        try {
            if (target.exists()) target.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            onProgress(UpdateProgress.Downloading(asset.name, 0, asset.sizeBytes))

            val connection = java.net.URL(asset.url).openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        // Report at most every 256 KB so the UI is not flooded.
                        if (read % (256 * 1024) < buffer.size) {
                            onProgress(UpdateProgress.Downloading(asset.name, read, asset.sizeBytes))
                        }
                    }
                }
            }

            onProgress(UpdateProgress.Verifying(asset.name))
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }

            if (asset.sha256 != null && !actualSha.equals(asset.sha256, ignoreCase = true)) {
                target.delete()
                return@withContext InstallResult.ChecksumMismatch(asset.sha256, actualSha)
            }

            // Size check catches a truncated download even when no digest is advertised.
            if (asset.sizeBytes > 0 && read != asset.sizeBytes) {
                target.delete()
                return@withContext InstallResult.Failed(
                    "The download was ${read} bytes but the release lists ${asset.sizeBytes}.",
                )
            }

            // Fail closed: only a clean bill (null) proceeds. Every other
            // verdict — mismatch, unreadable certs, or any future variant —
            // discards the download instead of installing it.
            when (val signerCheck = compareSigners(target)) {
                null -> Unit
                else -> {
                    target.delete()
                    return@withContext signerCheck
                }
            }

            onProgress(UpdateProgress.ReadyToInstall)
            AppLog.i("Update staged: ${asset.name} (${read} bytes, sha256 ${actualSha.take(12)}…)")
            InstallResult.Staged(target)
        } catch (e: Exception) {
            AppLog.w("Update download failed", e)
            runCatching { target.delete() }
            InstallResult.Failed(e.message ?: "The download failed.")
        }
    }

    /**
     * Returns a [InstallResult.SignatureMismatch] when the staged APK is signed by a
     * different key than the running app, an [InstallResult.Failed] when either
     * side cannot be read (fail closed: unverifiable means untrusted), or null
     * when the signers match.
     */
    private fun compareSigners(apk: File): InstallResult? {
        val pm = context.packageManager
        val installed = currentSignatures(pm)
            ?: return InstallResult.Failed("Could not verify the installed app's signature.")
        val candidate = apkSignatures(pm, apk)
            ?: return InstallResult.Failed("Could not verify the downloaded APK's signature.")

        // A release build and a debug build are signed with different keys, so a
        // debug build following the release channel (or vice versa) is refused here
        // with a clear message instead of a silent failure at install time.
        return if (installed.toSet() != candidate.toSet()) {
            InstallResult.SignatureMismatch(
                expected = installed.firstOrNull()?.take(16) ?: "unknown",
                actual = candidate.firstOrNull()?.take(16) ?: "unknown",
            )
        } else {
            null
        }
    }

    private fun currentSignatures(pm: PackageManager): List<String>? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        signatures?.map { sha256(it.toByteArray()) }
    } catch (e: Exception) {
        AppLog.w("Could not read the installed signing certificate", e)
        null
    }

    private fun apkSignatures(pm: PackageManager, apk: File): List<String>? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info?.signatures
        }
        signatures?.map { sha256(it.toByteArray()) }
    } catch (e: Exception) {
        AppLog.w("Could not read the downloaded APK's signing certificate", e)
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** True when this device permits GitForge to install packages. */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen where the user grants the install-packages permission. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.w("Could not open the install-permission screen", it) }
    }

    /**
     * Launches the system package installer for a staged APK. The user still sees and
     * confirms the normal Android install prompt; this never installs silently.
     */
    fun install(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLog.w("Could not launch the package installer", e)
            false
        }
    }

    /** Removes staged APKs. Called after an install starts and on startup cleanup. */
    fun clearStaged() {
        runCatching {
            installDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        }
    }
}
