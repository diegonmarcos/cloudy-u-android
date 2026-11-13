package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONArray

/**
 * #1693 Cloud Account's filing of the credentials other apps used to ask for themselves (App credentials page): one flat vault key per
 * id ([AppCredentialsContract.vaultPath]) in the keystore-backed vault, an index of the prefixed ids (`git.host.<host>`) beside them.
 * [read] is what the provider answers: default deny on the per-credential grant. No value is ever logged or put in an error.
 */
object AppCredentialsStore {
    private const val INDEX = "appcred_index"

    fun get(vault: AccountVault, id: String): String = (vault.connection(AppCredentialsContract.vaultPath(id)) as? String).orEmpty()

    /** Files [value] under [id]; false (nothing written) when the id is not a catalogue id or the value is empty or too long. */
    fun put(vault: AccountVault, id: String, value: String): Boolean {
        if (AppCredentialsContract.entry(id) == null) return false
        if (value.isBlank() || value.length > AppCredentialsContract.MAX_VALUE) return false
        vault.putConnection(AppCredentialsContract.vaultPath(id), value.trim())
        index(vault, id, add = true)
        return true
    }

    fun remove(vault: AccountVault, id: String): Boolean {
        if (AppCredentialsContract.entry(id) == null) return false
        vault.putConnection(AppCredentialsContract.vaultPath(id), "")
        index(vault, id, add = false)
        return true
    }

    /** Every fixed catalogue id, then the prefixed ids filed so far. */
    fun ids(vault: AccountVault): List<String> {
        val filed = (vault.connection(INDEX) as? String)?.let { runCatching { JSONArray(it) }.getOrNull() }
        val extra = filed?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { AppCredentialsContract.entry(it)?.prefix == true }
        return AppCredentialsContract.CATALOG.filter { !it.prefix }.map { it.id } + extra.distinct().sorted()
    }

    private fun index(vault: AccountVault, id: String, add: Boolean) {
        if (AppCredentialsContract.entry(id)?.prefix != true) return
        val cur = (vault.connection(INDEX) as? String)?.let { runCatching { JSONArray(it) }.getOrNull() }
        val list = (cur?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() - id).let { if (add) it + id else it }
        vault.putConnection(INDEX, JSONArray(list).toString())
    }

    /** Count of ids with a value, for the sign-in state line. */
    fun filled(vault: AccountVault): Int = ids(vault).count { get(vault, it).isNotBlank() }

    /** The provider's answer for [caller] asking [id]: the grant `cred.<id>` is required unless Cloud Account itself asks. */
    fun read(ctx: Context, caller: String, id: String?, vault: AccountVault = AccountVault(ctx)): Bundle {
        fun refuse(why: String) = Bundle().apply { putBoolean(AppCredentialsContract.KEY_OK, false); putString(AppCredentialsContract.KEY_ERROR, why) }
        if (AppCredentialsContract.entry(id) == null) return refuse("unknown credential id")
        if (caller != ctx.packageName && !vault.grants.allow(caller, AppCredentialsContract.grant(id!!))) return refuse("no grant for $caller")
        return Bundle().apply { putBoolean(AppCredentialsContract.KEY_OK, true); putString(AppCredentialsContract.KEY_VALUE, get(vault, id!!)) }
    }
}
