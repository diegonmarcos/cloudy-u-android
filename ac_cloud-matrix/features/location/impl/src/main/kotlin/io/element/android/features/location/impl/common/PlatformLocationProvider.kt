/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.location.impl.common

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location as AndroidLocation
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.locationclient.NavLocationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.location.DesiredAccuracy
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.asMapLibreLocation
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.inMeters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * #1770 the position this app shows and shares is Cloud Nav's last known fix (libs:location-client), the fleet's one location
 * owner. This provider never asks the platform for a position: it re-reads Cloud Nav's cached fix every [updateInterval]
 * (at least [MIN_POLL_MS]) and emits it when it moved at least [minDistance] from the one emitted before. Needs no location grant.
 */
@SuppressLint("InlinedApi")
class PlatformLocationProvider(
    context: Context,
    private val updateInterval: Duration,
    private val minDistance: Length,
    @Suppress("unused") private val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
    coroutineScope: CoroutineScope,
    sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : LocationProvider {
    override val location: StateFlow<Location?>

    init {
        val app = context.applicationContext
        val pollMs = updateInterval.inWholeMilliseconds.coerceAtLeast(MIN_POLL_MS)
        val minMeters = minDistance.inMeters.toFloat()
        val navFlow = flow {
            var last: AndroidLocation? = null
            while (true) {
                NavLocationClient.lastKnown(app, MAX_AGE_MS)?.let { fix ->
                    val now = toAndroidLocation(fix)
                    if (last == null || last!!.distanceTo(now) >= minMeters) {
                        last = now
                        emit(now.asMapLibreLocation())
                    }
                }
                delay(pollMs)
            }
        }.flowOn(Dispatchers.IO)
        location = navFlow.stateIn(coroutineScope, sharingStarted, null)
    }

    private fun toAndroidLocation(f: NavLocationClient.Fix) = AndroidLocation("cloud-nav").apply {
        latitude = f.lat; longitude = f.lon
        f.accuracyM?.let { accuracy = it }
        time = f.timeMs
        val ageMs = (System.currentTimeMillis() - f.timeMs).coerceAtLeast(0L)
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - TimeUnit.MILLISECONDS.toNanos(ageMs)
    }

    private companion object {
        const val MIN_POLL_MS = 5_000L
        const val MAX_AGE_MS = 15 * 60_000L
    }
}

@Composable
fun rememberPlatformLocationProvider(
    updateInterval: Duration,
    minDistance: Length,
    desiredAccuracy: DesiredAccuracy = DesiredAccuracy.High,
    context: Context = LocalContext.current,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): PlatformLocationProvider {
    return remember(context, updateInterval, minDistance, desiredAccuracy, coroutineScope, sharingStarted) {
        PlatformLocationProvider(
            context = context,
            updateInterval = updateInterval,
            minDistance = minDistance,
            desiredAccuracy = desiredAccuracy,
            coroutineScope = coroutineScope,
            sharingStarted = sharingStarted,
        )
    }
}
