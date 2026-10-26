package com.example.run_or_walk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local comparison with official arrival observations, not claimed exact door-opening times.
 * No GPS, API keys or upload. These samples do not authorize a delay correction by themselves.
 */
internal class SubwayArrivalHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("subway_arrival_history", Context.MODE_PRIVATE)

    @Synchronized
    fun record(station: OfficialSubwayStation, rows: List<OfficialSubwayArrival>,
        predictions: List<LinkedSubwayTimetableEstimate>, now: Long) {
        val pending = runCatching { JSONObject(preferences.getString("pending", "{}")!!) }.getOrDefault(JSONObject())
        val errors = runCatching { JSONArray(preferences.getString("errors", "[]")!!) }.getOrDefault(JSONArray())
        fun key(row: OfficialSubwayArrival) = listOf(station.routeId, station.stopId,
            row.identity.directionId, row.trainId).joinToString(":")
        for (row in rows) {
            if (row.trainId.isBlank() || row.arrivalCode != "1" || row.identity.stopId != station.stopId) continue
            val observed = row.sourceReceivedAtEpochMillis ?: continue
            if (now - observed !in 0L..300_000L) continue
            val old = pending.optJSONObject(key(row)) ?: continue
            if (observed <= old.optLong("predictedAt") || observed - old.optLong("predictedAt") > 3_600_000L) continue
            errors.put(JSONObject().put("routeId",station.routeId).put("stopId",station.stopId)
                .put("trainNo",row.trainId).put("observedAt",observed)
                .put("earliestErrorSeconds",(observed-old.getLong("latest"))/1000)
                .put("latestErrorSeconds",(observed-old.getLong("earliest"))/1000)
                .put("basis","official_arrival_observation_not_exact_event"))
            pending.remove(key(row))
        }
        // Keep the first prediction for comparison; repeatedly replacing it would hide error.
        for (prediction in predictions) {
            val estimate = prediction.estimate
            if (estimate.source != OfficialTimetableEstimateSource.OFFICIAL_TIMETABLE_POSITION ||
                prediction.arrival.trainId.isBlank() || pending.has(key(prediction.arrival))) continue
            pending.put(key(prediction.arrival), JSONObject().put("predictedAt",now)
                .put("earliest",estimate.earliestArrivalAtEpochMillis).put("latest",estimate.latestArrivalAtEpochMillis))
        }
        pending.keys().asSequence().toList().forEach {
            if (now-pending.getJSONObject(it).optLong("predictedAt") > 3_600_000L) pending.remove(it)
        }
        val retained = JSONArray()
        for (i in (errors.length()-100).coerceAtLeast(0) until errors.length()) retained.put(errors.getJSONObject(i))
        preferences.edit().putString("pending",pending.toString()).putString("errors",retained.toString()).apply()
    }
}
