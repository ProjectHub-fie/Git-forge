package com.projecthub.gitforge.workspace

import com.projecthub.gitforge.core.PathSafety
import java.io.File

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

/**
 * Reads the working tree for the file explorer.
 *
 * The listing is lazy: [list] returns one directory level at a time so a
 * repository with tens of thousands of files never materialises a full tree in
 * memory. Directories sort first, then files, both case-insensitively.
 */
class FileTreeReader(private val maxEntriesPerDirectory: Int = 2000) {

    fun list(root: File, relativeDir: String): List<FileNode> {
        val dir = if (relativeDir.isBlank()) {
            root
        } else {
            PathSafety.resolveExistingInside(root, relativeDir)
        }
        if (!dir.isDirectory) return emptyList()

        val children = dir.listFiles() ?: return emptyList()
        return children.asSequence()
            .filterNot { it.name == ".git" }
            .take(maxEntriesPerDirectory)
            .map { child ->
                FileNode(
                    name = child.name,
                    relativePath = relative(child, root),
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isFile) child.length() else 0L,
                    lastModified = child.lastModified(),
                )
            }
            .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private fun relative(file: File, root: File): String =
        PathSafety.relativize(root, file)
}
