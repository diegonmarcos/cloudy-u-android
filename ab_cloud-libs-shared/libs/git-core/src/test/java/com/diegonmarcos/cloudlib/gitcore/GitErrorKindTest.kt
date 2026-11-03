package com.diegonmarcos.cloudlib.gitcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** #1408 every failure sentence the engine meets maps to exactly one kind, and the precedence holds. */
class GitErrorKindTest {
    private fun k(s: String?) = GitErrorKind.classify(s)

    @Test fun blankAndUnknownAreUnknown() {
        assertEquals(GitErrorKind.UNKNOWN, k(null))
        assertEquals(GitErrorKind.UNKNOWN, k(""))
        assertEquals(GitErrorKind.UNKNOWN, k("   "))
        assertEquals(GitErrorKind.UNKNOWN, k("something odd happened"))
    }

    @Test fun storage() {
        assertEquals(GitErrorKind.STORAGE, k("Cloud Drive has no All files access, so git cannot read"))
        assertEquals(GitErrorKind.STORAGE, k("/storage/emulated/0/x: Permission denied"))
        assertEquals(GitErrorKind.STORAGE, k("Read-only file system"))
        assertEquals(GitErrorKind.STORAGE, k("open failed: EACCES"))
        assertEquals(GitErrorKind.STORAGE, k("open failed: EROFS"))
    }

    @Test fun locked() {
        assertEquals(GitErrorKind.LOCKED, k("Cannot lock /r/.git/index.lock"))
        assertEquals(GitErrorKind.LOCKED, k("/r/.git/refs/heads/main.lock already exists"))
        assertEquals(GitErrorKind.LOCKED, k("ref update LOCK_FAILURE"))
        assertEquals(GitErrorKind.LOCKED, k("repository is locked"))
        assertEquals(GitErrorKind.UNKNOWN, k("a.lock"))   // a .lock name alone, no "exists"
    }

    @Test fun auth() {
        assertEquals(GitErrorKind.AUTH, k("https://x: not authorized"))
        assertEquals(GitErrorKind.AUTH, k("Authentication is required but no CredentialsProvider has been registered"))
        assertEquals(GitErrorKind.AUTH, k("Auth fail"))
        assertEquals(GitErrorKind.AUTH, k("HTTP 401"))
        assertEquals(GitErrorKind.AUTH, k("HTTP 403"))
        assertEquals(GitErrorKind.AUTH, k("no credentials"))
        assertEquals(GitErrorKind.AUTH, k("CredentialsProvider missing"))
    }

    @Test fun conflictAndRejected() {
        assertEquals(GitErrorKind.CONFLICT, k("pull conflicts: 2 file(s)"))
        assertEquals(GitErrorKind.REJECTED, k("push rejected: main REJECTED_NONFASTFORWARD"))
        assertEquals(GitErrorKind.REJECTED, k("main NON_FAST_FORWARD"))
        assertEquals(GitErrorKind.REJECTED, k("non-fast-forward"))
        assertEquals(GitErrorKind.REJECTED, k("rejected (fetch first)"))
    }

    @Test fun notARepo() {
        assertEquals(GitErrorKind.NOT_A_REPO, k("repository not found: /x"))
        assertEquals(GitErrorKind.NOT_A_REPO, k("fatal: not a git repository"))
        assertEquals(GitErrorKind.NOT_A_REPO, k("RepositoryNotFoundException"))
        assertEquals(GitErrorKind.NOT_A_REPO, k("No HEAD exists"))
    }

    @Test fun network() {
        assertEquals(GitErrorKind.NETWORK, k("UnknownHostException: github.com"))
        assertEquals(GitErrorKind.NETWORK, k("unknown host"))
        assertEquals(GitErrorKind.NETWORK, k("Connection refused"))
        assertEquals(GitErrorKind.NETWORK, k("connect timed out"))
        assertEquals(GitErrorKind.NETWORK, k("read timeout"))
        assertEquals(GitErrorKind.NETWORK, k("Network is unreachable"))
        assertEquals(GitErrorKind.NETWORK, k("connect failed"))
        assertEquals(GitErrorKind.NETWORK, k("SSL peer shut down"))
        assertEquals(GitErrorKind.NETWORK, k("handshake"))
        assertEquals(GitErrorKind.UNKNOWN, k("connect"))  // connect without fail is not enough
    }

    @Test fun precedenceStorageBeforeAuthBeforeConflict() {
        assertEquals(GitErrorKind.STORAGE, k("permission denied while resolving conflict, 403"))
        assertEquals(GitErrorKind.LOCKED, k("index.lock: 401"))
        assertEquals(GitErrorKind.AUTH, k("401 during conflict"))
        assertEquals(GitErrorKind.CONFLICT, k("conflict, then non-fast-forward"))
        assertEquals(GitErrorKind.REJECTED, k("non-fast-forward: repository not found"))
        assertEquals(GitErrorKind.NOT_A_REPO, k("no head, connection refused"))
    }

    @Test fun throwableUsesClassNameAndWalksCauses() {
        class UnknownHostException(m: String) : Exception(m)
        assertEquals(GitErrorKind.NETWORK, GitErrorKind.classify(UnknownHostException("github.com")))
        assertEquals(GitErrorKind.AUTH, GitErrorKind.classify(RuntimeException("wrap", IOException("not authorized"))))
        assertEquals(GitErrorKind.UNKNOWN, GitErrorKind.classify(RuntimeException("x", IllegalStateException("y"))))
        val self = object : RuntimeException("z") { override val cause: Throwable get() = this }
        assertEquals(GitErrorKind.UNKNOWN, GitErrorKind.classify(self))
    }

    @Test fun ofReadsTheWireName() {
        for (kind in GitErrorKind.entries) assertEquals(kind, GitErrorKind.of(kind.name))
        assertEquals(GitErrorKind.UNKNOWN, GitErrorKind.of(null))
        assertEquals(GitErrorKind.UNKNOWN, GitErrorKind.of("nope"))
    }

    @Test fun exceptionIsAnIOExceptionWithItsKind() {
        val e: IOException = GitException(GitErrorKind.CONFLICT, "pull conflicts")
        assertTrue(e is GitException)
        assertEquals(GitErrorKind.CONFLICT, (e as GitException).kind)
        assertEquals("pull conflicts", e.message)
    }
}
