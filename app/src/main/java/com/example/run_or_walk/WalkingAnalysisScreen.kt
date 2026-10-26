package com.example.run_or_walk

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import java.util.Locale

@Composable
internal fun WalkingAnalysisScreen(controller: WalkingAnalysisController,lifecycleOwner: LifecycleOwner,
    settings: JourneySettings,onSettingsChange: (JourneySettings)->Unit,onBack: ()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var applySaved by remember { mutableStateOf(settings.usePersonalWalkingSpeed) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(hasLocationPermission(context)) controller.start()
    }
    fun start() {
        val permissions=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if(Build.VERSION.SDK_INT >= 29) permissions+=Manifest.permission.ACTIVITY_RECOGNITION
        if(!hasLocationPermission(context) || !controller.hasActivityPermission()) permission.launch(permissions.toTypedArray())
        else controller.start()
    }
    DisposableEffect(controller,lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_STOP) controller.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.pause() }
    }
    val result=controller.result.takeIf { controller.phase != MeasurementPhase.READY } ?: controller.saved ?: controller.result
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            TextButton(onClick=onBack) { Text("‹ 돌아가기") }
            Text("내 걷기 분석",style=MaterialTheme.typography.headlineMedium)
            Text("GPS와 걸음 센서로 평소 걷는 속도를 측정해요.",style=MaterialTheme.typography.bodyMedium)
            KakaoMapPanel(lifecycleOwner=lifecycleOwner,center=controller.location?.point,
                currentPoint=controller.location?.point,routePoints=controller.points,
                modifier=Modifier.fillMaxWidth().height(240.dp))
            WalkingAnalysisSummary(result)
            if(controller.message.isNotBlank()) Text(controller.message,style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={ start() },enabled=controller.phase != MeasurementPhase.RECORDING,modifier=Modifier.weight(1f)) {
                    Text(if(controller.phase == MeasurementPhase.PAUSED) "계속 측정" else if(controller.phase == MeasurementPhase.FINISHED) "새 측정" else "측정 시작")
                }
                OutlinedButton(onClick=controller::pause,enabled=controller.phase == MeasurementPhase.RECORDING,modifier=Modifier.weight(1f)) { Text("일시정지") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=controller::finish,enabled=controller.phase in setOf(MeasurementPhase.RECORDING,MeasurementPhase.PAUSED),modifier=Modifier.weight(1f)) { Text("종료") }
                Button(onClick={
                    val saved=controller.save()
                    if(applySaved && saved.reliableSpeed) onSettingsChange(settings.copy(personalWalkingSpeedKmh=saved.speedKmh,
                        usePersonalWalkingSpeed=true,useProviderWalkingTime=false))
                },enabled=controller.phase == MeasurementPhase.FINISHED,modifier=Modifier.weight(1f)) { Text("저장") }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("저장한 걷기 속도로 도보시간 계산",Modifier.weight(1f))
                Switch(checked=applySaved,onCheckedChange={ value ->
                    applySaved=value
                    val saved=controller.saved
                    onSettingsChange(settings.copy(usePersonalWalkingSpeed=value && saved?.reliableSpeed == true,
                        personalWalkingSpeedKmh=saved?.speedKmh?.takeIf { saved.reliableSpeed } ?: settings.personalWalkingSpeedKmh))
                })
            }
            Text("개인화 도보시간은 경로 API의 거리와 내 속도로 계산해요. 지도 제공 도보시간과 다를 수 있어요. 이 화면을 벗어나거나 화면이 꺼지면 측정이 일시정지돼요.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
internal fun WalkingAnalysisSummary(result: WalkingMeasurementResult) {
    SectionCard("측정 결과") {
        Text("이동거리  ${formatMeasurement(result.distanceMeters)} m")
        Text("걸음 수  ${result.steps?.toString() ?: "센서 사용 불가"}")
        Text("평균 한 걸음  ${result.strideCentimeters?.let { "${formatMeasurement(it)} cm" } ?: "측정 부족 / 확인 불가"}")
        Text("평균 걷기 속도  ${result.speedKmh?.let { "${formatMeasurement(it)} km/h" } ?: "확인 중"}")
        Text("1 km 예상시간  ${result.secondsPerKm?.let { "${it/60}분 ${it%60}초" } ?: "확인 중"}")
        Text(if(result.reliableSpeed) "유효한 이동 구간 기준" else "측정 신뢰도 부족 · 100m 이상, 실제 이동 1분 이상 필요",
            style=MaterialTheme.typography.bodySmall)
        if(result.sensorReset) Text("걸음 센서가 초기화되어 해당 구간을 제외했어요.",style=MaterialTheme.typography.bodySmall)
    }
}
private fun formatMeasurement(value: Double) = String.format(Locale.KOREA,"%.1f",value)
