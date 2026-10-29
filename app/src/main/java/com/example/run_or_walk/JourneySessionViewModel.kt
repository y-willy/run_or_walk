package com.example.run_or_walk

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Survives Activity recreation and persists only explicit static choices across app restarts. */
internal class JourneySessionViewModel(application: Application) : AndroidViewModel(application) {
    val displayClock=GuidanceDisplayClock(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime(),android.os.SystemClock::elapsedRealtime)
    val favorites = FavoriteBusStore(application)
    val favoriteArrivals = FavoriteBusArrivalLoader()
    val walkingAnalysis = WalkingAnalysisController(application)
    internal var walkingSearch:suspend (MapPoint,MapPoint)->KakaoWalkingRoute = KakaoApiRepository(BuildConfig.KAKAO_REST_KEY)::searchWalk
    internal var walkingLocation:suspend ()->GuidanceLocation = {getCurrentLocationSample(application)}
    internal var walkingLocations:()->kotlinx.coroutines.flow.Flow<GuidanceLocation> = {liveLocationSamples(application)}
    // GPS is physical session state, independent of the selected route/vehicle. Never persisted.
    var latestLocation:GuidanceLocation? by mutableStateOf(null);private set
    fun observeLocation(sample:GuidanceLocation,now:Long):Boolean {
        if(locationSampleFailure(sample,now)!=null || latestLocation?.let {sample.observedAtEpochMillis<it.observedAtEpochMillis}==true) return false
        latestLocation=sample;return true
    }
    fun locationAt(now:Long,vararg candidates:GuidanceLocation?):GuidanceLocation? =
        (candidates.toList()+latestLocation).filterNotNull().map(displayClock::alignLocation).filter {locationSampleFailure(it,now)==null}
            .maxByOrNull {it.observedAtEpochMillis}
    private val store = JourneySessionStore(application)
    private val saved = store.load()
    val screens = restoreJourneyScreens(saved)
    var mode by mutableStateOf(saved?.mode ?: FeatureMode.ROUTE)
    private val settingsStore = JourneySettingsStore(application)
    var settings by mutableStateOf(settingsStore.load())
        private set
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        persistenceScope.launch {
            snapshotFlow { captureJourneySession(mode, screens) }.collectLatest { snapshot ->
                delay(200L)
                // An onStop flush must not subsequently be overwritten by an older delayed snapshot.
                if (snapshot == captureJourneySession(mode, screens)) store.save(snapshot)
            }
        }
    }

    fun updateSettings(value: JourneySettings) {
        settings = value
        settingsStore.save(value)
    }

    /** Call from Activity.onStop, before Android may reclaim the process. */
    fun flush() {
        store.save(captureJourneySession(mode, screens))
        settingsStore.save(settings)
    }

    override fun onCleared() {
        walkingAnalysis.close()
        flush()
        persistenceScope.cancel()
        super.onCleared()
    }
}
