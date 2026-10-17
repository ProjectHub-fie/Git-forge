package com.projecthub.gitforge.workspace

import com.projecthub.gitforge.core.PathSafety
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Result of reading a file for the editor. */
data class FileContent(
    val relativePath: String,
    val text: String,
    val charsetName: String,
    val lineEnding: LineEnding,
    val sizeBytes: Long,
    val lastModified: Long,
    val isBinary: Boolean,
    val truncated: Boolean,
)

enum class LineEnding(val sequence: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r");

    companion object {
        fun detect(raw: String): LineEnding = when {
            raw.contains("\r\n") -> CRLF
            raw.contains('\r') -> CR
            else -> LF
        }
    }
}

/** Outcome of a write, reported back to the UI so it can refresh Git state. */
data class SaveResult(
    val relativePath: String,
    val bytesWritten: Int,
    val lineEnding: LineEnding,
    val charsetName: String,
)

/**
 * All filesystem mutation for the editor.
 *
 * Guarantees that matter for correctness:
 *  * every path is validated against the repository root before use;
 *  * a write is atomic (temp file plus rename), so a crash mid-save cannot leave a
 *    half-written file;
 *  * a save never rewrites line endings or the detected charset;
 *  * a save refuses to clobber a file that changed on disk since it was read.
 */
class FileOperations(private val maxEditableBytes: Long = 4L * 1024 * 1024) {

    fun read(root: File, relativePath: String, maxBytes: Long = maxEditableBytes): FileContent {
        val file = PathSafety.resolveExistingInside(root, relativePath)
        if (file.isDirectory) throw IOException("$relativePath is a directory")
        if (!file.canRead()) throw IOException("Cannot read $relativePath")

        val size = file.length()
        val bytes = if (size > maxBytes) {
            file.inputStream().use { it.readNBytes(maxBytes.toInt()) }
        } else {
            file.readBytes()
        }

        val binary = looksBinary(bytes)
        val charset = if (binary) StandardCharsets.UTF_8 else detectCharset(bytes)
        val decoded = bytes.toString(charset)
        val lineEnding = LineEnding.detect(decoded)

        return FileContent(
            relativePath = PathSafety.relativize(root, file),
            text = decoded,
            charsetName = charset.name(),
            lineEnding = lineEnding,
            sizeBytes = size,
            lastModified = file.lastModified(),
            isBinary = binary,
            truncated = size > maxBytes,
        )
    }

    /**
     * Writes [text] back to the working tree.
     *
     * @param expectedLastModified when non-null, the write is rejected if the file
     *   on disk was modified after this timestamp, which detects the case where a
     *   terminal command or another tool changed the file while the tab was open.
     * @param expectedSizeBytes when non-null alongside [expectedLastModified], the
     *   write is also rejected if the on-disk size differs, so a same-millisecond
     *   replacement that keeps the timestamp cannot slip past the mtime check.
     */
    fun write(
        root: File,
        relativePath: String,
        text: String,
        lineEnding: LineEnding,
        charsetName: String,
        expectedLastModified: Long? = null,
        expectedSizeBytes: Long? = null,
    ): SaveResult {
        val target = PathSafety.resolveInside(root, relativePath)
        if (target.isDirectory) throw IOException("$relativePath is a directory")

        if (expectedLastModified != null &&
            (!target.exists() ||
                target.lastModified() != expectedLastModified ||
                (expectedSizeBytes != null && target.length() != expectedSizeBytes))
        ) {
            throw IOException(
                "Refusing to overwrite $relativePath: the file changed on disk since it was opened.",
            )
        }

        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(StandardCharsets.UTF_8)
        val normalised = normaliseLineEndings(text, lineEnding)
        val bytes = normalised.toByteArray(charset)

        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create ${it.name}") }

        // Pseudo-atomic write: unique temp file plus fsync plus an atomic rename,
        // so a crash mid-save cannot leave a half-written file behind.
        val parent = target.parentFile ?: throw IOException("Cannot resolve parent of $relativePath")
        val temp = File.createTempFile(".${target.name}.", ".gitforge.tmp", parent)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            if (t is IOException) throw t
            throw IOException("Could not write $relativePath: ${t.message}", t)
        }

