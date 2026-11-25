package com.thelightphone.sdk.emulator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.shared.LightResult
import com.thelightphone.sdk.shared.LightServiceMethod
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object EmulatorLocationHelper {

    data class CurrentLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val timestamp: Instant,
    )

    data class Location(val latitude: Double, val longitude: Double)

    private val requestInterval = 1.minutes
    private val activeUidMap = ConcurrentHashMap<Int, Instant>()
    @Volatile private var currentLocation: CurrentLocation? = null
    @Volatile private var defaultLocation: Location? = null

    fun updateDefaultLocation(newLocation: Location?) {
        defaultLocation = newLocation
    }

    /**
     * Ends [callingUid]'s active location subscription, if any - host resource cleanup on
     * disable/purge.
     */
    fun revokeUid(callingUid: Int) {
        activeUidMap.remove(callingUid)
    }

    fun updateCurrentLocation(newLocation: CurrentLocation?): Boolean {
        if (newLocation == null) {
            currentLocation = null
            return true
        } else if (
            activeUidMap.none { (_, lastRequestTime) ->
                Clock.System.now() - lastRequestTime < requestInterval
            }
        ) {
            // if no tools have requested updates in the last minute, updates should fail
            // (simulates LightOS not polling location)
            return false
        }

        currentLocation = newLocation
        return true
    }

    private fun Context.hasLocationPermission(callingUid: Int): Boolean {
        val packageName =
            packageManager.getPackagesForUid(callingUid)?.firstOrNull() ?: return false
        return listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            .any {
                packageManager.checkPermission(it, packageName) == PackageManager.PERMISSION_GRANTED
            }
    }

    fun LightSdkServer.initLocationHelper(context: Context) {
        onGetDefaultLocation = { callingUid ->
            if (!context.hasLocationPermission(callingUid)) {
                LightResult.Error(LightResult.ErrorCode.NoPermission)
            } else {
                val location = defaultLocation
                LightResult.Success(
                    LightServiceMethod.GetDefaultLocation.Response(
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                    )
                )
            }
        }

        onGetCurrentLocation = { callingUid ->
            if (!context.hasLocationPermission(callingUid)) {
                LightResult.Error(LightResult.ErrorCode.NoPermission)
            } else {
                val location = currentLocation
                LightResult.Success(
                    LightServiceMethod.GetCurrentLocation.Response(
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        accuracyMeters = location?.accuracyMeters,
                        timestampMs = location?.timestamp?.toEpochMilliseconds(),
                    )
                )
            }
        }

        onRequestLocationUpdates = { callingUid ->
            if (!context.hasLocationPermission(callingUid)) {
                LightResult.Error(LightResult.ErrorCode.NoPermission)
            } else {
                activeUidMap[callingUid] = Clock.System.now()
                LightResult.Success(Unit)
            }
        }

        onReleaseLocationUpdates = { callingUid ->
            activeUidMap.remove(callingUid)
            LightResult.Success(Unit)
        }
    }
}
