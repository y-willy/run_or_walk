package com.example.run_or_walk

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

class PaceNudgeApplication : Application() {

    internal var mapInitializationFailure: MapLoadFailure? = null
        private set

    override fun onCreate() {
        super.onCreate()

        initializeMapSdk()
    }

    /** A map configuration/native failure must not prevent the rest of the app opening. */
    internal fun initializeMapSdk(): MapLoadFailure? {
        mapInitializationFailure = if (KakaoMapSdk.isInitialized()) {
            null
        } else if (BuildConfig.KAKAO_NATIVE_KEY.isBlank()) {
            MapLoadFailure(MapFailureCategory.CONFIGURATION)
        } else {
            try {
                KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY.trim())
                if (KakaoMapSdk.isInitialized()) null
                else MapLoadFailure(MapFailureCategory.INITIALIZATION)
            } catch (error: Exception) {
                MapLoadFailure.from(error)
            } catch (error: LinkageError) {
                MapLoadFailure.from(error)
            }
        }
        mapInitializationFailure?.let { Log.w("PaceNudgeMap", it.diagnosticCode) }
        return mapInitializationFailure
    }
}
