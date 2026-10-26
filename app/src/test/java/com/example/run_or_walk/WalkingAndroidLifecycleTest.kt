package com.example.run_or_walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Vibrator
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class WalkingAndroidLifecycleTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun deniedLocationDoesNotStartMeasurementOrRegisterSensor() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION)
        val controller=WalkingAnalysisController(app)
        val sensors=Shadows.shadowOf(app.getSystemService(SensorManager::class.java))
        controller.start()
        assertEquals(MeasurementPhase.READY,controller.phase)
        assertFalse(sensors.hasListener(controller))
        assertTrue(controller.message.contains("위치 권한"))
        controller.close()
    }
    @Test fun registeredStepCounterIsRemovedWhenPausedAndClosed() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACTIVITY_RECOGNITION)
        Shadows.shadowOf(app.getSystemService(android.location.LocationManager::class.java)).setLocationEnabled(true)
        val manager=app.getSystemService(SensorManager::class.java)
        val sensors=Shadows.shadowOf(manager)
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER))
        val controller=WalkingAnalysisController(app)
        controller.start()
        assertTrue(sensors.hasListener(controller))
        controller.pause()
        assertFalse(sensors.hasListener(controller)); assertEquals(MeasurementPhase.PAUSED,controller.phase)
        controller.close(); assertFalse(sensors.hasListener(controller))
    }
    @Test fun deniedActivityPermissionUsesNoFabricatedStepCount() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val controller=WalkingAnalysisController(app)
        controller.start()
        assertNull(controller.result.steps)
        assertEquals(StepSensorMode.PERMISSION_DENIED,controller.result.sensorMode)
        assertNull(controller.result.strideCentimeters)
        controller.pause(); controller.close()
    }
    @Test fun preferencesPersistOnlyMeasurementSummaryAndRecoverAcrossControllerCreation() {
        val store=WalkingAnalysisStore(app)
        val expected=WalkingMeasurementResult(120.0,150,120.0,150,100.0,120.0,StepSensorMode.COUNTER,false,1800000000000)
        store.save(expected)
        assertEquals(expected,WalkingAnalysisStore(app).load())
        val controller=WalkingAnalysisController(app)
        assertEquals(expected,controller.saved)
        controller.close()
    }
    @Test fun serviceDestructionCancelsAllTrackingAndRouteJobsAndClearsState() {
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get()
        val jobs=listOf("locationJob","walkingJob","monitoringJob","timeoutJob").associateWith { Job() }
        jobs.forEach { (name,job) -> service.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(service,job) }
        val field=service.javaClass.getDeclaredField("generation").apply { isAccessible=true }
        val before=field.getLong(service)
        JourneyMonitoringController.publish(JourneyMonitoringState(running=true))
        lifecycle.destroy()
        assertTrue(jobs.values.all { it.isCancelled })
        assertTrue(field.getLong(service)>before)
        assertFalse(JourneyMonitoringController.state.value.running)
        assertNull(service.javaClass.getDeclaredField("routeTracker").apply { isAccessible=true }.get(service))
    }
    @Test @Config(sdk=[28]) fun legacyAndroidDoesNotRequireActivityRecognitionRuntimePermission() {
        val controller=WalkingAnalysisController(app)
        assertTrue(controller.hasActivityPermission()); controller.close()
    }
    @Suppress("DEPRECATION")
    @Test @Config(sdk=[28]) fun realVibratorInvocationUsesTwoPulsesWithoutRepeating() {
        val vibrator=app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val shadow=Shadows.shadowOf(vibrator); shadow.setHasVibrator(true)
        playMovementVibration(app,2)
        assertArrayEquals(longArrayOf(0,100,150,100),shadow.pattern)
        assertEquals(-1,shadow.repeat)
    }
    @Test fun delayedOlderGpsCannotOverwriteTheLatestLocation() {
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get()
        val point=MapPoint(127.0,37.0)
        val id=OfficialBoardingIdentity("seoul","1001000133","1001","상행","서울역")
        val request=JourneyMonitoringRequest("서울역",point,id,subwayStation=OfficialSubwayStation(id.stopId,id.routeId,"서울역","1호선"))
        service.javaClass.getDeclaredField("currentRequest").apply { isAccessible=true }.set(service,request)
        val latest=GuidanceLocation(point,5.0,System.currentTimeMillis())
        JourneyMonitoringController.publish(JourneyMonitoringState(true,request,location=latest))
        service.javaClass.getDeclaredMethod("updateWalkingLocation",GuidanceLocation::class.java).apply { isAccessible=true }
            .invoke(service,latest.copy(point=point.copy(y=37.1),observedAtEpochMillis=latest.observedAtEpochMillis-4000))
        assertEquals(latest,JourneyMonitoringController.state.value.location)
        assertTrue(JourneyMonitoringController.state.value.running)
        lifecycle.destroy()
    }
    @Test fun anOldCompletedResultCannotResurrectStoppedGuidance() {
        val lifecycle=Robolectric.buildService(JourneyMonitoringService::class.java).create()
        val service=lifecycle.get()
        val point=MapPoint(127.0,37.0)
        val id=OfficialBoardingIdentity("seoul","1001000133","1001","상행","서울역")
        val request=JourneyMonitoringRequest("서울역",point,id,subwayStation=OfficialSubwayStation(id.stopId,id.routeId,"서울역","1호선"))
        service.javaClass.getDeclaredField("currentRequest").apply { isAccessible=true }.set(service,request)
        JourneyMonitoringController.publish(JourneyMonitoringState())
        val previous=JourneyMonitoringState(true,request,assessment=AutoGuidanceAssessment(AutoGuidanceStatus.WALK,boardingIdentity=id))
        service.javaClass.getDeclaredMethod("publishMovement",JourneyMonitoringState::class.java).apply { isAccessible=true }.invoke(service,previous)
        assertFalse(JourneyMonitoringController.state.value.running)
        assertNull(JourneyMonitoringController.state.value.assessment)
        lifecycle.destroy()
    }

}
