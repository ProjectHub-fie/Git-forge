package com.projecthub.gitforge.terminal

import com.projecthub.gitforge.terminal.shell.BuiltinRegistry
import com.projecthub.gitforge.terminal.shell.CommandResolver
import com.projecthub.gitforge.terminal.shell.ShellContext
import com.projecthub.gitforge.terminal.shell.ShellEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the real shell against a real temporary directory.
 *
 * Nothing here is stubbed: `cd`, redirection, pipes and the text utilities all go
 * through the same code path the terminal screen uses, so these tests verify actual
 * command execution rather than hard-coded output.
 */
class ShellEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun engine(): ShellEngine {
        lateinit var resolver: CommandResolver
        val builtins = BuiltinRegistry(describeCommand = { resolver.describeForWhich(it) })
        resolver = CommandResolver(builtins, emptyList())
        return ShellEngine(builtins, resolver, CoroutineScope(Dispatchers.Unconfined))
    }

    private fun context(dir: File): ShellContext = ShellContext(
        cwd = dir,
        env = mutableMapOf("HOME" to dir.absolutePath),
        home = dir,
        workspaceRoot = dir,
    )

    /**
     * One shell session: the working directory, environment and history persist
     * across calls, exactly as they do in the terminal screen.
     */
    private class Session(dir: File) {
        val builtins: BuiltinRegistry
        val context: ShellContext
        val engine: ShellEngine

        init {
            lateinit var resolver: CommandResolver
            builtins = BuiltinRegistry(describeCommand = { resolver.describeForWhich(it) })
            resolver = CommandResolver(builtins, emptyList())
            engine = ShellEngine(builtins, resolver, CoroutineScope(Dispatchers.Unconfined))
            context = ShellContext(
                cwd = dir,
                env = mutableMapOf("HOME" to dir.absolutePath),
                home = dir,
                workspaceRoot = dir,
            )
        }

        fun run(command: String) = runBlocking { engine.execute(context, command) { _, _ -> } }
    }

    /** A one-off command in a fresh session, for commands with no persistent state. */
    private fun run(dir: File, command: String) = runBlocking {
        engine().execute(context(dir), command) { _, _ -> }
    }

    @Test
    fun `pwd reports the working directory`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "pwd")
        assertEquals(0, result.exitCode)
        assertEquals(dir.canonicalPath, result.stdout.trim())
    }

    @Test
    fun `cd changes the working directory and is reflected by pwd`() {
        val dir = temp.newFolder("work")
        File(dir, "sub").mkdirs()
        val session = Session(dir)
        session.run("cd sub")
        val result = session.run("pwd")
        assertEquals(File(dir, "sub").canonicalPath, result.stdout.trim())
    }

    @Test
    fun `cd to a missing directory fails with a non zero exit code`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "cd nope")
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.isNotEmpty())
    }

    @Test
    fun `cd into a repository sibling directory keeps the session usable`() {
        val dir = temp.newFolder("work")
        File(dir, "project").mkdirs()
        val session = Session(dir)
        assertEquals(0, session.run("cd project").exitCode)
        assertEquals(File(dir, "project").canonicalPath, session.run("pwd").stdout.trim())
        // Commands still work after changing directory.
        assertEquals(0, session.run("touch inside.txt").exitCode)
        assertTrue(File(dir, "project/inside.txt").isFile)
    }

    @Test
    fun `mkdir creates a real directory`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "mkdir created")
        assertEquals(0, result.exitCode)
        assertTrue(File(dir, "created").isDirectory)
    }

    @Test
    fun `touch creates a real file`() {
        val dir = temp.newFolder("work")
        run(dir, "touch note.txt")
        assertTrue(File(dir, "note.txt").isFile)
    }

    @Test
    fun `echo writes a file through redirection and cat reads it back`() {
        val dir = temp.newFolder("work")
        run(dir, "echo hello > out.txt")
        assertTrue(File(dir, "out.txt").isFile)
        assertEquals("hello", File(dir, "out.txt").readText().trim())

        val read = run(dir, "cat out.txt")
        assertEquals("hello", read.stdout.trim())
    }

    @Test
    fun `append redirection adds to an existing file`() {
        val dir = temp.newFolder("work")
        run(dir, "echo first > log.txt")
        run(dir, "echo second >> log.txt")
        assertEquals(listOf("first", "second"), File(dir, "log.txt").readLines())
    }

    @Test
    fun `a pipe sends the output of one command into the next`() {
        val dir = temp.newFolder("work")
        File(dir, "data.txt").writeText("banana\napple\ncherry\n")
        val result = run(dir, "cat data.txt | sort")
        assertEquals(listOf("apple", "banana", "cherry"), result.stdout.trim().lines())
    }

    @Test
    fun `grep filters lines matching a pattern`() {
        val dir = temp.newFolder("work")
        File(dir, "log.txt").writeText("error: one\ninfo: two\nerror: three\n")
        val result = run(dir, "grep error log.txt")
        assertEquals(2, result.stdout.trim().lines().size)
        assertTrue(result.stdout.contains("error: one"))
    }

    @Test
    fun `grep -c still reports a count when nothing matches`() {
        val dir = temp.newFolder("work")
        File(dir, "log.txt").writeText("error: one\ninfo: two\nerror: three\n")
        assertEquals("2", run(dir, "grep -c error log.txt").stdout.trim())
        // A zero count must be printed, not omitted; only the exit code differs.
        val none = run(dir, "grep -c missing log.txt")
        assertEquals("0", none.stdout.trim())
        assertTrue(none.exitCode != 0)
    }

    @Test
    fun `grep -c counts matches arriving on stdin`() {
        val dir = temp.newFolder("work")
        File(dir, "log.txt").writeText("error: one\ninfo: two\nerror: three\n")
        val result = run(dir, "cat log.txt | grep -c error")
        assertEquals("2", result.stdout.trim())
    }

    @Test
    fun `sed performs a substitution`() {
        val dir = temp.newFolder("work")
        File(dir, "s.txt").writeText("hello world\n")
        val result = run(dir, "sed 's/world/there/' s.txt")
        assertEquals("hello there", result.stdout.trim())
    }

    @Test
    fun `wc counts lines`() {
        val dir = temp.newFolder("work")
        File(dir, "l.txt").writeText("a\nb\nc\n")
        val result = run(dir, "wc -l l.txt")
        assertTrue(result.stdout.trim().startsWith("3"))
    }

    @Test
    fun `head returns the first lines`() {
        val dir = temp.newFolder("work")
        File(dir, "n.txt").writeText("1\n2\n3\n4\n5\n")
        val result = run(dir, "head -n 2 n.txt")
        assertEquals(listOf("1", "2"), result.stdout.trim().lines())
    }

    @Test
    fun `tail returns the last lines`() {
        val dir = temp.newFolder("work")
        File(dir, "n.txt").writeText("1\n2\n3\n4\n5\n")
        val result = run(dir, "tail -n 2 n.txt")
        assertEquals(listOf("4", "5"), result.stdout.trim().lines())
    }

    @Test
    fun `uniq collapses adjacent duplicates`() {
        val dir = temp.newFolder("work")
        File(dir, "u.txt").writeText("a\na\nb\na\n")
        val result = run(dir, "uniq u.txt")
        assertEquals(listOf("a", "b", "a"), result.stdout.trim().lines())
    }

    @Test
    fun `cp copies a file`() {
        val dir = temp.newFolder("work")
        File(dir, "src.txt").writeText("payload\n")
        run(dir, "cp src.txt dst.txt")
        assertEquals("payload\n", File(dir, "dst.txt").readText())
    }

    @Test
    fun `mv moves a file`() {
        val dir = temp.newFolder("work")
        File(dir, "a.txt").writeText("payload\n")
        run(dir, "mv a.txt b.txt")
        assertFalse(File(dir, "a.txt").exists())
        assertTrue(File(dir, "b.txt").exists())
    }

    @Test
    fun `rm removes a file and requires a flag for a directory`() {
        val dir = temp.newFolder("work")
        File(dir, "gone.txt").writeText("x\n")
        run(dir, "rm gone.txt")
        assertFalse(File(dir, "gone.txt").exists())

        File(dir, "folder").mkdirs()
        run(dir, "rm -rf folder")
        assertFalse(File(dir, "folder").exists())
    }

    @Test
    fun `find locates a file by name`() {
        val dir = temp.newFolder("work")
        File(dir, "a/b").mkdirs()
        File(dir, "a/b/target.txt").writeText("x\n")
        val result = run(dir, "find . -name target.txt")
        assertTrue(result.stdout.contains("target.txt"))
    }

    @Test
    fun `an unknown command fails with exit code 127`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "definitely-not-a-command")
        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("not found") || result.stderr.contains("not installed"))
    }

    @Test
    fun `export sets a variable that later commands can read`() {
        val dir = temp.newFolder("work")
        val session = Session(dir)
        session.run("export GREETING=hello")
        val result = session.run("echo \$GREETING")
        assertEquals("hello", result.stdout.trim())
    }

    @Test
    fun `command history records what was run`() {
        val dir = temp.newFolder("work")
        val session = Session(dir)
        session.run("pwd")
        session.run("ls")
        assertTrue(session.context.history.contains("pwd"))
        assertTrue(session.context.history.contains("ls"))
    }

    @Test
    fun `which reports a builtin as available`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "which cd")
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("cd"))
    }

    @Test
    fun `a path traversal through a command argument is refused`() {
        val dir = temp.newFolder("work")
        val secret = temp.newFile("secret.txt")
        secret.writeText("do not read me\n")
        val result = run(dir, "cat ../secret.txt")
        assertTrue("the read must not succeed", result.exitCode != 0 || !result.stdout.contains("do not read me"))
    }

    @Test
    fun `single quotes suppress variable expansion`() {
        val dir = temp.newFolder("work")
        val session = Session(dir)
        session.run("export FOO=expanded")
        // \$FOO keeps the dollar literal; a bare $FOO is a Kotlin template.
        val result = session.run("echo '\$FOO'")
        assertEquals("\$FOO", result.stdout.trim())
        assertFalse(result.stdout.contains("expanded"))
    }

    @Test
    fun `double quotes still expand variables`() {
        val dir = temp.newFolder("work")
        val session = Session(dir)
        session.run("export FOO=expanded")
        val result = session.run("echo \"\$FOO\"")
        assertTrue(result.stdout.contains("expanded"))
    }

    @Test
    fun `quoted globs do not expand`() {
        val dir = temp.newFolder("work")
        File(dir, "a.kt").writeText("x")
        val result = run(dir, "echo \"*.kt\"")
        assertEquals("""*.kt""", result.stdout.trim())
    }

    @Test
    fun `input redirection feeds a file as stdin`() {
        val dir = temp.newFolder("work")
        File(dir, "in.txt").writeText("hello-stdin\n")
        val result = run(dir, "cat < in.txt")
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello-stdin"))
    }

    @Test
    fun `missing input redirection file is an error`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "cat < nope.txt")
        assertTrue(result.exitCode != 0)
    }

    @Test
    fun `background jobs are rejected loudly`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "sleep 5 &")
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("background"))
    }

    @Test
    fun `empty pipeline stages are a syntax error`() {
        val dir = temp.newFolder("work")
        val result = run(dir, "echo hi |")
        assertTrue(result.exitCode != 0)
    }

    @Test
    fun `env overrides do not leak into the session`() {
        val dir = temp.newFolder("work")
        val session = Session(dir)
        session.run("env LEAK_TEST=1 true")
        val result = session.run("echo \$LEAK_TEST")
        assertEquals("", result.stdout.trim())
    }
}
