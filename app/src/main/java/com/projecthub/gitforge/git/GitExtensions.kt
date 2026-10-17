package com.projecthub.gitforge.git

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import java.io.File

/** Converts a JGit commit into the app's immutable model. */
fun RevCommit.toCommitInfo(): CommitInfo = CommitInfo(
    sha = name,
    shortSha = name.take(7),
    message = fullMessage.trim(),
    authorName = authorIdent?.name ?: "Unknown",
    authorEmail = authorIdent?.emailAddress ?: "",
    timestampSeconds = commitTime.toLong(),
    parentShas = parents.map { it.name },
)

/**
 * Turns a JGit [DiffEntry] into a renderable diff.
 *
 * JGit 6.x has no built-in unified-diff formatter, so the line diff is produced
 * here with a longest-common-subsequence walk over the two blobs. The walk is
 * bounded by [maxLines] so a very large file cannot stall the UI thread.
 */
fun DiffEntry.toFileDiff(repository: Repository, maxLines: Int = 2000): FileDiff {
    val changeType = when (changeType) {
        DiffEntry.ChangeType.ADD -> ChangeType.ADDED
        DiffEntry.ChangeType.MODIFY -> ChangeType.MODIFIED
        DiffEntry.ChangeType.DELETE -> ChangeType.DELETED
        DiffEntry.ChangeType.RENAME -> ChangeType.RENAMED
        DiffEntry.ChangeType.COPY -> ChangeType.ADDED
    }

    val oldPath = if (changeType == ChangeType.ADDED) null else oldPath
    val newPath = if (changeType == ChangeType.DELETED) null else newPath

    val oldLines = readSide(repository, oldId, oldPath)
    val newLines = readSide(repository, newId, newPath)

    val diffLines = mutableListOf<DiffLine>()
    val path = newPath ?: oldPath ?: ""
    diffLines += DiffLine(DiffLine.Kind.HEADER, "--- ${oldPath ?: "/dev/null"}")
    diffLines += DiffLine(DiffLine.Kind.HEADER, "+++ ${newPath ?: "/dev/null"}")

    var additions = 0
    var deletions = 0

    if (changeType == ChangeType.RENAMED && oldPath == newPath) {
        diffLines += DiffLine(DiffLine.Kind.HEADER, "rename (contents unchanged)")
    } else {
        val ops = lcsDiff(oldLines, newLines, maxLines)
        for (op in ops) {
            when (op.kind) {
                DiffLine.Kind.ADDED -> { additions++; diffLines += DiffLine(op.kind, op.text) }
                DiffLine.Kind.REMOVED -> { deletions++; diffLines += DiffLine(op.kind, op.text) }
                else -> diffLines += DiffLine(op.kind, op.text)
            }
        }
    }

    return FileDiff(
        relativePath = path,
        oldPath = oldPath,
        newPath = newPath,
        changeType = changeType,
        lines = diffLines,
        additions = additions,
        deletions = deletions,
    )
}

/**
 * Resolves an abbreviated id through the object database. JGit diff entries can
 * carry shortened ids, which [ObjectId.fromString] rejects; resolving first
 * keeps the diff working for those entries.
 */
private fun AbbreviatedObjectId.toObjectId(repository: Repository): ObjectId? = runCatching {
    if (name().length >= 40) {
        ObjectId.fromString(name())
    } else {
        // resolve() returns every object matching the prefix; an abbreviated
        // diff id must match exactly one, otherwise fall back to the worktree.
        repository.newObjectReader().use { reader -> reader.resolve(this@toObjectId).singleOrNull() }
    }
}.getOrNull()

/**
 * Reads one side of a diff.
 *
 * For an unstaged change JGit reports the new side with the id of the working-tree
 * content, which is not an object in the repository database. So the object database
 * is tried first and the working tree is the fallback; a null path means the side
 * does not exist (a pure addition or deletion).
 */
private fun readSide(
    repository: Repository,
    id: AbbreviatedObjectId,
    path: String?,
    maxBytes: Long = 1L * 1024 * 1024,
): List<String> {
    val objectId = id.toObjectId(repository)
    if (objectId != null && objectId != ObjectId.zeroId()) {
        readBlobLinesOrNull(repository, objectId, maxBytes)?.let { return it }
    }
    if (path == null) return emptyList()
    val file = File(repository.workTree, path)
    if (!file.isFile) return emptyList()
    if (file.length() > maxBytes) return listOf("<file too large for diff>")
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
    return if (looksBinary(bytes)) emptyList<String>() else bytes.toString(Charsets.UTF_8).split('\n')
}

private fun readBlobLinesOrNull(repository: Repository, id: ObjectId, maxBytes: Long = 1L * 1024 * 1024): List<String>? = try {
    repository.newObjectReader().use { reader ->
        if (reader.getObjectSize(id, org.eclipse.jgit.lib.ObjectReader.OBJ_ANY) > maxBytes) {
            return listOf("<blob too large for diff>")
        }
    }
    val bytes = repository.open(id).bytes
    if (looksBinary(bytes)) emptyList() else bytes.toString(Charsets.UTF_8).split('\n')
} catch (_: Throwable) {
    null
}

private fun readBlobLines(repository: Repository, id: ObjectId): List<String> {
    if (id == ObjectId.zeroId()) return emptyList()
    return readBlobLinesOrNull(repository, id) ?: emptyList()
}

private fun looksBinary(bytes: ByteArray): Boolean {
    val sample = bytes.take(8000)
    if (sample.isEmpty()) return false
    return sample.any { it == 0.toByte() }
}

private data class DiffOp(val kind: DiffLine.Kind, val text: String)

/**
 * Line-oriented diff via an LCS table with a memory guard. For files larger than
 * [maxLines] the algorithm degrades to a whole-file replacement description
 * rather than allocating a quadratic table.
 */
private fun lcsDiff(old: List<String>, new: List<String>, maxLines: Int): List<DiffOp> {
    if (old.size > maxLines || new.size > maxLines) {
        val big = mutableListOf<DiffOp>()
        big += DiffOp(DiffLine.Kind.HEADER, "File too large for a line-by-line diff (${old.size} -> ${new.size} lines)")
        old.forEach { big += DiffOp(DiffLine.Kind.REMOVED, "-$it") }
        new.forEach { big += DiffOp(DiffLine.Kind.ADDED, "+$it") }
        return big
    }

    val n = old.size
    val m = new.size
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            table[i][j] = if (old[i] == new[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }

    val ops = mutableListOf<DiffOp>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            old[i] == new[j] -> { ops += DiffOp(DiffLine.Kind.CONTEXT, " $old[i]"); i++; j++ }
            table[i + 1][j] >= table[i][j + 1] -> { ops += DiffOp(DiffLine.Kind.REMOVED, "-${old[i]}"); i++ }
            else -> { ops += DiffOp(DiffLine.Kind.ADDED, "+${new[j]}"); j++ }
        }
    }
    while (i < n) { ops += DiffOp(DiffLine.Kind.REMOVED, "-${old[i]}"); i++ }
    while (j < m) { ops += DiffOp(DiffLine.Kind.ADDED, "+${new[j]}"); j++ }

    return ops
}
