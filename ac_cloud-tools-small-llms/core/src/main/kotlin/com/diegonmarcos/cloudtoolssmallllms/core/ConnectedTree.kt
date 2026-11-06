package com.diegonmarcos.cloudtoolssmallllms.core

/** Where an external sensor stands on this phone, best first. */
enum class ConnState { CONNECTED, PAIRED, AVAILABLE, ABSENT }

/** The phone as the Connected catalogue asks about it (the app adapts Bluetooth, USB, input devices and PackageManager; a test fakes it). */
interface ConnectedFacts {
    /** GATT service UUIDs (normalized 128-bit) of the bonded Bluetooth devices. */
    fun bondedServiceUuids(): Set<String>
    /** GATT service UUIDs of the devices connected right now. */
    fun connectedServiceUuids(): Set<String>
    /** Service UUIDs and manufacturer ids seen advertising during this session's scans. */
    fun scannedServiceUuids(): Set<String>
    fun scannedManufacturerIds(): Set<Int>
    /** USB device and interface classes of the attached devices. */
    fun usbClasses(): Set<Int>
    fun feature(name: String): Boolean
    fun packageInstalled(name: String): Boolean
    /** An attached input device of this kind (gamepad, stylus, keyboard). */
    fun input(kind: String): Boolean
}

/** One external-sensor class resolved against this phone. */
data class ResolvedProfile(val profile: ConnectedProfile, val state: ConnState)

data class ConnectedFilter(val category: String? = null, val transport: String? = null, val present: Presence = Presence.ALL, val text: String = "")

/** The Connected catalogue's categories, resolution and filters. */
object ConnectedTree {
    /**
     * CONNECTED: a connected device offers the service, a matching USB class is attached or the input device is there. PAIRED: a bonded device offers
     * it. AVAILABLE: it was seen advertising, or the platform capability (UWB, Wi-Fi RTT, ANT+, Health Services, Thread) is present. Else ABSENT.
     */
    fun stateOf(p: ConnectedProfile, f: ConnectedFacts): ConnState {
        if (p.uuid != null && p.uuid in f.connectedServiceUuids()) return ConnState.CONNECTED
        if (p.usbClass != null && p.usbClass in f.usbClasses()) return ConnState.CONNECTED
        if (p.input != null && f.input(p.input)) return ConnState.CONNECTED
        if (p.uuid != null && p.uuid in f.bondedServiceUuids()) return ConnState.PAIRED
        if (p.uuid != null && p.uuid in f.scannedServiceUuids()) return ConnState.AVAILABLE
        if (p.manufacturerId != null && p.manufacturerId in f.scannedManufacturerIds()) return ConnState.AVAILABLE
        if (p.feature != null && f.feature(p.feature)) return ConnState.AVAILABLE
        if (p.packageName != null && f.packageInstalled(p.packageName)) return ConnState.AVAILABLE
        return ConnState.ABSENT
    }

    fun resolve(c: ConnectedCatalogue, f: ConnectedFacts): List<ResolvedProfile> = c.profiles.map { ResolvedProfile(it, stateOf(it, f)) }

    /** The profiles grouped in the declared category order (every declared category, even an empty one, then any category a profile names that is not declared). */
    fun grouped(c: ConnectedCatalogue, resolved: List<ResolvedProfile>): List<Pair<Pair<String, String>, List<ResolvedProfile>>> =
        c.categories.map { cat -> cat to resolved.filter { it.profile.category == cat.first } }

    fun filter(resolved: List<ResolvedProfile>, f: ConnectedFilter): List<ResolvedProfile> = resolved.filter { r ->
        (f.category == null || r.profile.category == f.category) && (f.transport == null || f.transport in r.profile.transport) &&
            (f.present == Presence.ALL || (f.present == Presence.PRESENT) == (r.state != ConnState.ABSENT)) &&
            (f.text.isBlank() || r.profile.label.contains(f.text, true) || r.profile.typicalUse.contains(f.text, true))
    }

    /** Every transport the catalogue declares (the Transport filter's choices), in declaration order. */
    fun transports(c: ConnectedCatalogue): List<String> = c.profiles.flatMap { it.transport }.distinct()
}
