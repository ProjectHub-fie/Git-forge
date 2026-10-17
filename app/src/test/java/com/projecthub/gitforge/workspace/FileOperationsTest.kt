package com.projecthub.gitforge.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Exercises the real editor file layer against a real filesystem. These are not
 * mocks: a save must land on disk with the right bytes, and a conflicting save must
 * be refused.
 */
class FileOperationsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ops = FileOperations()

    private fun repo(): File = temp.newFolder("repo").also { it.mkdirs() }

    // ------------------------------------------------------------------ read/write

    @Test
    fun `writes and reads a file back with identical content`() {
        val root = repo()
        ops.write(root, "src/Hello.kt", "fun main() {}\n", LineEnding.LF, "UTF-8")
        val content = ops.read(root, "src/Hello.kt")
        assertEquals("fun main() {}\n", content.text)
        assertEquals(File(root, "src/Hello.kt").readText(), "fun main() {}\n")
    }

    @Test
    fun `creates parent directories on write`() {
        val root = repo()
        ops.write(root, "a/b/c/d/deep.txt", "deep", LineEnding.LF, "UTF-8")
        assertTrue(File(root, "a/b/c/d/deep.txt").isFile)
    }

    @Test
    fun `preserves CRLF line endings on save`() {
        val root = repo()
        val original = "one\r\ntwo\r\n"
        File(root, "win.txt").writeText(original)
        val content = ops.read(root, "win.txt")
        assertEquals(LineEnding.CRLF, content.lineEnding)

        ops.write(root, "win.txt", "one\nthree\n", content.lineEnding, content.charsetName)
        assertEquals("one\r\nthree\r\n", File(root, "win.txt").readText())
    }

    @Test
    fun `preserves LF line endings on save`() {
        val root = repo()
        File(root, "unix.txt").writeText("one\ntwo\n")
        val content = ops.read(root, "unix.txt")
        assertEquals(LineEnding.LF, content.lineEnding)
        ops.write(root, "unix.txt", "alpha\nbeta\n", content.lineEnding, content.charsetName)
        assertEquals("alpha\nbeta\n", File(root, "unix.txt").readText())
    }

    @Test
    fun `save is refused when the file changed on disk since it was read`() {
        val root = repo()
        val file = File(root, "notes.txt")
        file.writeText("original\n")
        val content = ops.read(root, "notes.txt")

        // Simulate another writer (for example a terminal command).
        file.writeText("changed by something else\n")
        file.setLastModified(content.lastModified + 5_000)

        try {
            ops.write(
                root = root,
                relativePath = "notes.txt",
                text = "my edit\n",
                lineEnding = content.lineEnding,
                charsetName = content.charsetName,
                expectedLastModified = content.lastModified,
            )
            fail("A save over a newer file must be refused")
        } catch (_: IOException) {
            // Expected: the on-disk version wins until the user reloads.
        }
        assertEquals("changed by something else\n", file.readText())
    }

    @Test
    fun `save succeeds when the on-disk timestamp is unchanged`() {
        val root = repo()
        val file = File(root, "notes.txt")
        file.writeText("original\n")
        val content = ops.read(root, "notes.txt")
        ops.write(root, "notes.txt", "updated\n", content.lineEnding, content.charsetName, content.lastModified)
        assertEquals("updated\n", file.readText())
    }

    @Test
    fun `detects a binary file and refuses to treat it as text`() {
        val root = repo()
        val bytes = ByteArray(64) { 0 }
        File(root, "blob.bin").writeBytes(bytes)
        val content = ops.read(root, "blob.bin")
        assertTrue(content.isBinary)
    }

    @Test
    fun `truncates a file larger than the editor limit instead of loading it all`() {
        val root = repo()
        File(root, "big.txt").writeText("x".repeat(5_000))
        val content = ops.read(root, "big.txt", maxBytes = 1_000)
        assertTrue(content.truncated)
        assertTrue(content.text.length <= 1_000)
    }

    @Test
    fun `reading a directory is rejected`() {
        val root = repo()
        File(root, "adir").mkdirs()
        try {
            ops.read(root, "adir")
            fail("Reading a directory must fail")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test
    fun `reading outside the root is rejected`() {
        val root = repo()
        temp.newFile("outside.txt").writeText("secret")
        try {
            ops.read(root, "../outside.txt")
            fail("Reading outside the root must fail")
        } catch (_: IOException) {
            // Expected.
        }
    }

    // ------------------------------------------------------------- file management

    @Test
    fun `creates a file and a directory`() {
        val root = repo()
        ops.createFile(root, "new.txt")
        ops.createDirectory(root, "folder")
        assertTrue(File(root, "new.txt").isFile)
        assertTrue(File(root, "folder").isDirectory)
    }

    @Test
    fun `renames a file and keeps its contents`() {
        val root = repo()
        ops.createFile(root, "old.txt", "payload")
        val renamed = ops.rename(root, "old.txt", "new.txt")
        assertEquals("new.txt", renamed.name)
        assertFalse(File(root, "old.txt").exists())
        assertEquals("payload", File(root, "new.txt").readText())
    }

    @Test
    fun `renaming with a traversal name is rejected`() {
        val root = repo()
        ops.createFile(root, "old.txt")
        try {
            ops.rename(root, "old.txt", "../escaped.txt")
            fail("A traversal rename must be rejected")
        } catch (_: IOException) {
            // Expected.
        }
        assertTrue(File(root, "old.txt").exists())
    }

    @Test
    fun `deletes a file and a directory`() {
        val root = repo()
        ops.createFile(root, "gone.txt")
        ops.createDirectory(root, "goneDir")
        ops.delete(root, "gone.txt")
        ops.delete(root, "goneDir")
        assertFalse(File(root, "gone.txt").exists())
        assertFalse(File(root, "goneDir").exists())
    }

    @Test
    fun `moves a file to a new relative path`() {
        val root = repo()
        ops.createFile(root, "a/one.txt", "content")
        ops.move(root, "a/one.txt", "b/two.txt")
        assertFalse(File(root, "a/one.txt").exists())
        assertEquals("content", File(root, "b/two.txt").readText())
    }

    // -------------------------------------------------------------------- search

    @Test
    fun `project search finds matches with file and line number`() {
        val root = repo()
        ops.createFile(root, "src/A.kt", "val needle = 1\n")
        ops.createFile(root, "src/B.kt", "no match here\nval needle = 2\n")

        val hits = ops.search(root, "needle")
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.relativePath == "src/A.kt" && it.lineNumber == 1 })
        assertTrue(hits.any { it.relativePath == "src/B.kt" && it.lineNumber == 2 })
    }

    @Test
    fun `project search skips the git directory`() {
        val root = repo()
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("needle\n")
        ops.createFile(root, "real.txt", "needle\n")
        val hits = ops.search(root, "needle")
        assertEquals(1, hits.size)
        assertEquals("real.txt", hits.first().relativePath)
    }

    @Test
    fun `project search is case insensitive when asked`() {
        val root = repo()
        ops.createFile(root, "a.txt", "Needle\n")
        assertTrue(ops.search(root, "needle").isNotEmpty())
    }

    @Test
    fun `project search returns nothing for a blank query`() {
        val root = repo()
        ops.createFile(root, "a.txt", "anything\n")
        assertTrue(ops.search(root, "   ").isEmpty())
    }

    // ------------------------------------------------------------------ file tree

    @Test
    fun `file tree lists directories before files and hides dot directories`() {
        val root = repo()
        ops.createDirectory(root, "src")
        ops.createFile(root, "README.md")
        File(root, ".git").mkdirs()
        ops.createFile(root, ".git/HEAD", "ref: refs/heads/main")

        val reader = FileTreeReader()
        val entries = reader.list(root, "")
        val names = entries.map { it.name }

        assertTrue("directories come first", names.indexOf("src") < names.indexOf("README.md"))
        assertFalse("dot directories are hidden", names.contains(".git"))
        assertNotNull(entries.firstOrNull { it.isDirectory })
    }

    @Test
    fun `file tree descends into a subdirectory`() {
        val root = repo()
        ops.createFile(root, "src/main/App.kt")
        val reader = FileTreeReader()
        val entries = reader.list(root, "src")
        assertTrue(entries.any { it.name == "main" && it.isDirectory })
    }

    @Test
    fun `save is refused when the size changed but the timestamp did not`() {
        val root = repo()
        val file = File(root, "notes.txt")
        file.writeText("original\n")
        val content = ops.read(root, "notes.txt")
        // Same-millisecond replacement: timestamp spoofed back, size differs.
        file.writeText("original plus more\n")
        file.setLastModified(content.lastModified)
        try {
            ops.write(
                root = root,
                relativePath = "notes.txt",
                text = "my edit\n",
                lineEnding = content.lineEnding,
                charsetName = content.charsetName,
                expectedLastModified = content.lastModified,
                expectedSizeBytes = content.sizeBytes,
            )
            fail("A save over a same-timestamp replacement must be refused")
        } catch (_: IOException) {
            // Expected.
        }
        assertEquals("original plus more\n", file.readText())
    }

    @Test
    fun `save is refused when the file was deleted on disk`() {
        val root = repo()
        val file = File(root, "gone.txt")
        file.writeText("original\n")
        val content = ops.read(root, "gone.txt")
        assertTrue(file.delete())
        try {
            ops.write(
                root = root,
                relativePath = "gone.txt",
                text = "resurrected\n",
                lineEnding = content.lineEnding,
                charsetName = content.charsetName,
                expectedLastModified = content.lastModified,
                expectedSizeBytes = content.sizeBytes,
            )
            fail("A save over a deleted file must be refused")
        } catch (_: IOException) {
            // Expected: reload instead of resurrect.
        }
        assertFalse(File(root, "gone.txt").exists())
    }
}
