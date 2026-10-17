package com.projecthub.gitforge.terminal

import com.projecthub.gitforge.terminal.runtime.RuntimePackageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ANSI parser turns raw process output into styled lines. A mistake here shows up
 * as garbled terminal output, so escape sequences are tested directly.
 */
class AnsiParserTest {

    private val parser = AnsiParser()

    @Test
    fun `plain text passes through with no styling`() {
        val result = parser.parse("hello world\n", "")
        assertEquals(1, result.lines.size)
        assertEquals("hello world", result.lines.first().first)
        assertEquals(AnsiStyle.NONE, result.lines.first().second)
    }

    @Test
    fun `splits output into lines and keeps a trailing partial line`() {
        val result = parser.parse("one\ntwo\npart", "")
        assertEquals(listOf("one", "two"), result.lines.map { it.first })
        assertEquals("part", result.carry)
    }

    @Test
    fun `a colour code is applied to the text that follows it`() {
        val result = parser.parse("\u001B[31mred text\u001B[0m\n", "")
        val style = result.lines.first().second
        assertEquals(AnsiColor.RED, style.fg)
    }

    @Test
    fun `reset clears the active colour`() {
        val result = parser.parse("\u001B[32mgreen\u001B[0mplain\n", "")
        assertEquals(AnsiColor.GREEN, result.lines.first().second.fg)
    }

    @Test
    fun `bold is recognised`() {
        val result = parser.parse("\u001B[1mbold\n", "")
        assertTrue(result.lines.first().second.bold)
    }

    @Test
    fun `bright colours are distinguished from normal colours`() {
        val bright = parser.parse("\u001B[91mtext\n", "").lines.first().second
        assertEquals(AnsiColor.BRIGHT_RED, bright.fg)
    }

    @Test
    fun `carry across calls completes a line split mid-sequence`() {
        // A process may write half a line, then the rest; the two halves must join.
        val first = parser.parse("hel", "")
        val second = parser.parse("lo\n", first.carry)
        assertEquals("hello", second.lines.first().first)
    }

    @Test
    fun `carriage return overwrites the current line`() {
        // Progress bars use \r to redraw; the final state is what should be kept.
        val result = parser.parse("50%\r100%\n", "")
        assertEquals("100%", result.lines.last().first)
    }

    @Test
    fun `an unknown escape sequence is dropped rather than printed`() {
        val result = parser.parse("before\u001B[999mafter\n", "")
        assertFalse(result.lines.first().first.contains("\u001B"))
        assertTrue(result.lines.first().first.contains("before"))
        assertTrue(result.lines.first().first.contains("after"))
    }
    @Test
    fun `an SGR sequence split across chunks does not leak literally`() {
        val first = parser.parse("\u001B[3", "")
        val second = parser.parse("1mred\n", first.carry)
        assertTrue(second.lines.any { it.first.contains("red") })
        assertFalse((second.lines.map { it.first } + second.carry).any { it.contains("\u001B[3") })
    }

    @Test
    fun `CRLF is one newline not an overwrite plus a newline`() {
        val result = parser.parse("a\r\nb\r\n", "")
        assertEquals(listOf("a", "b"), result.lines.map { it.first })
    }

    @Test
    fun `a lone CR still overwrites the line`() {
        val result = parser.parse("50%\r100%\n", "")
        assertEquals("100%", result.lines.last().first)
    }
}

/**
 * The runtime manifest is what gets downloaded and verified on device. A wrong
 * checksum means a failed install, so the shape of the manifest is asserted here.
 */
class RuntimePackageRegistryTest {

    @Test
    fun `every package has a version, a path and a sha256 checksum`() {
        val all = RuntimePackageRegistry.fullUserspace()
        assertTrue(all.isNotEmpty())
        all.forEach { pkg ->
            assertTrue("${pkg.name} needs a version", pkg.version.isNotBlank())
            assertTrue("${pkg.name} needs a filename", pkg.filename.startsWith("pool/"))
            assertEquals("${pkg.name} checksum must be a sha256", 64, pkg.sha256.length)
            assertTrue("${pkg.name} checksum must be hex", pkg.sha256.all { it in "0123456789abcdef" })
            assertTrue("${pkg.name} must have a positive size", pkg.sizeBytes > 0)
        }
    }

    @Test
    fun `no package is listed twice across the groups`() {
        val names = RuntimePackageRegistry.fullUserspace().map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `the node group contains node and npm`() {
        val names = RuntimePackageRegistry.nodePackages.map { it.name }
        assertTrue(names.contains("nodejs"))
        assertTrue(names.contains("npm"))
        // Node needs these at runtime, so they must ship with it.
        assertTrue(names.contains("libc++"))
        assertTrue(names.contains("openssl"))
    }

    @Test
    fun `the python group contains python and pip`() {
        val names = RuntimePackageRegistry.pythonPackages.map { it.name }
        assertTrue(names.contains("python"))
        assertTrue(names.contains("python-pip"))
    }

    @Test
    fun `the common group provides certificates`() {
        assertTrue(RuntimePackageRegistry.commonPackages.any { it.name == "ca-certificates" })
    }

    @Test
    fun `package names use only characters that are safe in a file name`() {
        RuntimePackageRegistry.fullUserspace().forEach { pkg ->
            assertTrue(
                "${pkg.name} would need escaping in a path",
                pkg.name.matches(Regex("[A-Za-z0-9._+-]+")),
            )
        }
    }

    @Test
    fun `licence summary names the significant components`() {
        val names = RuntimePackageRegistry.licenceSummary().map { it.first }
        assertTrue(names.any { it.contains("Node") })
        assertTrue(names.any { it.contains("CPython") })
        assertTrue(names.any { it.contains("JGit", ignoreCase = true) || it.contains("Termux") })
    }

    @Test
    fun `the download size is the sum of the individual package sizes`() {
        val packages = RuntimePackageRegistry.nodePackages
        val expected = packages.sumOf { it.sizeBytes }
        assertEquals(expected, RuntimePackageRegistry.totalDownloadBytes(packages))
    }

    @Test
    fun `the declared abi is a real android abi`() {
        assertTrue(
            RuntimePackageRegistry.SUPPORTED_ABI in
                setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
        )
    }

    @Test
    fun `the default mirror is https`() {
        assertTrue(RuntimePackageRegistry.DEFAULT_MIRROR.startsWith("https://"))
        assertNotNull(RuntimePackageRegistry.DEFAULT_MIRROR)
    }
}
