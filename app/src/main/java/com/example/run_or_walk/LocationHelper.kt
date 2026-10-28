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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
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
        // Refresh every 20 seconds without repeatedly accepting a 20-second-old fix:
        // the guidance policy expires it at 30 seconds, before the following poll.
        return cached?.takeIf {
            val now = System.currentTimeMillis()
            locationSampleFailure(it, now) == null && now - it.observedAtEpochMillis <= 10_000L
        }
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
                    .setMaxUpdateAgeMillis(10_000L)
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

internal fun Location.asGuidanceSample(): GuidanceLocation {
    val elapsed = SystemClock.elapsedRealtimeNanos()
    // Future/absent monotonic timestamps are invalid observations, not fresh fixes.
    val ageMillis = if (elapsedRealtimeNanos in 1L..elapsed)
        (elapsed - elapsedRealtimeNanos) / 1_000_000L else Long.MAX_VALUE
    return GuidanceLocation(
        MapPoint(longitude, latitude),
        if (hasAccuracy()) accuracy.toDouble() else Double.POSITIVE_INFINITY,
        if (ageMillis == Long.MAX_VALUE) -1L else System.currentTimeMillis() - ageMillis,
        if (hasSpeed()) speed.toDouble() else null,
        if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        if(ageMillis==Long.MAX_VALUE) null else elapsedRealtimeNanos/1_000_000L)
}

suspend fun getCurrentPoint(context: Context): MapPoint = getCurrentLocationSample(context).point

/** Callback removal is tied to coroutine cancellation, including service stop and permission revocation. */
@Suppress("MissingPermission")
internal fun liveLocationSamples(context: Context): kotlinx.coroutines.flow.Flow<GuidanceLocation> =
    kotlinx.coroutines.flow.callbackFlow {
        if (!hasLocationPermission(context)) {
            close(DeviceLocationException(LocationFailure.PERMISSION_REQUIRED)); return@callbackFlow
        }
        val manager=context.getSystemService(LocationManager::class.java)
        if(manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            close(DeviceLocationException(LocationFailure.SERVICES_DISABLED)); return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.locations.forEach { trySend(it.asGuidanceSample()) }
            }
        }
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,4_000L)
            .setMinUpdateIntervalMillis(2_000L).setMaxUpdateDelayMillis(4_000L).setMaxUpdateAgeMillis(0L).build()
        try {
            client.requestLocationUpdates(request,callback,android.os.Looper.getMainLooper())
                .addOnFailureListener { close(it) }
        } catch (error: Exception) { close(error) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

/** A transient provider close must not permanently stop fresh fixes until the next Activity resume. */
internal fun recoveringLocationSamples(source:()->kotlinx.coroutines.flow.Flow<GuidanceLocation>,
    onFailure:(Exception)->Unit={},retryMillis:Long=5_000L):kotlinx.coroutines.flow.Flow<GuidanceLocation> = kotlinx.coroutines.flow.flow {
    var failures=0
    while(kotlinx.coroutines.currentCoroutineContext().isActive) {
        try {
            source().collect {fix ->failures=0;emit(fix)}
            return@flow
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(error:Exception) {
            onFailure(error);failures++
            kotlinx.coroutines.delay(minOf(30_000L,retryMillis*(1L shl minOf(failures-1,3))))
        }
    }
}
