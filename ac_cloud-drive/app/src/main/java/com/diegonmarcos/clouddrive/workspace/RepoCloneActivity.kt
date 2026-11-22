package com.diegonmarcos.clouddrive.workspace

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace

/**
 * #1855 P1 the clone hand-off of libs:repo-workspace: an app asks "clone this repository with Drive" and lands on Sync > Git with
 * the clone dialog prefilled (MainActivity.EXTRA_CLONE_NAME). Exported but guarded by the CONSTELLATION_DATA signature permission
 * in the manifest, so only fleet apps reach it; it takes ONE extra, a plain repository name (RepoWorkspace.validName), forwards
 * it to the shell and finishes, so it never shows a screen of its own.
 */
class RepoCloneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent?.getStringExtra(RepoWorkspace.EXTRA_NAME)?.trim()
        if (RepoWorkspaceCatalog.refusal(name).isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CLONE)
                .putExtra(MainActivity.EXTRA_CLONE_NAME, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}
