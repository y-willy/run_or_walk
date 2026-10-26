package com.example.run_or_walk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

internal class WalkingAnalysisStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("walking_analysis",Context.MODE_PRIVATE)
    fun save(result: WalkingMeasurementResult) {
        prefs.edit().putString("last",JSONObject().put("distance",result.distanceMeters).put("steps",result.steps)
            .put("pairedDistance",result.pairedDistanceMeters).put("pairedSteps",result.pairedSteps)
            .put("moving",result.movingSeconds).put("active",result.activeSeconds).put("sensor",result.sensorMode.name)
            .put("reset",result.sensorReset).put("savedAt",result.savedAtEpochMillis).toString()).apply()
    }
    fun load(): WalkingMeasurementResult? = runCatching {
        val raw = prefs.getString("last",null) ?: return null
        val json = JSONObject(raw)
        WalkingMeasurementResult(json.getDouble("distance"),json.optInt("steps").takeUnless { json.isNull("steps") },
            json.getDouble("pairedDistance"),json.getInt("pairedSteps"),json.getDouble("moving"),json.getDouble("active"),
            StepSensorMode.valueOf(json.getString("sensor")),json.optBoolean("reset"),json.getLong("savedAt"))
            .takeIf { it.distanceMeters.isFinite() && it.distanceMeters >= 0 && it.movingSeconds.isFinite() && it.movingSeconds >= 0 }
    }.getOrNull()
}

internal class WalkingAnalysisController(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(SensorManager::class.java)
    private val store = WalkingAnalysisStore(app)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var locationJob: Job? = null
    private var session = WalkingMeasurementSession()
    var phase by mutableStateOf(MeasurementPhase.READY); private set
    var result by mutableStateOf(WalkingMeasurementResult()); private set
    var saved by mutableStateOf(store.load()); private set
    var message by mutableStateOf(""); private set
    var points by mutableStateOf<List<MapPoint>>(emptyList()); private set
    var location by mutableStateOf<GuidanceLocation?>(null); private set
    fun hasActivityPermission() = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(app,
        Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    fun start() {
        if (!hasLocationPermission(app)) { message="위치 권한을 허용해 주세요."; return }
        if (phase == MeasurementPhase.FINISHED) { session=WalkingMeasurementSession(); points=emptyList(); location=null }
        val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val detector = manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val sensor = (counter ?: detector).takeIf { hasActivityPermission() }
        val mode = when {
            !hasActivityPermission() -> StepSensorMode.PERMISSION_DENIED
            counter != null -> StepSensorMode.COUNTER
            detector != null -> StepSensorMode.DETECTOR
            else -> StepSensorMode.UNAVAILABLE
        }
        if (phase == MeasurementPhase.RECORDING) return
        session.start(mode,SystemClock.elapsedRealtime()); phase=session.phase
        result=session.result(SystemClock.elapsedRealtime())
        val registered = sensor != null && runCatching { manager?.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL,0) == true }.getOrDefault(false)
        if (sensor != null && !registered) session.sensorUnavailable()
        message = if (mode in setOf(StepSensorMode.UNAVAILABLE,StepSensorMode.PERMISSION_DENIED) || !registered)
            "걸음 센서를 사용할 수 없어 GPS 속도만 측정해요. 보폭은 계산하지 않아요." else "평소 속도로 100m 이상 걸어 주세요."
        locationJob?.cancel()
        locationJob=scope.launch {
            try { liveLocationSamples(app).collect { fix ->
                val now=System.currentTimeMillis()
                if(fix.accuracyMeters > 20) message="정확한 위치를 켜고 탁 트인 곳에서 측정해 주세요."
                else if(validPedestrianFix(fix,now) && result.sensorMode in setOf(StepSensorMode.COUNTER,StepSensorMode.DETECTOR))
                    message="평소 속도로 100m 이상 걸어 주세요."
                val before=result.distanceMeters
                session.observeLocation(fix,now)
                result=session.result(SystemClock.elapsedRealtime())
                if (validPedestrianFix(fix,now) && fix.speedMetersPerSecond?.let { it <= 2.4 } != false) {
                    location=fix
                    if (points.isEmpty() || result.distanceMeters > before)
                        points=(points+fix.point).takeLast(1500)
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { pause(); message=friendlyLocationFailure(error) ?: "위치 수신이 중단되어 측정을 일시정지했어요." }
        }
    }
    fun pause() {
        session.pause(SystemClock.elapsedRealtime()); phase=session.phase
        release(); result=session.result(SystemClock.elapsedRealtime())
    }
    fun finish() {
        session.finish(SystemClock.elapsedRealtime()); phase=session.phase
        release(); result=session.result(SystemClock.elapsedRealtime())
        message=if(result.reliableSpeed) "측정을 마쳤어요. 결과를 저장할 수 있어요." else "측정 거리나 이동시간이 부족해요. 결과는 참고용으로 저장돼요."
    }
    fun save(): WalkingMeasurementResult {
        if (phase == MeasurementPhase.RECORDING) finish()
        val value=result.copy(savedAtEpochMillis=System.currentTimeMillis())
        store.save(value); saved=value; message="기기에 저장했어요."
        return value
    }
    private fun release() { locationJob?.cancel(); locationJob=null; manager?.unregisterListener(this) }
    fun close() { pause(); scope.cancel() }
    override fun onSensorChanged(event: SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { session.observeCounter(it.toLong()) }
            Sensor.TYPE_STEP_DETECTOR -> if(event.values.firstOrNull() == 1f) session.observeStep()
        }
        result=session.result(SystemClock.elapsedRealtime())
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) = Unit
}
