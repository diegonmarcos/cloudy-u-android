package com.diegonmarcos.cloudlib.gitcore

import java.io.IOException

/**
 * #1408 typed git errors. Every failure the in-process engine reports carries one [GitErrorKind], on the
 * wire as the answer's "kind" and in Kotlin as [GitException.kind], so the UI branches on a kind
 * (offer the conflict solver, the sign-in, the All files grant) instead of matching sentences.
 */
enum class GitErrorKind {
    /** The remote refused the credentials (401/403, auth fail, no key). */
    AUTH,
    /** The remote could not be reached (DNS, connect, timeout, TLS). */
    NETWORK,
    /** A merge, rebase or stash pop stopped on conflicting files. */
    CONFLICT,
    /** The remote is ahead: a plain push was rejected as non-fast-forward. */
    REJECTED,
    /** The directory is not a git repository. */
    NOT_A_REPO,
    /** The storage root denied access (no All files access, read-only volume, permission denied). */
    STORAGE,
    /** Another git process holds the index or a ref lock. */
    LOCKED,
    /** Anything else. */
    UNKNOWN;

    companion object {
        /** The wire name back to a kind; an unknown or missing name is [UNKNOWN]. */
        fun of(name: String?): GitErrorKind = entries.firstOrNull { it.name == name } ?: UNKNOWN

        /** The kind of a failure sentence (an exception message or a refused op's summary and details). */
        fun classify(text: String?): GitErrorKind {
            val t = text.orEmpty().lowercase()
            return when {
                t.isBlank() -> UNKNOWN
                "all files access" in t || "permission denied" in t || "read-only file system" in t ||
                    "eacces" in t || "erofs" in t -> STORAGE
                "index.lock" in t || ".lock" in t && "exists" in t || "lock_failure" in t || "locked" in t -> LOCKED
                "not authorized" in t || "authentication" in t || "auth fail" in t || "401" in t || "403" in t ||
                    "credentialsprovider" in t || "no credentials" in t -> AUTH
                "conflict" in t -> CONFLICT
                "non_fast_forward" in t || "rejected_nonfastforward" in t || "non-fast-forward" in t || "fetch first" in t -> REJECTED
                "repository not found" in t || "not a git repository" in t || "repositorynotfound" in t ||
                    "no head" in t -> NOT_A_REPO
                "unknownhost" in t || "unknown host" in t || "connection refused" in t || "timed out" in t ||
                    "timeout" in t || "network is unreachable" in t || "connect" in t && "fail" in t ||
                    "ssl" in t || "handshake" in t -> NETWORK
                else -> UNKNOWN
            }
        }

        /** The kind of a thrown failure: its class name and its message, whichever names one. */
        fun classify(t: Throwable): GitErrorKind {
            var cur: Throwable? = t
            while (cur != null) {
                val k = classify(cur.javaClass.simpleName + ": " + cur.message)
                if (k != UNKNOWN) return k
                cur = cur.cause?.takeIf { it !== cur }
            }
            return UNKNOWN
        }
    }
}

/** A refused git call with its [kind]. An [IOException], so every existing catch still holds. */
class GitException(val kind: GitErrorKind, message: String) : IOException(message)
