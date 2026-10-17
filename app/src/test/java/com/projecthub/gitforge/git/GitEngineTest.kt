package com.projecthub.gitforge.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Integration tests against real Git repositories created on a real filesystem.
 *
 * These exercise the same JGit engine the app uses, so a passing test here means the
 * Git screen and the terminal are operating on a working engine rather than a stub.
 */
class GitEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = GitEngine()

    private fun newRepo(): File = temp.newFolder("repo").also { dir ->
        runBlocking { engine.init(dir) }
    }

    private fun commit(repo: File, message: String = "test commit") = runBlocking {
        engine.commit(repo, message, "Test User", "test@example.com")
    }

    // ---------------------------------------------------------------- status/diff

    @Test
    fun `a new repository has an empty working tree`() = runBlocking {
        val repo = newRepo()
        val status = engine.status(repo)
        assertTrue(status.files.isEmpty())
        assertFalse(status.hasUncommittedChanges)
    }

    @Test
    fun `an untracked file is reported as untracked`() = runBlocking {
        val repo = newRepo()
        File(repo, "new.txt").writeText("hello\n")
        val status = engine.status(repo)
        assertTrue(status.untracked.any { it.relativePath == "new.txt" })
        assertTrue(status.hasUncommittedChanges)
    }

    @Test
    fun `a modified tracked file is reported as modified`() = runBlocking {
        val repo = newRepo()
        File(repo, "tracked.txt").writeText("first\n")
        engine.stage(repo, listOf("tracked.txt"))
        commit(repo)
        File(repo, "tracked.txt").writeText("second\n")
        val status = engine.status(repo)
        assertTrue(status.unstaged.any { it.relativePath == "tracked.txt" })
    }

    @Test
    fun `staging moves a file from unstaged to staged`() = runBlocking {
        val repo = newRepo()
        File(repo, "a.txt").writeText("content\n")
        engine.stage(repo, listOf("a.txt"))
        val status = engine.status(repo)
        assertTrue(status.staged.any { it.relativePath == "a.txt" })
        assertTrue(status.unstaged.none { it.relativePath == "a.txt" })
    }

    @Test
    fun `unstaging returns a file to the unstaged set`() = runBlocking {
        val repo = newRepo()
        File(repo, "a.txt").writeText("content\n")
        engine.stage(repo, listOf("a.txt"))
        engine.unstage(repo, listOf("a.txt"))
        val status = engine.status(repo)
        assertTrue(status.staged.none { it.relativePath == "a.txt" })
        assertTrue(status.untracked.any { it.relativePath == "a.txt" })
    }

    @Test
    fun `diff shows the added line for a modified file`() = runBlocking {
        val repo = newRepo()
        File(repo, "d.txt").writeText("alpha\n")
        engine.stage(repo, listOf("d.txt"))
        commit(repo)
        File(repo, "d.txt").writeText("alpha\nbeta\n")
        val diff = engine.diff(repo, "d.txt", staged = false).firstOrNull()
        assertNotNull(diff)
        assertTrue("expected an added line", diff!!.lines.any { it.kind == DiffLine.Kind.ADDED && it.text.contains("beta") })
        assertTrue(diff.additions >= 1)
    }

    @Test
    fun `diff for a staged change is available before committing`() = runBlocking {
        val repo = newRepo()
        File(repo, "s.txt").writeText("one\n")
        engine.stage(repo, listOf("s.txt"))
        val diff = engine.diff(repo, "s.txt", staged = true).firstOrNull()
        assertNotNull(diff)
        assertTrue(diff!!.lines.any { it.kind == DiffLine.Kind.ADDED })
    }

    // -------------------------------------------------------------------- commits

    @Test
    fun `committing creates a commit and cleans the working tree`() = runBlocking {
        val repo = newRepo()
        File(repo, "c.txt").writeText("data\n")
        engine.stage(repo, listOf("c.txt"))
        val commit = commit(repo, "initial commit")
        assertEquals("initial commit", commit.message.trim())
        assertEquals(40, commit.sha.length)

        val status = engine.status(repo)
        assertFalse(status.hasUncommittedChanges)
    }

    @Test
    fun `commit records the configured author`() = runBlocking {
        val repo = newRepo()
        File(repo, "author.txt").writeText("x\n")
        engine.stage(repo, listOf("author.txt"))
        val commit = commit(repo)
        assertEquals("Test User", commit.authorName)
        assertEquals("test@example.com", commit.authorEmail)
    }

    @Test
    fun `committing with nothing staged fails instead of creating an empty commit`() = runBlocking {
        val repo = newRepo()
        try {
            engine.commit(repo, "nothing", "Test User", "test@example.com")
            fail("Committing with nothing staged must fail")
        } catch (_: GitOperationException) {
            // Expected.
        }
        assertTrue(engine.log(repo).isEmpty())
    }

    @Test
    fun `history returns commits newest first and supports paging`() = runBlocking {
        val repo = newRepo()
        repeat(3) { index ->
            File(repo, "file$index.txt").writeText("content $index\n")
            engine.stage(repo, listOf("file$index.txt"))
            commit(repo, "commit $index")
        }
        val all = engine.log(repo, limit = 10)
        assertEquals(3, all.size)
        assertEquals("commit 2", all.first().message.trim())

        val page = engine.log(repo, limit = 1, skip = 1)
        assertEquals(1, page.size)
        assertEquals("commit 1", page.first().message.trim())
    }

    // ------------------------------------------------------------------- branches

    @Test
    fun `creating a branch and switching to it works`() = runBlocking {
        val repo = newRepo()
        File(repo, "base.txt").writeText("base\n")
        engine.stage(repo, listOf("base.txt"))
        commit(repo)

        engine.createBranch(repo, "feature", checkout = true)
        assertEquals("feature", engine.status(repo).branch)

        val branches = engine.branches(repo)
        assertTrue(branches.any { it.name == "feature" && it.isCurrent })
        assertTrue(branches.any { it.name == "main" || it.name == "master" })
    }

    @Test
    fun `creating a branch without checkout keeps the current branch`() = runBlocking {
        val repo = newRepo()
        File(repo, "base.txt").writeText("base\n")
        engine.stage(repo, listOf("base.txt"))
        commit(repo)
        val before = engine.status(repo).branch
        engine.createBranch(repo, "sidecar", checkout = false)
        assertEquals(before, engine.status(repo).branch)
        assertTrue(engine.branches(repo).any { it.name == "sidecar" })
    }

    @Test
    fun `switching branches restores that branch's file content`() = runBlocking {
        val repo = newRepo()
        File(repo, "shared.txt").writeText("main version\n")
        engine.stage(repo, listOf("shared.txt"))
        commit(repo)

        engine.createBranch(repo, "other", checkout = true)
        File(repo, "shared.txt").writeText("other version\n")
        engine.stage(repo, listOf("shared.txt"))
        commit(repo, "change on other")

        engine.checkout(repo, "main")
        assertEquals("main version\n", File(repo, "shared.txt").readText())
    }

    @Test
    fun `renaming a branch is reflected in the branch list`() = runBlocking {
        val repo = newRepo()
        File(repo, "f.txt").writeText("x\n")
        engine.stage(repo, listOf("f.txt"))
        commit(repo)
        engine.createBranch(repo, "old-name", checkout = true)
        engine.renameBranch(repo, "old-name", "new-name")
        val branches = engine.branches(repo)
        assertTrue(branches.any { it.name == "new-name" })
        assertFalse(branches.any { it.name == "old-name" })
    }

    @Test
    fun `deleting a branch removes it`() = runBlocking {
        val repo = newRepo()
        File(repo, "f.txt").writeText("x\n")
        engine.stage(repo, listOf("f.txt"))
        commit(repo)
        engine.createBranch(repo, "doomed", checkout = false)
        engine.deleteBranch(repo, "doomed")
        assertFalse(engine.branches(repo).any { it.name == "doomed" })
    }

    @Test
    fun `deleting the current branch is refused`() = runBlocking {
        val repo = newRepo()
        File(repo, "f.txt").writeText("x\n")
        engine.stage(repo, listOf("f.txt"))
        commit(repo)
        val current = engine.status(repo).branch
        try {
            engine.deleteBranch(repo, current)
            fail("Deleting the checked out branch must fail")
        } catch (_: GitOperationException) {
            // Expected.
        }
    }

    // ---------------------------------------------------------------------- merge

    @Test
    fun `fast forward merge brings the branch content in`() = runBlocking {
        val repo = newRepo()
        File(repo, "base.txt").writeText("base\n")
        engine.stage(repo, listOf("base.txt"))
        commit(repo)
        val mainBranch = engine.status(repo).branch

        engine.createBranch(repo, "feature", checkout = true)
        File(repo, "feature.txt").writeText("feature\n")
        engine.stage(repo, listOf("feature.txt"))
        commit(repo, "add feature")

        engine.checkout(repo, mainBranch)
        val outcome = engine.merge(repo, "feature")
        assertEquals(MergeStatus.FAST_FORWARD, outcome.status)
        assertTrue(File(repo, "feature.txt").exists())
    }

    @Test
    fun `merge reports conflicts and lists the conflicting paths`() = runBlocking {
        val repo = newRepo()
        File(repo, "conflict.txt").writeText("base\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo)
        val mainBranch = engine.status(repo).branch

        engine.createBranch(repo, "other", checkout = true)
        File(repo, "conflict.txt").writeText("other change\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo, "other change")

        engine.checkout(repo, mainBranch)
        File(repo, "conflict.txt").writeText("main change\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo, "main change")

        val outcome = engine.merge(repo, "other")
        assertEquals(MergeStatus.CONFLICTING, outcome.status)
        assertTrue(outcome.conflictingPaths.any { it.contains("conflict.txt") })

        // The conflict markers must be present so the user can resolve by hand.
        val text = File(repo, "conflict.txt").readText()
        assertTrue("conflict markers should be written", text.contains("<<<<<<<") || text.contains(">>>>>>>"))
    }

    // ---------------------------------------------------------------------- revert

    @Test
    fun `revert creates a new commit that undoes the change`() = runBlocking {
        val repo = newRepo()
        File(repo, "r.txt").writeText("version one\n")
        engine.stage(repo, listOf("r.txt"))
        commit(repo, "first")

        File(repo, "r.txt").writeText("version two\n")
        engine.stage(repo, listOf("r.txt"))
        val second = commit(repo, "second")
        assertEquals("version two\n", File(repo, "r.txt").readText())

        engine.revert(repo, second.sha, "Test User", "test@example.com")
        assertEquals("version one\n", File(repo, "r.txt").readText())
        // History is preserved: the revert is an extra commit, not a rewrite.
        assertTrue(engine.log(repo).size >= 3)
    }

    // --------------------------------------------------------------------- remotes

    @Test
    fun `a new repository has no remotes`() = runBlocking {
        val repo = newRepo()
        assertTrue(engine.remotes(repo).isEmpty())
    }

    @Test
    fun `setting a remote stores it without any credential in the url`() = runBlocking {
        val repo = newRepo()
        engine.setRemote(repo, "origin", "https://github.com/example/project.git")
        val remotes = engine.remotes(repo)
        assertEquals(1, remotes.size)
        assertEquals("origin", remotes.first().name)
        assertEquals("https://github.com/example/project.git", remotes.first().fetchUrl)
    }

    // ------------------------------------------------------------------- persistence

    @Test
    fun `repository state survives closing and reopening the engine`() = runBlocking {
        val repo = newRepo()
        File(repo, "persist.txt").writeText("kept\n")
        engine.stage(repo, listOf("persist.txt"))
        commit(repo, "persisted")

        // A brand new engine instance, as if the app had restarted.
        val reopened = GitEngine()
        val log = reopened.log(repo)
        assertEquals(1, log.size)
        assertEquals("persisted", log.first().message.trim())
        assertEquals("kept\n", File(repo, "persist.txt").readText())
    }

    @Test
    fun `status of a non repository is reported as an error`() = runBlocking {
        val plain = temp.newFolder("plain")
        try {
            engine.status(plain)
            fail("Status on a non-repository must fail")
        } catch (_: GitOperationException) {
            // Expected.
        }
    }

    @Test
    fun `setting a remote strips credentials from the url`() = runBlocking {
        val repo = newRepo()
        engine.setRemote(repo, "origin", "https://x-access-token:secret123@github.com/example/project.git")
        val remotes = engine.remotes(repo)
        assertEquals(1, remotes.size)
        assertFalse(remotes.first().fetchUrl.contains("secret123"))
        assertTrue(remotes.first().fetchUrl.startsWith("https://github.com/"))
    }

    @Test
    fun `setting a remote rejects non-https urls`() = runBlocking {
        val repo = newRepo()
        try {
            engine.setRemote(repo, "origin", "git@github.com:example/project.git")
            fail("Non-HTTPS remote URLs must be rejected")
        } catch (_: GitOperationException) {
            // Expected: only https:// remotes are stored.
        }
    }

    @Test
    fun `merge refuses a dirty tree instead of failing midway`() = runBlocking {
        val repo = newRepo()
        File(repo, "base.txt").writeText("base\n")
        engine.stage(repo, listOf("base.txt"))
        commit(repo)
        engine.createBranch(repo, "feature", checkout = false)
        File(repo, "dirty.txt").writeText("uncommitted\n")
        try {
            engine.merge(repo, "feature")
            fail("Merge over uncommitted changes must be refused")
        } catch (_: GitOperationException) {
            // Expected: commit or discard first.
        }
    }

    @Test
    fun `abort merge restores the tree after a conflict`() = runBlocking {
        val repo = newRepo()
        File(repo, "conflict.txt").writeText("base\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo)
        val mainBranch = engine.status(repo).branch

        engine.createBranch(repo, "other", checkout = true)
        File(repo, "conflict.txt").writeText("other change\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo, "other change")

        engine.checkout(repo, mainBranch)
        File(repo, "conflict.txt").writeText("main change\n")
        engine.stage(repo, listOf("conflict.txt"))
        commit(repo, "main change")

        val outcome = engine.merge(repo, "other")
        assertEquals(MergeStatus.CONFLICTING, outcome.status)
        engine.abortMerge(repo)
        assertFalse(File(repo, ".git/MERGE_HEAD").exists())
        assertTrue(engine.status(repo).conflicting.isEmpty())
    }
}
