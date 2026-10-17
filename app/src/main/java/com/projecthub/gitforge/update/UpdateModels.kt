package com.projecthub.gitforge.update

/** Which build channel an installed app follows. */
enum class UpdateChannel(val label: String) {
    /** The signed release APK from the repository's releases. */
    STABLE("Release"),

    /** The debug APK, for a device running a debug build. */
    DEBUG("Debug"),
    ;

    companion object {
        /** A debug build follows the debug channel; a release build follows releases. */
        fun forBuild(isDebug: Boolean): UpdateChannel = if (isDebug) DEBUG else STABLE
    }
}

/** One downloadable APK attached to a release. */
data class UpdateAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    /** Expected SHA-256 of the download, when the release advertises one. */
    val sha256: String? = null,
)

/**
 * A release the updater could install.
 *
 * [version] is the version name parsed out of the tag (`V1.1.0` becomes `1.1.0`),
 * [tagName] is kept verbatim for display and for the GitHub link.
 */
data class UpdateRelease(
    val tagName: String,
    val version: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val assets: List<UpdateAsset>,
    /** True when GitHub marks the release as a draft; never offered as an update. */
    val isDraft: Boolean = false,
    /** True when GitHub marks the release as a pre-release; offered only off STABLE. */
    val isPrerelease: Boolean = false,
) {
    /**
     * The APK that matches the requested [channel]. A release carries both a signed
     * release APK and a debug APK, so picking the wrong one would install a build the
     * user did not ask for — and, because the two are signed with different keys, it
     * would also fail at install time. Selection is therefore exact first, then a
     * channel-consistent fallback that never crosses from release to debug.
     */
    fun assetFor(channel: UpdateChannel): UpdateAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        apks.firstOrNull { it.name.equals(channel.apkName, ignoreCase = true) }?.let { return it }
        return when (channel) {
            UpdateChannel.STABLE -> apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
            // Never cross channels: a debug build waits for a debug APK.
            UpdateChannel.DEBUG -> apks.firstOrNull { it.name.contains("debug", ignoreCase = true) }
        }
    }

    private val UpdateChannel.apkName: String
        get() = when (this) {
            UpdateChannel.STABLE -> "app-release.apk"
            UpdateChannel.DEBUG -> "app-debug.apk"
        }
}

/** What the updater concluded after asking GitHub. */
sealed interface UpdateCheck {
    /** A newer build exists on [channel]. */
    data class Available(
        val release: UpdateRelease,
        val channel: UpdateChannel,
    ) : UpdateCheck

    /** The installed build is current. */
    data class UpToDate(val currentVersion: String) : UpdateCheck

    /** The check could not be completed. [message] is safe to show to the user. */
    data class Failed(val message: String) : UpdateCheck
}
