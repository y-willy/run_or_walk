package com.example.run_or_walk

import android.graphics.Color
import android.util.Log

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles

@Composable
fun KakaoMapPanel(
    lifecycleOwner: LifecycleOwner,
    center: MapPoint? = null,
    routePoints: List<MapPoint> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 지도 View를 재구성 시에도 유지
    val mapView = remember(context) {
        MapView(context)
    }

    // 준비된 카카오맵 객체 저장
    var kakaoMap by remember {
        mutableStateOf<KakaoMap?>(null)
    }

    // Compose 화면 안에 카카오 지도 표시
    AndroidView(
        factory = { mapView },
        modifier = modifier
    )

    // Activity 생명주기와 지도 연결
    DisposableEffect(mapView, lifecycleOwner) {

        mapView.setFinishManually(true)

        mapView.start(
            object : MapLifeCycleCallback() {

                override fun onMapDestroy() {
                    Log.d("PaceNudge", "카카오맵 종료")
                }

                override fun onMapError(error: Exception) {
                    Log.e(
                        "PaceNudge",
                        "카카오맵 오류",
                        error
                    )
                }
            },

            object : KakaoMapReadyCallback() {

                override fun onMapReady(map: KakaoMap) {
                    // 인증과 지도 준비 완료
                    kakaoMap = map
                }

                override fun getPosition(): LatLng {
                    // 최초 지도 위치: 서울시청
                    return LatLng.from(37.5665, 126.9780)
                }

                override fun getZoomLevel(): Int {
                    return 16
                }
            }
        )

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.resume()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    mapView.pause()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.finish()
        }
    }

    // 검색 지점이나 현재 위치로 카메라 이동
    LaunchedEffect(kakaoMap, center) {
        val map = kakaoMap ?: return@LaunchedEffect
        val point = center ?: return@LaunchedEffect

        map.moveCamera(
            CameraUpdateFactory.newCenterPosition(
                LatLng.from(point.y, point.x)
            )
        )
    }

    // 실제 도보 경로 좌표를 파란 선으로 표시
    LaunchedEffect(kakaoMap, routePoints) {
        val map = kakaoMap ?: return@LaunchedEffect

        val layer = map.routeLineManager?.layer ?: return@LaunchedEffect

        // 이전에 표시된 도보 경로 삭제
        layer.removeAll()

        if (routePoints.size < 2) {
            return@LaunchedEffect
        }

        val styles = RouteLineStyles.from(
            RouteLineStyle.from(12f, Color.BLUE)
        )

        val positions = routePoints.map { point ->
            // MapPoint: x=경도, y=위도
            // LatLng: 위도, 경도 순서
            LatLng.from(point.y, point.x)
        }

        val segment = RouteLineSegment.from(positions, styles)

        layer.addRouteLine(
            RouteLineOptions.from(segment)
        )
    }
}