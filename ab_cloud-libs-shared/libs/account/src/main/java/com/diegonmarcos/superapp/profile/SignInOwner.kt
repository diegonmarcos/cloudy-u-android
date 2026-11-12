package com.diegonmarcos.superapp.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.SignInContract
import com.diegonmarcos.superapp.settings.AccountVault

/**
 * #1693 Cloud Account is the one app that STARTS a sign-in. libs:account draws the same Connect / sign-in pages in
 * every host, so each page asks [isOwner] first: in Cloud Account the ways run as before; in any other host the same
 * tile stays in place (rule 16) but becomes a link ([open]) to Cloud Account at that provider's page, and its line
 * is the state Cloud Account reports ([status], over the accountdata provider's [SignInContract.METHOD]).
 * Nothing here throws: a missing or refusing Cloud Account is a sentence on the tile.
 */
object SignInOwner {
    fun isOwner(ctx: Context): Boolean = ctx.packageName == SignInContract.ACCOUNT_PACKAGE

    /** Opens Cloud Account at [provider]'s sign-in page; null when it opened, else the reason it could not. */
    fun open(ctx: Context, provider: String): String? {
        if (!AccountData.accountInstalled(ctx)) return SignInContract.missing(provider).line
        val extras = SignInContract.openIntentExtras(provider)
        val intent = Intent(SignInContract.ACTION_OPEN).setPackage(SignInContract.ACCOUNT_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((k, v) in extras) intent.putExtra(k, v)
        return runCatching { ctx.startActivity(intent); null }.getOrElse {
            runCatching {
                val launch = ctx.packageManager.getLaunchIntentForPackage(SignInContract.ACCOUNT_PACKAGE)
                    ?: return "Cloud Account cannot be opened (${it.javaClass.simpleName})"
                for ((k, v) in extras) launch.putExtra(k, v)
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null
            }.getOrElse { e -> "Cloud Account cannot be opened (${e.javaClass.simpleName})" }
        }
    }

    /** [provider]'s sign-in state: read locally in Cloud Account, asked of Cloud Account everywhere else. Blocking: call off the main thread. */
    fun status(ctx: Context, provider: String): SignInContract.Status {
        if (isOwner(ctx)) return local(ctx, provider)
        if (!AccountData.accountInstalled(ctx)) return SignInContract.missing(provider)
        val r: Bundle? = runCatching {
            ctx.contentResolver.call(Uri.parse("content://${SignInContract.AUTHORITY}"), SignInContract.METHOD, provider, null)
        }.getOrNull()
        r ?: return SignInContract.Status(provider, SignInContract.State.UNKNOWN, "Cloud Account did not answer: update it or open it once")
        return SignInContract.parse(provider, r.getBoolean(SignInContract.KEY_OK), r.getString(SignInContract.KEY_JSON), r.getString(SignInContract.KEY_ERROR))
    }

    /** Cloud Account's own answer: a forge token it filed, else the last vault fetch and the way that made it. No secret leaves. */
    fun local(ctx: Context, provider: String): SignInContract.Status = runCatching {
        val vault = AccountVault(ctx)
        val filed = (vault.connection("forge.$provider.token") as? String).orEmpty().isNotBlank()
        val last = ConnectWays.lastFetch(ctx)
        val at = last?.optString("at").orEmpty()
        val line = last?.optString("line").orEmpty()
        val viaThis = last?.optBoolean("ok") == true && (provider == "vault" || line.contains(provider, ignoreCase = true) ||
            (provider == "github" && line.contains(ConnectWays.GH_AUTH_LOGIN)))
        when {
            filed -> SignInContract.Status(provider, SignInContract.State.SIGNED_IN, "signed in in Cloud Account (token filed)", at)
            viaThis -> SignInContract.Status(provider, SignInContract.State.SIGNED_IN, "signed in in Cloud Account · $line", at)
            else -> SignInContract.Status(provider, SignInContract.State.SIGNED_OUT, "not signed in: sign in in Cloud Account", at)
        }
    }.getOrElse { SignInContract.Status(provider, SignInContract.State.UNKNOWN, "Cloud Account could not read its state (${it.javaClass.simpleName})") }

    /** The provider a Connect way belongs to: its forge, else the way's kind family. */
    fun providerOf(way: ConnectWays.Way): String = way.forge ?: when (way.kind) {
        ConnectWays.AUTHELIA_WEB -> "authelia"
        ConnectWays.VAULT_FILE -> "vault"
        else -> way.kind
    }
}
