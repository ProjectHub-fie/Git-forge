package com.projecthub.gitforge.core

/**
 * Removes credential material from any text before it is logged, shown in an
 * error dialog, written into the terminal scrollback, or echoed into a shell.
 *
 * This is deliberately conservative: it strips known token shapes as well as the
 * generic `key=value` and header forms that Git and HTTP tooling produce.
 */
object Redaction {

    const val MASK = "***REDACTED***"

    private val patterns: List<Regex> = listOf(
        // GitHub token families, including newer gha_/ghd_ (attestations/caches) and
        // any future gh*_ prefix. Bare "gh_" alone is never matched: 16+ chars required.
        Regex("""gh[a-z]_[A-Za-z0-9]{16,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        // Any bearer/authorization header value.
        Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}"""),
        Regex("""(?i)\b(authorization|auth)\s*[:=]\s*\S+"""),
        // URL userinfo: https://user:secret@host
        Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@"""),
        // Common credential query/body parameters.
        Regex("""(?i)\b(access_token|refresh_token|client_secret|token|password|passwd|pwd|apikey|api_key)\s*[:=]\s*["']?[^\s"'&,;]+"""),
        // 64-char hex (SHA-256 digests / private-key material). 40-char SHAs are
        // deliberately left alone so commit ids stay readable in messages.
        Regex("""\b[A-Fa-f0-9]{64}\b"""),
    )

    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input.orEmpty()
        var output: String = input
        for (pattern in patterns) {
            output = pattern.replace(output) { match ->
                val result = match.value
                val group1 = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                when {
                    // Keep the scheme and the header name, drop the secret.
                    result.startsWith("http", ignoreCase = true) && group1 != null -> "$group1$MASK@"
                    group1 != null -> "$group1 $MASK"
                    else -> MASK
                }
            }
        }
        return output
    }

    /** Returns true when [input] appears to still contain credential material. */
    fun containsSecret(input: String?): Boolean {
        if (input.isNullOrEmpty()) return false
        return patterns.any { it.containsMatchIn(input) }
    }
}
