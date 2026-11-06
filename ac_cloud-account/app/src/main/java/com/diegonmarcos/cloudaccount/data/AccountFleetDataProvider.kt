package com.diegonmarcos.cloudaccount.data

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.fleetdata.provider.FleetApiSource
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataEndpoint
import com.diegonmarcos.superapp.fleetdata.provider.FleetDataHost
import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.settings.AccountVault

/**
 * #1547 accountdata, extended for the personal (tier 1) and fleet/infra (tier 2) datasets: the existing call() methods are untouched,
 * and openFile serves the generic routes app/<pkg>/<dataset>, shared/<dataset> and index through the shared fleet data endpoint.
 * Tier 1 is kept encrypted at rest (Keystore); both tiers are synced ONLY from the authenticated cloudy-c3-api route with the fleet bearer,
 * never from the public repo.
 */
class AccountFleetDataProvider : AccountData.Provider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        FleetDataHost.register(FleetDataRouting.ACCOUNT, FleetApiSource(
            apiBase = {
                FleetEndpointsContract.parseDecl(AccountHost.fleetEndpointsDecl()).firstOrNull { it.id == "c3-infra-api" }
                    ?.publicUrl()?.takeIf { it.isNotBlank() }
            },
            bearer = { AccountVault(ctx).connection("fleet.bearer")?.toString() },
        ))
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        return FleetDataEndpoint.of(ctx, uri.authority ?: return null).openFile(uri, mode, callingPackage)
    }
}
