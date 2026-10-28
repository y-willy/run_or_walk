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
    val favorites = FavoriteBusStore(application)
    val favoriteArrivals = FavoriteBusArrivalLoader()
    val walkingAnalysis = WalkingAnalysisController(application)
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
