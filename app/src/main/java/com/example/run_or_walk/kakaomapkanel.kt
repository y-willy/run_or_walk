package com.example.run_or_walk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles

@Composable
fun KakaoMapPanel(
    lifecycleOwner: LifecycleOwner,
    center: MapPoint? = null,
    routePoints: List<MapPoint> = emptyList(),
    modifier: Modifier = Modifier,
    currentPoint: MapPoint? = null,
    boardingPoint: MapPoint? = null,
    recenterRequest: Int = 0,
    onCurrentLocation: () -> Unit = {}
) {
    val context = LocalContext.current
    var retryCount by remember { mutableIntStateOf(0) }
    // Ordinary recomposition retains the View; only an explicit retry replaces it.
    val mapView = remember(context, retryCount) { MapView(context) }
    var kakaoMap by remember(mapView) { mutableStateOf<KakaoMap?>(null) }
    var mapError by remember(mapView) { mutableStateOf(false) }
    val markerBitmaps = remember(context) {
        val density = context.resources.displayMetrics.density
        createLocationMarker(density, false) to createLocationMarker(density, true)
    }
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    ) {
        key(mapView) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "승차 지점까지 도보 경로 지도. 파란 원은 현재 위치, 민트 핀은 승차 지점입니다."
                }
            )
        }

        if (kakaoMap != null && !mapError) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                Text(
                    "● 현재 위치  ·  ◆ 승차 지점",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        kakaoMap?.let { map ->
                            val level = (map.cameraPosition?.zoomLevel ?: 16) + 1
                            map.moveCamera(CameraUpdateFactory.zoomTo(level.coerceIn(1, 21)))
                        }
                    },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "지도 확대" },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                FilledTonalButton(
                    onClick = {
                        kakaoMap?.let { map ->
                            val level = (map.cameraPosition?.zoomLevel ?: 16) - 1
                            map.moveCamera(CameraUpdateFactory.zoomTo(level.coerceIn(1, 21)))
                        }
                    },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "지도 축소" },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            }
            FilledTonalButton(
                onClick = onCurrentLocation,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("◎ 현재 위치") }
        }

        if (kakaoMap == null || mapError) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(20.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (mapError) {
                        Text("지도를 불러오지 못했어요", style = MaterialTheme.typography.titleSmall)
                        Text("네트워크 연결을 확인하고 다시 시도해 주세요.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { retryCount++ }) { Text("지도 다시 불러오기") }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("지도를 준비하고 있어요", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var resumed = false
        var disposed = false
        var touchSurface: SurfaceView? = null
        mapView.setFinishManually(true)
        try {
            mapView.start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        Log.d("PaceNudge", "카카오맵 종료")
                    }

                    override fun onMapError(error: Exception) {
                        if (!disposed) mapError = true
                        Log.e("PaceNudge", "카카오맵 오류", error)
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(map: KakaoMap) {
                        if (disposed) return
                        // MapView is a FrameLayout: the SDK's child SurfaceView
                        // consumes touches, so listen there rather than on its parent.
                        // Returning false preserves the SDK's own gesture handling.
                        touchSurface = mapView.surfaceView
                        @Suppress("ClickableViewAccessibility")
                        touchSurface?.setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                        kakaoMap = map
                        mapError = false
                    }

                    override fun getPosition(): LatLng = LatLng.from(37.5665, 126.9780)
                    override fun getZoomLevel(): Int = 16
                }
            )
        } catch (error: Exception) {
            mapError = true
            Log.e("PaceNudge", "카카오맵 시작 오류", error)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!resumed) {
                    mapView.resume()
                    resumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (resumed) {
                    mapView.pause()
                    resumed = false
                }
                else -> Unit
            }
        }
        // LifecycleRegistry brings a newly registered observer to the current
        // state, including ON_RESUME when this panel enters an already live screen.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            touchSurface?.parent?.requestDisallowInterceptTouchEvent(false)
            touchSurface?.setOnTouchListener(null)
            mapView.parent?.requestDisallowInterceptTouchEvent(false)
            if (resumed) mapView.pause()
            mapView.finish()
        }
    }

    LaunchedEffect(kakaoMap, center, recenterRequest) {
        val map = kakaoMap ?: return@LaunchedEffect
        val point = center ?: return@LaunchedEffect
        map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(point.y, point.x)))
    }

    LaunchedEffect(kakaoMap, routePoints) {
        val map = kakaoMap ?: return@LaunchedEffect
        val layer = map.routeLineManager?.layer ?: return@LaunchedEffect
        layer.removeAll()
        if (routePoints.size >= 2) {
            val styles = RouteLineStyles.from(RouteLineStyle.from(12f, AndroidColor.rgb(20, 184, 166)))
            val positions = routePoints.map { LatLng.from(it.y, it.x) }
            val segment = RouteLineSegment.from(positions, styles)
            layer.addRouteLine(RouteLineOptions.from(segment))
        }
    }

    LaunchedEffect(kakaoMap, currentPoint, boardingPoint) {
        val map = kakaoMap ?: return@LaunchedEffect
        val manager = map.labelManager ?: return@LaunchedEffect
        val layer = manager.getLayer("pacenudge-positions")
            ?: manager.addLayer(LabelLayerOptions.from("pacenudge-positions"))
            ?: return@LaunchedEffect
        layer.removeAll()
        currentPoint?.let { point ->
            layer.addLabel(
                LabelOptions.from("current-position", LatLng.from(point.y, point.x))
                    .setStyles(LabelStyles.from(LabelStyle.from(markerBitmaps.first)
                        .setAnchorPoint(0.5f, 0.5f).setApplyDpScale(false)))
            )
        }
        boardingPoint?.let { point ->
            layer.addLabel(
                LabelOptions.from("boarding-position", LatLng.from(point.y, point.x))
                    .setStyles(LabelStyles.from(LabelStyle.from(markerBitmaps.second)
                        .setAnchorPoint(0.5f, 1f).setApplyDpScale(false)))
            )
        }
    }
}

// Native SDK labels use real coordinates. Shape and text distinguish the two
// markers without relying on color; no placeholder is shown before GPS returns.
private fun createLocationMarker(density: Float, boarding: Boolean): Bitmap {
    val width = (40 * density).toInt().coerceAtLeast(40)
    val height = if (boarding) (48 * density).toInt().coerceAtLeast(48) else width
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = width / 2f
    val cy = width / 2f
    if (boarding) {
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(cx, cy, width * 0.45f, paint)
        val tip = Path().apply {
            moveTo(width * 0.30f, cy + width * 0.25f)
            lineTo(cx, height.toFloat())
            lineTo(width * 0.70f, cy + width * 0.25f)
            close()
        }
        canvas.drawPath(tip, paint)
        paint.color = AndroidColor.rgb(15, 118, 110)
        canvas.drawCircle(cx, cy, width * 0.36f, paint)
        paint.color = AndroidColor.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * 0.35f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("승", cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
    } else {
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(cx, cy, width * 0.33f, paint)
        paint.color = AndroidColor.rgb(37, 99, 235)
        canvas.drawCircle(cx, cy, width * 0.24f, paint)
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(cx, cy, width * 0.08f, paint)
    }
    return bitmap
}
