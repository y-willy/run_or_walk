package com.example.run_or_walk

/** An explicit row selection; the ordinal may change when the preceding train leaves. */
internal data class SubwayArrivalChoice(val identity: OfficialBoardingIdentity, val trainId: String, val slot: Int?) {
    fun matches(row: OfficialSubwayArrival): Boolean =
        identity.authority == row.identity.authority && identity.stopId == row.identity.stopId &&
            identity.routeId == row.identity.routeId && identity.directionId == row.identity.directionId &&
            if (trainId.isNotBlank()) row.trainId == trainId
            else row.identity == identity && row.trainId.isBlank() && slot == row.officialSlot

    companion object {
        fun from(row: OfficialSubwayArrival) = SubwayArrivalChoice(row.identity,row.trainId,row.officialSlot)
    }
}

internal fun SubwayArrivalChoice?.sameSelectionAs(other: SubwayArrivalChoice?): Boolean {
    if(this == null || other == null) return this == other
    return identity.authority == other.identity.authority && identity.stopId == other.identity.stopId &&
        identity.routeId == other.identity.routeId && identity.directionId == other.identity.directionId &&
        if(trainId.isNotBlank()) trainId == other.trainId
        else other.trainId.isBlank() && slot == other.slot && identity.serviceVariant == other.identity.serviceVariant
}

internal fun canSelectSubwayArrival(row: OfficialSubwayArrival, station: OfficialSubwayStation?): Boolean =
    station != null && row.identity.isComplete && row.identity.authority == "seoul" &&
        row.identity.stopId == station.stopId && row.identity.routeId == station.routeId &&
        row.identity.directionId in setOf("상행","하행","내선","외선") && row.arrivalCode != "2"
