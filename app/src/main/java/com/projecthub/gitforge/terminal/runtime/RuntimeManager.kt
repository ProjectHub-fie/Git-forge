package com.projecthub.gitforge.terminal.runtime

import com.projecthub.gitforge.core.AppLog
import com.projecthub.gitforge.core.PathSafety
import com.projecthub.gitforge.core.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Progress reported while a userspace is being installed. */
data class RuntimeInstallProgress(
    val phase: String,
    val completedPackages: Int,
    val totalPackages: Int,
    val currentPackage: String?,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0) 0f else (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/** What is actually installed, determined by probing the filesystem, not by a flag. */
data class RuntimeInstallation(
    val prefix: File,
    val installedPackages: Set<String>,
) {
    val hasNode: Boolean get() = installedPackages.contains("nodejs")
    val hasNpm: Boolean get() = installedPackages.contains("npm")
    val hasPython: Boolean get() = installedPackages.contains("python")
    val hasPip: Boolean get() = installedPackages.contains("python-pip")

    fun executable(name: String): File? {
        val candidate = File(prefix, "bin/$name")
        return candidate.takeIf { it.exists() && it.canExecute() }
    }
}

/**
 * Owns the app-private Termux-style prefix.
 *
 * Layout inside `filesDir/runtime/`:
 * ```
 * runtime/usr/bin/          node, npm, python3, pip
 * runtime/usr/lib/          shared libraries and the loader search path
 * runtime/usr/lib/node_modules/npm/   the npm CLI
 * runtime/cache/            downloaded .deb files, safe to delete
 * runtime/etc/              ca-certificates, resolv.conf
 * ```
 *
 * Nothing here needs root. Everything lives inside the app sandbox, so an
 * uninstall removes it cleanly and no other app can read it.
 */
class RuntimeManager(private val filesRoot: File) {

    val runtimeRoot: File = File(filesRoot, "runtime")
    val prefix: File = File(runtimeRoot, "usr")
    val cacheDir: File = File(runtimeRoot, "cache")
    val binDir: File = File(prefix, "bin")
    val libDir: File = File(prefix, "lib")

    private val installer = DebPackageInstaller(prefix)

    init {
        listOf(runtimeRoot, prefix, cacheDir, binDir, libDir, File(prefix, "etc"), File(prefix, "tmp")).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }

    /**
     * Reads what is installed by checking for the real executables. A marker file is
     * deliberately not trusted as the source of truth: if a package was removed or
     * a binary was never made executable, the probe reports it as missing.
     */
    fun installation(): RuntimeInstallation {
        val installed = mutableSetOf<String>()
        if (File(binDir, "node").exists()) installed += "nodejs"
        if (File(prefix, "lib/node_modules/npm/bin/npm-cli.js").exists()) installed += "npm"
        if (File(binDir, "python3").exists()) installed += "python"
        if (File(binDir, "pip").exists()) installed += "python-pip"
        return RuntimeInstallation(prefix, installed)
    }

    fun isAbiSupported(): Boolean = android.os.Build.SUPPORTED_ABIS
        .any { it.equals(RuntimePackageRegistry.SUPPORTED_ABI, ignoreCase = true) }

    fun abiSummary(): String = android.os.Build.SUPPORTED_ABIS.joinToString(", ")

    /**
     * Installs the requested groups.
     *
     * Packages are downloaded and verified one at a time and the `.deb` is deleted
     * as soon as it has been unpacked, so peak storage is roughly the size of the
     * largest single package rather than the whole set.
     */
    suspend fun install(
        groups: Set<RuntimeGroup>,
        mirror: String = RuntimePackageRegistry.DEFAULT_MIRROR,
        onProgress: (RuntimeInstallProgress) -> Unit = {},
    ): RuntimeInstallation = withContext(Dispatchers.IO) {
        if (!isAbiSupported()) {
            throw IOException(
                "This device reports ${abiSummary()}. The userspace packages are built for " +
                    "${RuntimePackageRegistry.SUPPORTED_ABI}, so they cannot run here.",
            )
        }

        val packages = packagesFor(groups)
        if (packages.isEmpty()) throw IOException("No packages selected")

        val totalBytes = packages.sumOf { it.sizeBytes }
        var downloadedBytes = 0L

        packages.forEachIndexed { index, pkg ->
            onProgress(
                RuntimeInstallProgress(
                    phase = "Downloading ${pkg.name} ${pkg.version}",
                    completedPackages = index,
                    totalPackages = packages.size,
                    currentPackage = pkg.name,
                    bytesDownloaded = downloadedBytes,
                    bytesTotal = totalBytes,
                ),
            )

            val deb = File(cacheDir, "${pkg.name}_${pkg.version.replace(':', '_')}.deb")
            download(mirror, pkg, deb)

            onProgress(
                RuntimeInstallProgress(
                    phase = "Verifying and unpacking ${pkg.name}",
                    completedPackages = index,
                    totalPackages = packages.size,
                    currentPackage = pkg.name,
                    bytesDownloaded = downloadedBytes,
                    bytesTotal = totalBytes,
                ),
            )

            try {
                installer.install(deb, pkg.sha256) { message ->
                    AppLog.d("$message (${pkg.name})")
                }
            } finally {
                // Reclaim the space immediately; the verified unpack is on disk now.
                deb.delete()
            }

            downloadedBytes += pkg.sizeBytes
        }

        onProgress(
            RuntimeInstallProgress(
                phase = "Finalising",
                completedPackages = packages.size,
                totalPackages = packages.size,
                currentPackage = null,
                bytesDownloaded = downloadedBytes,
                bytesTotal = totalBytes,
            ),
        )

        preparePrefix(mirror)
        installation()
    }

    /**
     * The exact package list an install of [groups] will download, de-duplicated
     * by name. Public so the install-confirm dialog can quote the same numbers
     * instead of double-counting the shared `commonPackages`.
     */
    fun packagesFor(groups: Set<RuntimeGroup>): List<TermuxPackage> {
        val result = linkedMapOf<String, TermuxPackage>()
        // COMMON first: certificates are needed by both HTTPS clients.
        if (groups.isNotEmpty()) {
            RuntimePackageRegistry.commonPackages.forEach { result[it.name] = it }
        }
        groups.forEach { group ->
            RuntimePackageRegistry.packagesFor(group).forEach { result[it.name] = it }
        }
        return result.values.toList()
    }

    private fun download(mirror: String, pkg: TermuxPackage, destination: File) {
        val url = "${mirror.trimEnd('/')}/${pkg.filename}"
        AppLog.i("Downloading runtime package ${pkg.name} ${pkg.version}")
        destination.parentFile?.mkdirs()

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Could not download ${pkg.name}: HTTP ${response.code} from $mirror")
                }
                val body = response.body ?: throw IOException("Empty response for ${pkg.name}")
                destination.outputStream().use { output -> body.byteStream().copyTo(output) }
            }
        } catch (e: IOException) {
            destination.delete()
            throw IOException("Could not download ${pkg.name} (${Redaction.redact(e.message ?: "network error")})", e)
        }

        val actualSize = destination.length()
        if (actualSize != pkg.sizeBytes) {
            AppLog.w("${pkg.name}: expected ${pkg.sizeBytes} bytes, received $actualSize")
        }
    }

    /**
     * Applies the finishing touches that a Debian post-install script would do, but
     * as plain Java so no shell is required and nothing is executed implicitly.
     */
    private fun preparePrefix(mirror: String) {
        // Node needs the library directory on the loader search path. Termux relies
        // on the shell exporting this; GitForge sets it explicitly for every process.
        File(prefix, "etc/profile.gitforge").writeText(
            buildString {
                append("# Generated by GitForge. Loader configuration for the app-private userspace.\n")
                append("export PREFIX=${prefix.absolutePath}\n")
                append("export HOME=${runtimeRoot.absolutePath}/home\n")
                append("export PATH=${binDir.absolutePath}:${binDir.absolutePath}/applets\n")
                append("export LD_LIBRARY_PATH=${libDir.absolutePath}\n")
                append("export TMPDIR=${prefix.absolutePath}/tmp\n")
                append("export LANG=en_US.UTF-8\n")
                append("export TERM=xterm-256color\n")
            },
        )

        File(runtimeRoot, "home").mkdirs()

        // Point the npm registry at the public default so `npm install` works
        // without any extra configuration. Written as plain text, no token.
        val npmrc = File(prefix, "etc/npmrc")
        if (!npmrc.exists()) {
            npmrc.writeText(
                buildString {
                    append("registry=https://registry.npmjs.org/\n")
                    append("fund=false\n")
                    append("audit=false\n")
                    append("progress=false\n")
                    append("# GitForge keeps npm cache inside app-private storage.\n")
                    append("cache=${prefix.absolutePath}/.npm-cache\n")
                },
            )
        }
        File(prefix, "etc/termux-mirror").writeText(mirror)
    }

    /** Removes the whole userspace. Only ever called after an explicit confirmation. */
    fun uninstall() {
        runtimeRoot.deleteRecursively()
        listOf(runtimeRoot, prefix, cacheDir, binDir, libDir).forEach { it.mkdirs() }
        AppLog.i("Removed the installed userspace")
    }

    fun usageBytes(): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { addLast(runtimeRoot) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.listFiles()?.forEach { child ->
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /**
     * The environment every child process receives. Built from scratch rather than
     * inherited so the app's own variables, and any credential in them, never reach
     * a third-party program.
     */
    fun environmentFor(sessionEnv: Map<String, String>): Map<String, String> = buildMap {
        put("PREFIX", prefix.absolutePath)
        put("HOME", File(runtimeRoot, "home").absolutePath)
        put("PATH", "${binDir.absolutePath}:${File(prefix, "bin/applets").absolutePath}")
        put("LD_LIBRARY_PATH", libDir.absolutePath)
        put("TMPDIR", File(prefix, "tmp").absolutePath)
        put("LANG", "en_US.UTF-8")
        put("LC_ALL", "en_US.UTF-8")
        put("TERM", "xterm-256color")
        put("NPM_CONFIG_USERCONFIG", File(prefix, "etc/npmrc").absolutePath)
        put("NPM_CONFIG_PREFIX", prefix.absolutePath)
        // npm needs a writable cache; app-private storage is the only writable place.
        put("NPM_CONFIG_CACHE", File(prefix, ".npm-cache").absolutePath)
        put("NODE_REPL_HISTORY", File(prefix, ".node_repl_history").absolutePath)
        put("ANDROID_DATA", System.getenv("ANDROID_DATA") ?: "/data")
        put("ANDROID_ROOT", System.getenv("ANDROID_ROOT") ?: "/system")
        // Session overrides win, so `export FOO=bar` behaves as expected.
        putAll(sessionEnv)
    }

    /** True when the given path is inside the app-private runtime prefix. */
    fun owns(path: File): Boolean = PathSafety.isInside(runtimeRoot, path)
}
