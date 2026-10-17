package com.projecthub.gitforge.update

/**
 * Compares dotted version strings and release tags.
 *
 * The updater must decide whether a release is newer than the installed build, and
 * doing that with string comparison gets `1.10.0` vs `1.9.0` wrong. This parses the
 * numeric components and compares them in order, so no version ordering decision
 * depends on string ordering.
 */
object VersionComparator {

    /** Strips a leading `v`/`V` and any build metadata, then splits on `.`. */
    fun parse(raw: String): List<Int> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return emptyList()
        val head = trimmed.substringBefore('+').substringBefore('-')
        return head.split('.')
            .map { part -> parseComponent(part.takeWhile { it.isDigit() }) }
    }

    private fun parseComponent(digits: String): Int {
        if (digits.isEmpty()) return 0
        // Cap at Int.MAX so huge components never overflow to 0 via toIntOrNull.
        return digits.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
    }

    /**
     * Negative when [a] is older than [b], zero when equal, positive when newer.
     * Numeric core compares first; a release outranks its own pre-release
     * (`1.1.0` > `1.1.0-rc1`), and two pre-releases compare lexically.
     */
    fun compare(a: String, b: String): Int {
        val left = parse(a)
        val right = parse(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return comparePreRelease(preReleaseOf(a), preReleaseOf(b))
    }

    private fun preReleaseOf(raw: String): String? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val withoutBuild = trimmed.substringBefore('+')
        if (!withoutBuild.contains('-')) return null
        return withoutBuild.substringAfter('-', "").takeIf { it.isNotEmpty() }
    }

    private fun comparePreRelease(left: String?, right: String?): Int {
        if (left == null && right == null) return 0
        // A release is newer than the same core with a pre-release suffix.
        if (left == null) return 1
        if (right == null) return -1
        return left.compareTo(right)
    }

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