        return SaveResult(
            relativePath = PathSafety.relativize(root, target),
            bytesWritten = bytes.size,
            lineEnding = lineEnding,
            charsetName = charset.name(),
        )
    }

    fun createFile(root: File, relativePath: String, initialContent: String = ""): File {
        val target = PathSafety.resolveInside(root, relativePath)
        PathSafety.validateNewName(target.name)
        if (target.exists()) throw IOException("${target.name} already exists")
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent directory") }
        target.writeText(initialContent, StandardCharsets.UTF_8)
        return target
    }

    fun createDirectory(root: File, relativePath: String): File {
        val target = PathSafety.resolveInside(root, relativePath)
        PathSafety.validateNewName(target.name)
        if (target.exists()) throw IOException("${target.name} already exists")
        if (!target.mkdirs()) throw IOException("Could not create directory ${target.name}")
        return target
    }

    fun rename(root: File, relativePath: String, newName: String): File {
        PathSafety.validateNewName(newName)
        val source = PathSafety.resolveExistingInside(root, relativePath)
        if (source.name == ".git") throw IOException("The .git directory cannot be renamed")
        val destination = File(source.parentFile, newName)
        if (destination.exists()) throw IOException("$newName already exists")
        if (!source.renameTo(destination)) throw IOException("Could not rename ${source.name}")
        return destination
    }

    fun move(root: File, relativePath: String, newRelativePath: String): File {
        val source = PathSafety.resolveExistingInside(root, relativePath)
        val destination = PathSafety.resolveInside(root, newRelativePath)
        if (destination.exists()) throw IOException("Destination already exists")
        destination.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent directory") }
        if (!source.renameTo(destination)) throw IOException("Could not move ${source.name}")
        return destination
    }

    fun delete(root: File, relativePath: String): Boolean {
        val target = PathSafety.resolveExistingInside(root, relativePath)
        if (target.name == ".git") throw IOException("The .git directory cannot be deleted")
        // Refuse to follow a symlink out of the workspace.
        if (target.canonicalFile != target.absoluteFile) {
            throw IOException("Refusing to delete a symbolic link: $relativePath")
        }
        return target.deleteRecursively()
    }

    /**
     * Case-insensitive content search across the working tree. Streams one file at
     * a time and stops after [maxResults] so a large repository cannot exhaust
     * memory or block for an unbounded time.
     */
    fun search(
        root: File,
        query: String,
        maxResults: Int = 200,
        maxFileBytes: Long = 1L * 1024 * 1024,
        cancelled: () -> Boolean = { false },
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        val hits = mutableListOf<SearchHit>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)

        while (stack.isNotEmpty() && hits.size < maxResults) {
            if (cancelled()) break
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (hits.size >= maxResults) break
                when {
                    child.isDirectory -> {
                        if (child.name != ".git" && !isSymlink(child)) stack.addLast(child)
                    }
                    child.isFile && child.length() <= maxFileBytes -> {
                        val bytes = runCatching { child.readBytes() }.getOrNull() ?: continue
                        if (looksBinary(bytes)) continue
                        val text = bytes.toString(StandardCharsets.UTF_8)
                        text.lineSequence().forEachIndexed { index, line ->
                            if (hits.size < maxResults && line.lowercase().contains(needle)) {
                                hits += SearchHit(
                                    relativePath = PathSafety.relativize(root, child),
                                    lineNumber = index + 1,
                                    lineText = line.take(400),
                                )
                            }
                        }
                    }
                }
            }
        }
        return hits
    }

    private fun isSymlink(file: File): Boolean = try {
        file.canonicalFile != file.absoluteFile
    } catch (_: IOException) {
        true
    }

    private fun normaliseLineEndings(text: String, lineEnding: LineEnding): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        return when (lineEnding) {
            LineEnding.LF -> unified
            LineEnding.CRLF -> unified.replace("\n", "\r\n")
            LineEnding.CR -> unified.replace("\n", "\r")
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(8000)
        if (sample.isEmpty()) return false
        var suspicious = 0
        for (b in sample) {
            if (b == 0.toByte()) return true
            val unsigned = b.toInt() and 0xFF
            if (unsigned < 0x09 || (unsigned in 0x0E..0x1F)) suspicious++
        }
        return suspicious.toDouble() / sample.size > 0.30
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        // UTF-16 BOMs
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }
        // If it decodes cleanly as UTF-8, it is UTF-8. Otherwise fall back to
        // ISO-8859-1 so no byte is ever silently replaced with U+FFFD.
        return if (isValidUtf8(bytes)) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= bytes.size) return false
            repeat(extra) { offset ->
                val next = bytes[i + offset + 1].toInt() and 0xFF
                if (next !in 0x80..0xBF) return false
            }
            i += extra + 1
        }
        return true
    }
}

data class SearchHit(
    val relativePath: String,
    val lineNumber: Int,
    val lineText: String,
)
