package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.cloudlib.gitrepos.FleetSession
import com.diegonmarcos.superapp.fleetendpoints.SignInContract
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #1693 WHERE DRIVE'S CLOUD (AUTHELIA) RUNG GETS ITS SESSION: from Cloud Account, over the fleet IPC (`session` method,
 * signature-gated there), as a short-lived lease held in [FleetSession] (process memory). Drive runs no Authelia login
 * and no cookie capture of its own; with Cloud Account missing, signed out or the lease expired the rung simply has no
 * session and says so (the sign-in button opens Cloud Account). The cookie is never logged here.
 */
object DriveSessionSource {
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        FleetSession.source = {
            val s = AccountClient.session(app, SignInContract.PROVIDER_AUTHELIA)
            if (s.usable(System.currentTimeMillis())) FleetSession.Lease(s.cookie, s.expiresAtMs) else null
        }
    }
}
