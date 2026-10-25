package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** A recent, accurate cached fix remains a sensor observation, never a new GPS reading.
 * The current request is bounded so a null fix indoors cannot leave the screen loading forever.
 */
suspend fun getCurrentLocationSample(context: Context): GuidanceLocation {
    if (!hasLocationPermission(context)) throw DeviceLocationException(LocationFailure.PERMISSION_REQUIRED)
    val manager = context.getSystemService(LocationManager::class.java)
    if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
        throw DeviceLocationException(LocationFailure.SERVICES_DISABLED)
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    var rejectedSample: GuidanceLocation? = null

    suspend fun freshCachedSample(): GuidanceLocation? {
        val cached = try {
            @Suppress("MissingPermission")
            withTimeoutOrNull(1_500L) { client.lastLocation.awaitLocation() }?.asGuidanceSample()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (cached != null && (rejectedSample == null ||
                cached.observedAtEpochMillis > requireNotNull(rejectedSample).observedAtEpochMillis)) {
            rejectedSample = cached
        }
        return cached?.takeIf { locationSampleFailure(it, System.currentTimeMillis()) == null }
    }

    freshCachedSample()?.let { return it }
    var providerFailure: LocationFailure? = null
    val current = try {
        withTimeoutOrNull(12_500L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val token = CancellationTokenSource()
                continuation.invokeOnCancellation { token.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(30_000L)
                    .setDurationMillis(12_000L)
                    .build()
                try {
                    @Suppress("MissingPermission")
                    val task = client.getCurrentLocation(request, token.token)
                    task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    task.addOnFailureListener {
                        if (continuation.isActive) continuation.resumeWithException(
                            DeviceLocationException(if (it is SecurityException)
                                LocationFailure.PERMISSION_REQUIRED else LocationFailure.SERVICE_UNAVAILABLE))
                    }
                    task.addOnCanceledListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        DeviceLocationException(if (error is SecurityException)
                            LocationFailure.PERMISSION_REQUIRED else LocationFailure.SERVICE_UNAVAILABLE))
                }
            }
        }?.asGuidanceSample()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: DeviceLocationException) {
        if (error.failure == LocationFailure.PERMISSION_REQUIRED) throw error
        providerFailure = error.failure
        null
    }
    if (current != null) {
        rejectedSample = current
        if (locationSampleFailure(current, System.currentTimeMillis()) == null) return current
    }
    // A location request can update the fused cache even when its own result is null.
    freshCachedSample()?.let { return it }
    throw DeviceLocationException(rejectedSample?.let {
        locationSampleFailure(it, System.currentTimeMillis())
    } ?: providerFailure ?: LocationFailure.TIMEOUT)
}

private suspend fun Task<Location>.awaitLocation(): Location? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener {
        if (continuation.isActive) continuation.resumeWithException(
            DeviceLocationException(LocationFailure.SERVICE_UNAVAILABLE))
    }
    addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
}

private fun Location.asGuidanceSample(): GuidanceLocation {
    val elapsed = SystemClock.elapsedRealtimeNanos()
    // Future/absent monotonic timestamps are invalid observations, not fresh fixes.
    val ageMillis = if (elapsedRealtimeNanos in 1L..elapsed)
        (elapsed - elapsedRealtimeNanos) / 1_000_000L else Long.MAX_VALUE
    return GuidanceLocation(
        MapPoint(longitude, latitude),
        if (hasAccuracy()) accuracy.toDouble() else Double.POSITIVE_INFINITY,
        if (ageMillis == Long.MAX_VALUE) -1L else System.currentTimeMillis() - ageMillis)
}

suspend fun getCurrentPoint(context: Context): MapPoint = getCurrentLocationSample(context).point
