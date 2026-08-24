package com.example.aurorascout

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationClient(context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): GeoPoint = suspendCancellableCoroutine { cont ->
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) cont.resume(GeoPoint(location.latitude, location.longitude))
                else {
                    fused.lastLocation
                        .addOnSuccessListener { last ->
                            if (last != null) cont.resume(GeoPoint(last.latitude, last.longitude))
                            else cont.resumeWithException(IllegalStateException("No location fix is available yet."))
                        }
                        .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                }
            }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
