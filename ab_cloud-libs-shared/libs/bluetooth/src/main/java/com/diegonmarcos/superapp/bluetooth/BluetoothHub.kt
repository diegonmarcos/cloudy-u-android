package com.diegonmarcos.superapp.bluetooth

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The process-wide Bluetooth stack (#1221): one [AndroidBleAdapter], one [GattSession], one [BleController]. The five tabs of the page
 * are separate screens, and Cloud Browser's Web Bluetooth bridge shares the session, so the connection outlives a tab switch.
 */
object BluetoothHub {
    @Volatile private var adapter: AndroidBleAdapter? = null
    @Volatile private var controller: BleController? = null
    @Volatile private var session: GattSession? = null
    @Volatile private var catalog: GattCatalog? = null
    @Volatile private var companies: CompanyIds? = null

    /** #1264 the vendor vocabulary the analyzer names manufacturer data with (runtime dataset company-ids). */
    fun companies(ctx: Context): CompanyIds = companies ?: synchronized(this) {
        companies ?: CompanyIds.served(ctx.applicationContext)?.also { companies = it } ?: CompanyIds.parse("{}")   // not cached until served: retried
    }

    fun catalog(ctx: Context): GattCatalog = catalog ?: synchronized(this) {
        catalog ?: GattCatalog.served(ctx.applicationContext)?.also { catalog = it } ?: GattCatalog.parse("{}")   // not cached until served: retried
    }

    fun session(ctx: Context): GattSession = session ?: synchronized(this) {
        session ?: run {
            val app = ctx.applicationContext
            val main = Handler(Looper.getMainLooper())
            val a = AndroidBleAdapter(app).also { adapter = it }
            GattSession(a, BleLog(), { ms, block -> main.postDelayed(block, ms) }).also { session = it }
        }
    }

    fun controller(ctx: Context): BleController = controller ?: synchronized(this) {
        controller ?: BleController(session(ctx), catalog(ctx)).also { controller = it }
    }
}
