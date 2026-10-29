package com.example.run_or_walk

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.example.run_or_walk.ui.theme.RUN_or_WALKTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class,
    qualifiers = "w360dp-h1100dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBasedWalkingGuidanceUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun stoppedThenMovingThenMissingVelocityKeepsGuidanceWithTheConfiguredBaseline() {
        val now = 1_800_000_000_000L
        val point = MapPoint(127.0, 37.0)
        val id = OfficialBoardingIdentity("seoul", "1001000133", "1001", "상행", "서울역")
        val settings = JourneySettings()
        var fix by mutableStateOf(GuidanceLocation(point, 5.0, now, 0.0, 0.2))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {
                val pace = usableWalkingCalibration(1.8, fix, now)
                val seconds = settings.walkingSeconds(300, 250, pace)
                val arrival = RealtimeArrivalSnapshot(id, now + 600_000,
                    fetchedAtEpochMillis = now, validForMillis = 60_000, receivedAtEpochMillis = now)
                val assessment = calculateMovementGuidance(AutoGuidanceInput(fix,
                    GuidanceBoarding(id, point, true),
                    GuidanceWalkingRoute(point, point, id, seconds, now, 120_000, 300), arrival, true), now, settings)
                RUN_or_WALKTheme {
                    Column {
                        JourneyMetrics(displayedWalkingSpeed(fix, now), 300)
                        AutomaticGuidanceCard(assessment, "1호선", "서울역", "", now,
                            arrival.firstArrivalAtEpochMillis, null, now, seconds)
                    }
                }
            }
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.onNodeWithText("여유 있어요").assertIsDisplayed()
            compose.onNodeWithText("정보 확인 중").assertDoesNotExist()
            compose.runOnIdle { fix = fix.copy(speedMetersPerSecond = 1.8) }
            compose.onNodeWithText("6.5 km/h").assertIsDisplayed()
            compose.onNodeWithText("여유 있어요").assertIsDisplayed()
            compose.runOnIdle { fix = fix.copy(speedMetersPerSecond = null, speedAccuracyMetersPerSecond = null) }
            compose.onNodeWithText("0.0 km/h").assertIsDisplayed()
            compose.onNodeWithText("여유 있어요").assertIsDisplayed()
            compose.onNodeWithText("정보 확인 중").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }
}
