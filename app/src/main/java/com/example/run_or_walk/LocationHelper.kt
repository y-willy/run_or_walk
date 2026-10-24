
package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 위치 권한을 받았는지 검사
fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    return fine || coarse
}

// GPS로 현재 위치 한 번 가져오기
suspend fun getCurrentPoint(context: Context): MapPoint =
    suspendCancellableCoroutine { continuation ->

        if (!hasLocationPermission(context)) {
            continuation.resumeWithException(
                SecurityException("위치 권한이 없습니다.")
            )
            return@suspendCancellableCoroutine
        }

        // Google 위치 서비스 객체
        val client =
            LocationServices.getFusedLocationProviderClient(context)

        // 위치 요청 취소에 사용
        val tokenSource = CancellationTokenSource()

        continuation.invokeOnCancellation {
            tokenSource.cancel()
        }

        try {
            @Suppress("MissingPermission")
            val task = client.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                tokenSource.token
            )

            task.addOnSuccessListener { location ->
                if (continuation.isActive) {
                    if (location != null) {
                        // GPS 위도·경도를 ODsay 좌표 형식으로 변환
                        continuation.resume(
                            MapPoint(
                                x = location.longitude,
                                y = location.latitude
                            )
                        )
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException(
                                "현재 위치를 가져오지 못했습니다. GPS를 켜고 다시 시도하세요."
                            )
                        )
                    }
                }
            }

            task.addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            task.addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel()
                }
            }
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
