package com.diegonmarcos.superapp.profile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract

/**
 * #1703 THE BROWSER TRIP OF CLOUD MAIL'S OAUTH CODE GRANT STARTS HERE, in Cloud Account. Cloud Mail opens this activity with the handle
 * [MailOAuthCodeSessions.start] gave it ([MailOAuthContract.EXTRA_HANDLE]); it opens the authorization page of the request still in flight and
 * finishes, so the browser is on top and the redirect returns on Cloud Mail's own scheme. Signature-permission gated in the manifest. A missing, expired
 * or foreign handle is one sentence, never a crash; the URL is never logged.
 */
class MailOAuthCodeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = if (SignInOwner.isOwner(this)) MailOAuthCodeSessions.authorizationUrl(intent?.getStringExtra(MailOAuthContract.EXTRA_HANDLE)) else null
        val opened = url != null && runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        if (!opened) Toast.makeText(this, if (url == null) "This sign-in expired: start it again in Cloud Mail" else "No browser can open the sign-in page", Toast.LENGTH_LONG).show()
        finish()
    }
}
