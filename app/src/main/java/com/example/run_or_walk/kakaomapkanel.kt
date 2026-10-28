package com.example.run_or_walk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import com.kakao.vectormap.KakaoMapSdk
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
import kotlinx.coroutines.delay

@Composable
fun KakaoMapPanel(
    lifecycleOwner: LifecycleOwner,
    center: MapPoint? = null,
    routePoints: List<MapPoint> = emptyList(),
    modifier: Modifier = Modifier,
    currentPoint: MapPoint? = null,
    boardingPoint: MapPoint? = null,
    recenterRequest: Int = 0,
    onCurrentLocation: () -> Unit = {},
    accessSegments: List<List<MapPoint>> = emptyList(),
    journeySegments: List<List<MapPoint>> = emptyList(),
    departurePoint: MapPoint? = null,
    destinationPoint: MapPoint? = null,
    journeyKey: Any? = null,
    active: Boolean = true,
    showControls: Boolean = true
) {
    val context = LocalContext.current
    val latestActive by rememberUpdatedState(active)
    var retryCount by remember { mutableIntStateOf(0) }
    // Ordinary recomposition retains the View; only an explicit retry replaces it.
    val mapView = remember(context, lifecycleOwner, retryCount) { MapView(context) }
    var kakaoMap by remember(mapView) { mutableStateOf<KakaoMap?>(null) }
    var mapFailure by remember(mapView) { mutableStateOf<MapLoadFailure?>(null) }
    var mapResumed by remember(mapView) { mutableStateOf(false) }
    var showAuthenticationHelp by remember { mutableStateOf(false) }
    var userMovedCamera by remember(mapView,boardingPoint,journeyKey) { mutableStateOf(false) }
    var recenterAfterLocation by remember(mapView) { mutableStateOf(false) }
    var lastFitKey by remember(mapView) { mutableStateOf<Any?>(null) }
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
                    contentDescription = if (departurePoint != null) "파란 경로는 현재 위치에서 지정 출발지, 초록 경로는 지정 출발지에서 목적지입니다." else "현재 위치에서 승차 지점까지 도보 경로 지도입니다."
                }
            )
        }

        if (kakaoMap != null && mapFailure == null) {
            if(showControls) Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                Text(
                    if (departurePoint != null) "파란색: 현위치 → 출발지\n초록색: 출발지 → 목적지"
                    else "● 현재 위치  ·  ◆ 승차 지점",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if(showControls) Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        userMovedCamera = true
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
                        userMovedCamera = true
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
            if(showControls) FilledTonalButton(
                onClick = { userMovedCamera = true; recenterAfterLocation = true; onCurrentLocation() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("◎ 현재 위치") }
        }

        if (kakaoMap == null || mapFailure != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(20.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val failure = mapFailure
                    if (failure != null) {
                        Text("지도를 불러오지 못했어요", style = MaterialTheme.typography.titleSmall)
                        val authenticationFailure = failure.category in setOf(
                            MapFailureCategory.AUTHENTICATION, MapFailureCategory.CONFIGURATION)
                        Text(if (authenticationFailure) "카카오 개발자 설정에서 앱 인증 정보를 확인해 주세요."
                            else failure.guidance, style = MaterialTheme.typography.bodySmall)
                        Text("오류 코드: ${failure.diagnosticCode}", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (authenticationFailure) {
                                TextButton(onClick = { showAuthenticationHelp = true }) { Text("지도 인증 설정") }
                            }
                            Button(onClick = { retryCount++ }) { Text("재시도") }
                        }
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

    if (showAuthenticationHelp) {
        MapAuthenticationHelpDialog(onDismiss = { showAuthenticationHelp = false })
    }

    DisposableEffect(mapView, lifecycleOwner) {
        var disposed = false
        var startSucceeded = false
        var startAttempted = false
        var touchSurface: SurfaceView? = null
        val mainHandler = Handler(Looper.getMainLooper())
        fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            else mainHandler.post { block() }
        }
        fun reportFailure(failure: MapLoadFailure) {
            if (disposed) return
            if (mapFailure != null && mapFailure?.category != MapFailureCategory.TIMEOUT) return
            mapFailure = failure
            kakaoMap = null
            // SDK exception messages can include request/authentication details.
            Log.w("PaceNudgeMap", failure.diagnosticCode)
        }
        mapResumed = false
        val initializationFailure =
            (context.applicationContext as? PaceNudgeApplication)?.initializeMapSdk()
                ?: if (!KakaoMapSdk.isInitialized()) {
                    MapLoadFailure(MapFailureCategory.INITIALIZATION)
                } else null
        try {
            if (initializationFailure != null) {
                reportFailure(initializationFailure)
            } else {
                startAttempted = true
                mapView.start(
                    object : MapLifeCycleCallback() {
                        override fun onMapDestroy() {
                            onMain {
                                if (!disposed && mapFailure == null) {
                                    reportFailure(MapLoadFailure(MapFailureCategory.LIFECYCLE))
                                }
                            }
                        }

                        override fun onMapError(error: Exception) {
                            onMain { reportFailure(MapLoadFailure.from(error)) }
                        }
                    },
                    object : KakaoMapReadyCallback() {
                        override fun onMapReady(map: KakaoMap) {
                            onMain {
                                if (disposed || mapFailure != null) return@onMain
                                // The SDK's child SurfaceView consumes touches.
                                // Returning false preserves its gesture handling.
                                touchSurface = mapView.surfaceView
                                @Suppress("ClickableViewAccessibility")
                                touchSurface?.setOnTouchListener { view, event ->
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                                            { userMovedCamera = true; recenterAfterLocation = false; view.parent?.requestDisallowInterceptTouchEvent(true) }
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                            view.parent?.requestDisallowInterceptTouchEvent(false)
                                    }
                                    false
                                }
                                kakaoMap = map
                            }
                        }

                        override fun getPosition(): LatLng = LatLng.from(37.5665, 126.9780)
                        override fun getZoomLevel(): Int = 16
                    }
                )
            }
            if (initializationFailure == null && mapFailure == null) {
                // SDK 2.15.2 ignores this setter before start(): the renderer
                // must exist. Keep it alive across temporary Surface detach.
                mapView.setFinishManually(true)
                startSucceeded = true
            }
        } catch (error: Exception) {
            reportFailure(MapLoadFailure.from(error))
        } catch (error: LinkageError) {
            reportFailure(MapLoadFailure.from(error))
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (startSucceeded && !mapResumed && latestActive) {
                    mapView.resume()
                    mapResumed = true
                }
                Lifecycle.Event.ON_PAUSE -> if (mapResumed) {
                    mapView.pause()
                    mapResumed = false
                }
                else -> Unit
            }
        }
        // LifecycleRegistry brings a newly registered observer to the current
        // state, including ON_RESUME when this panel enters an already live screen.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            val wasResumed=mapResumed
            mapResumed = false
            lifecycleOwner.lifecycle.removeObserver(observer)
            touchSurface?.parent?.requestDisallowInterceptTouchEvent(false)
            touchSurface?.setOnTouchListener(null)
            mapView.parent?.requestDisallowInterceptTouchEvent(false)
            if (wasResumed) mapView.pause()
            if (startAttempted) mapView.finish()
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    // Keep the same View/camera across tabs, but pause its renderer when the panel is hidden.
    LaunchedEffect(active,mapView,kakaoMap) {
        if(!active && mapResumed) {mapView.pause();mapResumed=false}
        else if(active && kakaoMap!=null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !mapResumed) {
            mapView.resume();mapResumed=true
        }
    }

    // Count only foreground waiting; backgrounding the Activity cancels the
    // timer. An explicit retry gets a new View and invalidates old callbacks.
    LaunchedEffect(mapView, mapResumed, kakaoMap, mapFailure) {
        if (mapResumed && kakaoMap == null && mapFailure == null) {
            delay(20_000)
            val failure = MapLoadFailure(MapFailureCategory.TIMEOUT)
            mapFailure = failure
            Log.w("PaceNudgeMap", failure.diagnosticCode)
        }
    }

    LaunchedEffect(kakaoMap, center, recenterRequest) {
        val map = kakaoMap ?: return@LaunchedEffect
        val point = center ?: return@LaunchedEffect
        if (recenterAfterLocation || !userMovedCamera && boardingPoint == null && routePoints.isEmpty() &&
            accessSegments.isEmpty() && journeySegments.isEmpty()) {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(point.y, point.x)))
            recenterAfterLocation = false
        }
    }

    LaunchedEffect(kakaoMap, routePoints, accessSegments, journeySegments, departurePoint) {
        val map = kakaoMap ?: return@LaunchedEffect
        val layer = map.routeLineManager?.layer ?: return@LaunchedEffect
        layer.removeAll()
        val paths = if (departurePoint != null) accessSegments.map { it to AndroidColor.rgb(37,99,235) } +
            journeySegments.map { it to AndroidColor.rgb(22,163,74) }
            else listOf(routePoints to AndroidColor.rgb(20,184,166))
        for ((path,color) in paths) if (path.size >= 2) {
            val styles = RouteLineStyles.from(RouteLineStyle.from(12f, color))
            val positions = path.map { LatLng.from(it.y, it.x) }
            val segment = RouteLineSegment.from(positions, styles)
            layer.addRouteLine(RouteLineOptions.from(segment))
        }
    }

    LaunchedEffect(kakaoMap, journeyKey, accessSegments, journeySegments, routePoints, currentPoint,boardingPoint,userMovedCamera) {
        val map = kakaoMap ?: return@LaunchedEffect
        val points = walkingMapViewportPoints(routePoints,currentPoint,boardingPoint,accessSegments+journeySegments)
        // Fit each newly available extent before a gesture. Never recenter on ETA ticks/GPS updates.
        val fitKey = Triple(journeyKey,boardingPoint,routePoints.isNotEmpty() to (accessSegments.isNotEmpty() to journeySegments.isNotEmpty()))
        if (!userMovedCamera && points.isNotEmpty() && lastFitKey != fitKey) {
            map.moveCamera(if(points.size==1) CameraUpdateFactory.newCenterPosition(LatLng.from(points[0].y,points[0].x))
                else CameraUpdateFactory.fitMapPoints(points.map { LatLng.from(it.y,it.x) }.toTypedArray(),48))
            lastFitKey = fitKey
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

@Composable
private fun MapAuthenticationHelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnostics = remember(context) { readMapAuthenticationDiagnostics(context) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        copiedMessage = "$label 복사했어요."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("카카오 지도 인증 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("401은 지도 인증이 거절된 상태예요. 사용 중인 네이티브 앱 키의 카카오 개발자 앱에서 Android 플랫폼을 등록하고 아래 정보를 확인해 주세요.")
                SelectionContainer { Text(diagnostics.copyText) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { copy("패키지", diagnostics.packageName) }) { Text("패키지 복사") }
                    TextButton(enabled = diagnostics.signingKeyHashes.isNotEmpty(), onClick = {
                        copy("키 해시", diagnostics.signingKeyHashes.joinToString("\n"))
                    }) { Text("키 해시 복사") }
                }
                copiedMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text("이 값은 지금 설치된 APK의 서명이에요. Android Studio·클라우드·배포 빌드는 서명이 다를 수 있어 각각 등록해야 해요.")
                Text("네이티브 앱 키가 REST API 키와 바뀌지 않았는지, 같은 카카오 앱에서 지도 사용 권한이 활성화되어 있는지도 확인해 주세요. 설정을 저장한 뒤 지도를 다시 불러오세요.")
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://developers.kakao.com/console/app")))
                    }
                }) { Text("카카오 개발자 콘솔 열기") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("확인") } }
    )
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
