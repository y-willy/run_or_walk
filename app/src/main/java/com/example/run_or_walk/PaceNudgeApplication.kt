package com.example.run_or_walk

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk

class PaceNudgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 네이티브 앱 키가 설정되었는지 확인
        check(BuildConfig.KAKAO_NATIVE_KEY.isNotBlank()) {
            "KAKAO_NATIVE_KEY를 local.properties에 설정하세요."
        }

        // 카카오맵 SDK 초기화
        KakaoMapSdk.init(
            this,
            BuildConfig.KAKAO_NATIVE_KEY
        )
    }
}