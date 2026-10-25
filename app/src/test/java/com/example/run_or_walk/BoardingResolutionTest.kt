package com.example.run_or_walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingResolutionTest {
    private val gangnam = MapPoint(127.0276, 37.4979)
    private val line2 = OfficialSubwayStation("1002000222", "1002", "강남", "2호선")
    private val shinbundang = OfficialSubwayStation("1077000687", "1077", "강남", "신분당선")
    private fun route(name: String = "강남", line: String = "2호선") = KakaoTransitChoice(
        900, 0, line, name, gangnam, BoardingPointSource.ESTIMATED, "SUBWAY")
    private fun place(id: String = "line2", name: String = "강남역 2호선",
        point: MapPoint = gangnam, code: String? = "SW8", category: String? = null) =
        JourneyPlace(id, name, "서울 강남구", point, code, category)

    @Test
    fun exactStationAndLineResolveWithoutChoosingAnInterchangeByListOrder() {
        assertSame(line2, resolveOfficialBoardingStation(listOf(shinbundang, line2), "강남역", "수도권 2호선"))
        assertSame(line2, resolveOfficialBoardingStation(listOf(line2, shinbundang), "강남역 2호선"))
        assertNull(resolveOfficialBoardingStation(listOf(line2, shinbundang), "강남역"))
        assertNull(resolveOfficialBoardingStation(listOf(line2, shinbundang), "강남역 2호선", "신분당선"))
    }

    @Test
    fun aliasesAndLineSuffixProduceAnExactSearchInsteadOfEmptyCandidates() {
        assertEquals("강남", officialStationSearchQuery(" 강남역 2호선 "))
        assertEquals("교대", officialStationSearchQuery("교대(법원.검찰청)역 3호선"))
        val gyodae = OfficialSubwayStation("g3", "1003", "교대", "3호선")
        assertSame(gyodae, resolveOfficialBoardingStation(listOf(gyodae), "교대(법원.검찰청)역", "3호선"))
        val isu4 = OfficialSubwayStation("isu4", "1004", "총신대입구(이수)", "4호선")
        val isu7 = OfficialSubwayStation("isu7", "1007", "총신대입구(이수)", "7호선")
        assertSame(isu7, resolveOfficialBoardingStation(listOf(isu4, isu7), "이수역 7호선"))
        assertNull(resolveOfficialBoardingStation(listOf(isu4, isu7), "이수역"))
    }

    @Test
    fun sameBaseNamesOnDifferentLinesRemainAmbiguousWithoutLineEvidence() {
        val sinchon2 = OfficialSubwayStation("s2", "1002", "신촌", "2호선")
        val sinchonCentral = OfficialSubwayStation("scentral", "1063", "신촌(경의중앙선)", "경의중앙선")
        assertNull(resolveOfficialBoardingStation(listOf(sinchon2, sinchonCentral), "신촌역"))
        assertSame(sinchonCentral, resolveOfficialBoardingStation(
            listOf(sinchon2, sinchonCentral), "신촌(경의중앙선)역"))
        assertNull(resolveOfficialBoardingStation(listOf(line2), "강남구청역", "2호선"))
        assertNull(resolveOfficialBoardingStation(listOf(line2), "강남역", "2호선/3호선"))
    }

    @Test
    fun oneVerifiedNearbyPlaceCanReplaceAnEstimatedRouteBoardingPoint() {
        val chosen = place()
        val otherLine = place("shinbundang", "강남역 신분당선")
        assertSame(chosen, resolveBoardingPlace(route(), listOf(otherLine, chosen)))
        assertSame(chosen, resolveBoardingPlace(route(), listOf(chosen, chosen)))
        val generic = place("generic", "강남역")
        assertSame(chosen, resolveBoardingPlace(route(), listOf(generic, chosen)))
        assertSame(generic, resolveBoardingPlace(route(), listOf(generic)))
    }

    @Test
    fun ambiguousOrDistantOrUnknownCategoryPlacesNeverAutoConfirm() {
        assertNull(resolveBoardingPlace(route(), listOf(place(), place("other", "강남역 2호선"))))
        assertNull(resolveBoardingPlace(route(), listOf(place(name = "강남구청역 2호선"))))
        assertNull(resolveBoardingPlace(route(), listOf(place(point = MapPoint(127.05, 37.51)))))
        assertNull(resolveBoardingPlace(route(), listOf(place(code = null))))
        assertNull(resolveBoardingPlace(route(), listOf(place(code = null, category = "음식점 > 카페"))))
        assertNull(resolveBoardingPlace(route().copy(boardingPoint = null), listOf(place())))
        assertNull(resolveBoardingPlace(route().copy(boardingPoint = MapPoint(Double.NaN, 37.5)), listOf(place())))
        assertNull(resolveBoardingPlace(route(line = "2호선/3호선"), listOf(place())))
    }

    @Test
    fun categoryMetadataSurvivesUiPlaceConversionAndCannotCrossTransitTypes() {
        val subway = JourneyPlace.from(KakaoPlace("1", "강남역 2호선", "서울", gangnam, "SW8"))
        assertTrue(subway.isSubwayStation)
        assertSame(subway, resolveBoardingPlace(route(), listOf(subway)))
        val bus = JourneyPlace.from(KakaoPlace("2", "강남역", "서울", gangnam,
            categoryName = "교통,수송 > 교통시설 > 버스정류장"))
        assertTrue(bus.isBusStop)
        assertFalse(bus.isSubwayStation)
        assertNull(resolveBoardingPlace(route(), listOf(bus)))
        assertNull(resolveBoardingPlace(route().copy(firstTransitType = "BUS"), listOf(subway)))
        assertSame(bus, resolveBoardingPlace(route().copy(firstTransitType = "BUS"), listOf(bus)))
    }

    @Test
    fun automaticPhysicalPlaceConfirmationDoesNotCreateOfficialStationOrTrainIdentity() {
        val state = JourneyScreenState(FeatureMode.ROUTE)
        state.boarding = place()
        state.boardingSource = BoardingPointSource.ESTIMATED
        assertFalse(state.boardingConfirmed)
        state.boardingMatchedAutomatically = true
        assertTrue(state.boardingConfirmed)
        assertFalse(state.boardingChosenByUser)
        assertNull(state.boardingNotice)
        assertNull(state.selectedStation)
        assertNull(state.selectedArrival)
        assertNull(state.confirmedServiceIdentity)
        state.editQuery(SearchField.DESTINATION, "새 목적지")
        assertFalse(state.boardingConfirmed)
        assertFalse(state.boardingMatchedAutomatically)
    }
}
